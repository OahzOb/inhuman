package com.inhuman.core.ai.provider.anthropic

import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.provider.ProviderFactory
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Anthropic Messages provider.
 *
 * API keys use `x-api-key`; an OAuth access token uses `Authorization: Bearer`
 * plus the beta header Anthropic requires for subscription tokens. The two are
 * separate credentials and the caller names which one it holds.
 */
class AnthropicProvider(
	private val apiKey: String,
	private val baseUrl: String,
	override val id: String,
	private val auth: ProviderFactory.Auth = ProviderFactory.Auth.ApiKey,
	private val client: OkHttpClient = DeepSeekProvider.shared,
) : ChatProvider {

	private fun Request.Builder.authenticate(): Request.Builder = apply {
		header("anthropic-version", AnthropicWire.API_VERSION)
		if (auth == ProviderFactory.Auth.OAuth) {
			header("Authorization", "Bearer $apiKey")
			header("anthropic-beta", "oauth-2025-04-20")
		} else {
			header("x-api-key", apiKey)
		}
	}

	override suspend fun listModels(): List<RemoteModel> = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(AnthropicWire.endpoint(baseUrl, "/models?limit=100"))
			.authenticate()
			.header("Accept", "application/json")
			.build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) {
				error("HTTP ${response.code} ${response.body.string().take(300)}")
			}
			val root = AnthropicWire.json.parseToJsonElement(response.body.string()).jsonObject
			root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
				val model = element.jsonObject
				val modelId = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				RemoteModel(
					id = modelId,
					displayName = model["display_name"]?.jsonPrimitive?.contentOrNull,
					// Claude models accept images; the models endpoint does not
					// advertise modalities.
					inputModalities = listOf("text", "image"),
				)
			}
		}
	}

	override fun stream(request: ChatRequest): Flow<StreamChunk> = callbackFlow {
		val body = AnthropicWire.requestBody(request).toString()
		val httpRequest = Request.Builder()
			.url(AnthropicWire.endpoint(baseUrl, "/messages"))
			.authenticate()
			.header("Accept", "text/event-stream")
			.header("Content-Type", "application/json")
			.post(body.toRequestBody(JSON_MEDIA_TYPE))
			.build()
		val startedAt = System.currentTimeMillis()
		val call = client.newCall(httpRequest)
		val parser = SseParser()
		val decoder = AnthropicDecoder()

		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				Diag.w("http", "anthropic request failed: ${e.message}")
				trySend(StreamChunk.Failure(e.message ?: "network error", e))
				close()
			}

			override fun onResponse(call: Call, response: Response) {
				response.use { res ->
					Diag.i("http", "anthropic response HTTP ${res.code}")
					if (!res.isSuccessful) {
						val detail = runCatching { res.body.string().take(600) }.getOrNull()
						trySend(
							StreamChunk.Failure(
								"HTTP ${res.code} ${res.message}" +
									if (detail.isNullOrBlank()) "" else ": $detail",
								statusCode = res.code,
							),
						)
						close()
						return
					}
					val source = res.body.source()
					var lines = 0
					var frames = 0
					var chunks = 0
					try {
						while (isActive) {
							val line = source.readUtf8Line() ?: break
							lines++
							val frame = parser.accept(line) ?: continue
							frames++
							decoder.decode(frame).forEach {
								chunks++
								trySend(it)
							}
						}
						parser.endOfStream()?.let { frame ->
							frames++
							decoder.decode(frame).forEach {
								chunks++
								trySend(it)
							}
						}
						decoder.endOfStream().forEach {
							chunks++
							trySend(it)
						}
					} catch (e: CancellationException) {
						Diag.d("http", "anthropic stream cancelled")
					} catch (e: IOException) {
						Diag.e("http", "anthropic stream read failed", e)
						trySend(StreamChunk.Failure(e.message ?: "stream read failed", e))
					} finally {
						Diag.i(
							"http",
							"anthropic stream end lines=$lines frames=$frames chunks=$chunks " +
								"ms=${System.currentTimeMillis() - startedAt}",
						)
						close()
					}
				}
			}
		})

		awaitClose { call.cancel() }
	}
		// `trySend` is the only option in OkHttp's callbacks: they run on a
		// blocking dispatcher thread, not in a coroutine, so there is no `send`
		// and no backpressure. The default 64-slot buffer then made `trySend`
		// fail on a fast stream and the chunk was dropped without a trace.
		// Fusing the buffer to unlimited is what makes it lossless; the memory
		// ceiling is one reply's worth of text.
		.buffer(Channel.UNLIMITED)
		.flowOn(Dispatchers.IO)

	override fun previewRequest(request: ChatRequest): JsonObject = AnthropicWire.requestBody(request)

	private companion object {
		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}
