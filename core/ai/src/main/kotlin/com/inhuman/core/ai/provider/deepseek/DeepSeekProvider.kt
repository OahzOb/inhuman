package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.stream.SseParser
import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible chat over `/chat/completions`.
 *
 * The class and package keep the DeepSeek name because that is the seeded
 * profile and the decoder's measured behaviour was established there. Every
 * provider profile uses the same class with its own base URL and reasoning mode;
 * keeping one implementation is the reason a custom profile needs no second SSE
 * decoder.
 *
 * ## Why the raw response body rather than `okhttp-sse`
 *
 * `okhttp-sse` is a fine EventSource client, but it buys little here and costs
 * something. Little, because the provider-specific decoding has to be written
 * regardless, and a generic emitter adds a callback layer to bridge into
 * coroutines. Something, because its automatic reconnection is actively harmful
 * for LLM streaming: a silent reconnect re-runs the completion and duplicates
 * the answer, since these APIs offer no `Last-Event-ID` resume.
 *
 * Reading the body line by line keeps the socket lifecycle explicit, which is
 * what makes cancellation immediate. Retrofit would be worse still -- its
 * converter model fights incremental bodies.
 */
class DeepSeekProvider(
	private val apiKey: String,
	private val baseUrl: String = DEFAULT_BASE_URL,
	private val client: OkHttpClient = shared,
	override val id: String = "deepseek",
	/**
	 * Which reasoning fields this endpoint accepts.
	 *
	 * `deepseek` sends `thinking`, `reasoning_effort` and replays
	 * `reasoning_content`. `effort` sends only the standard
	 * `reasoning_effort`. `none` sends none of them, which is the safe default
	 * for an arbitrary OpenAI-compatible server.
	 */
	private val reasoningMode: String = "deepseek",
	/** Some compatible servers reject `stream_options`; let the profile omit it. */
	private val includeStreamUsage: Boolean = true,
) : ChatProvider {

	override suspend fun listModels(): List<RemoteModel> =
		DeepSeekModels.fetch(client, baseUrl, apiKey)

	override fun previewRequest(request: ChatRequest): JsonObject =
		DeepSeekRequest.build(
			request = request,
			reasoningMode = reasoningMode,
			includeStreamUsage = includeStreamUsage,
		)

	/**
	 * Stream a completion.
	 *
	 * Cancelling the collector cancels the HTTP call. That is not automatic:
	 * cancelling a coroutine does not by itself unblock a socket read, so the
	 * call is cancelled explicitly on completion of the flow. Without it a
	 * cancelled generation holds the connection until the read timeout, and the
	 * server keeps generating tokens nobody will read.
	 */
	override fun stream(request: ChatRequest): Flow<StreamChunk> = callbackFlow {
		val startedAt = System.currentTimeMillis()
		val payload = try {
			DeepSeekRequest.build(
				request = request,
				reasoningMode = reasoningMode,
				includeStreamUsage = includeStreamUsage,
			).toString()
		} catch (e: Exception) {
			// A request that cannot be serialised never reaches the network, so
			// there is no HTTP status to explain it. This line is the only
			// record of what was asked for.
			Diag.e("http", "request build failed model=${request.model}", e)
			throw e
		}
		Diag.i(
			"http",
			"chat request model=${request.model} messages=${request.messages.size} " +
				"tools=${request.tools.size} thinking=${request.thinking ?: "default"} " +
				"effort=${request.reasoningEffort ?: "-"} bytes=${payload.length}",
		)
		val requestBody = payload.toRequestBody(JSON_MEDIA_TYPE)

		val httpRequest = Request.Builder()
			.url("$baseUrl/chat/completions")
			.header("Authorization", "Bearer $apiKey")
			.header("Accept", "text/event-stream")
			.post(requestBody)
			.build()

		val call = client.newCall(httpRequest)
		val parser = SseParser()
		val decoder = DeepSeekStreamDecoder()

		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				// A cancel arrives here too, as an IOException on a closed
				// socket. Reporting it as a stream failure would make every
				// user-initiated stop look like an error in the transcript.
				if (call.isCanceled()) {
					Diag.d("http", "call cancelled by collector")
					close()
					return
				}
				Diag.w(
					"http",
					"call failed ${e.javaClass.simpleName}: ${e.message} " +
						"ms=${System.currentTimeMillis() - startedAt}",
					e,
				)
				// ## The order here is the whole fix
				//
				// This read `close()` then `trySend(...)`, and a chunk sent
				// after the channel is closed is **dropped** -- the collector
				// sees the flow end normally and cannot tell "the network died"
				// from "the model finished without saying anything". So every
				// DNS failure, refused connection and TLS error arrived at the
				// view model as an empty successful stream: no error, no
				// transcript entry, and a reply bubble that got deleted for
				// being empty. It looked like the model had nothing to say.
				//
				// Pinned by `CallbackFlowFailureDeliveryTest`, because the
				// behaviour is a fact about the coroutines library rather than
				// about this file.
				trySend(StreamChunk.Failure(e.message ?: "network error", e))
				close()
			}

			override fun onResponse(call: Call, response: Response) {
				response.use { res ->
					Diag.i("http", "response HTTP ${res.code} ${res.message}")
					if (!res.isSuccessful) {
						// Read the body: DeepSeek returns a JSON error object
						// with a usable message, and dropping it turns a
						// diagnosable failure ("insufficient balance",
						// "invalid api key") into a bare status code.
						val detail = try {
							res.body.string().take(600)
						} catch (_: Exception) {
							null
						}
						Diag.w(
							"http",
							"request failed HTTP ${res.code}: ${detail?.take(300) ?: "-"}",
						)
						trySend(
							StreamChunk.Failure(
								"HTTP ${res.code} ${res.message}" +
									if (detail.isNullOrBlank()) "" else ": $detail",
							),
						)
						close()
						return
					}

					// OkHttp 5 made ResponseBody non-null on Response, so this
					// needs no safe call -- but source() can still throw for a
					// body that is already consumed, which is why the read below
					// stays inside the try.
					val source = res.body.source()
					var lines = 0
					var frames = 0
					var chunks = 0

					try {
						// readUtf8Line returns null at end of body. It strips
						// the newline; a trailing CR is handled by the parser.
						while (isActive) {
							val line = source.readUtf8Line() ?: break
							lines++
							val frame = parser.accept(line) ?: continue
							frames++
							decoder.decode(frame).forEach { chunk ->
								chunks++
								trySend(chunk)
							}
						}
						// Flush: a server that closes without its final blank
						// line would otherwise lose the last chunk.
						parser.endOfStream()?.let { frame ->
							frames++
							decoder.decode(frame).forEach { chunk ->
								chunks++
								trySend(chunk)
							}
						}
						decoder.endOfStream().forEach { chunk ->
							chunks++
							trySend(chunk)
						}
					} catch (e: CancellationException) {
						// Normal stop.
						Diag.d("http", "stream cancelled")
					} catch (e: IOException) {
						Diag.e("http", "stream read failed", e)
						trySend(StreamChunk.Failure(e.message ?: "stream read failed", e))
					} finally {
						// One line per request, with the shape of what arrived.
						// A stream that ended with frames=0 is a different bug
						// from one that delivered text and then failed, and the
						// failure chunk alone cannot tell them apart.
						Diag.i(
							"http",
							"stream end lines=$lines frames=$frames chunks=$chunks " +
								"ms=${System.currentTimeMillis() - startedAt}",
						)
						close()
					}
				}
			}
		})

		awaitClose {
			// The important line. Cancelling the collector must close the
			// socket, not merely stop reading from it.
			Diag.d("http", "collector closed; cancelling call")
			call.cancel()
		}
	}
		// `trySend` is the only option in OkHttp's callbacks: they run on a
		// blocking dispatcher thread, not in a coroutine, so there is no `send`
		// and no backpressure. The default 64-slot buffer then made `trySend`
		// fail on a fast stream -- or on a collector busy with a tool call --
		// and the chunk was **dropped**. The reply arrived with holes in it and
		// nothing anywhere said a token had been lost.
		//
		// Fusing the buffer to unlimited is what makes `trySend` lossless here.
		// The memory ceiling is one reply's worth of text, small next to the
		// request that produced it.
		.buffer(Channel.UNLIMITED)
		.flowOn(Dispatchers.IO)

	companion object {
		const val DEFAULT_BASE_URL = "https://api.deepseek.com"

		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

		internal val json = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
			encodeDefaults = false
		}

		/**
		 * The one client the app makes requests with.
		 *
		 * ## Why it is shared rather than per provider
		 *
		 * This used to be a factory called for every `DeepSeekProvider`, and a
		 * provider is constructed per turn -- so every round trip of every turn
		 * got a **new connection pool, a new dispatcher and a new executor**.
		 * Whatever the previous request had warmed, the next one threw away:
		 * no keep-alive, no TLS session reuse, and a fresh handshake for a
		 * conversation that had just been talking to the same host seconds
		 * earlier. It also left each abandoned client's threads alive until
		 * finalisation.
		 *
		 * Sharing one client is the arrangement OkHttp is designed around and
		 * documents: "It is recommended to share a single instance". Nothing
		 * here needs a per-turn client -- the timeouts are constant, and the
		 * reasons route by `Call`, not by client.
		 *
		 * The settings below are unchanged, and they are all deliberate:
		 */
		val shared: OkHttpClient by lazy {
			OkHttpClient.Builder()
				// Headers normally arrive in milliseconds; the long read timeout
				// is for the body after them. Without this a silent connection
				// held the spinner until the app was restarted.
				.addInterceptor(HeaderTimeoutInterceptor())
				// Long read timeout on purpose. DeepSeek documents that the
				// server closes the connection if inference has not begun within
				// 10 minutes, and reasoning models routinely take a long time
				// before the first token. A conventional 30-second read timeout
				// kills legitimate slow responses while they are still thinking.
				.readTimeout(11, TimeUnit.MINUTES)
				.connectTimeout(30, TimeUnit.SECONDS)
				// No automatic retries: a retried POST that already produced
				// tokens would duplicate the answer, and the caller is better
				// placed to decide whether re-running is safe.
				.retryOnConnectionFailure(false)
				.build()
		}
	}
}
