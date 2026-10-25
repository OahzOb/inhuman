package com.inhuman.core.ai.provider.gemini

import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.StreamChunk
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
import kotlinx.serialization.json.intOrNull
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
 * Google Generative AI provider. Gemini API keys use `x-goog-api-key`; the
 * project id/Vertex credentials are not portable to a phone and are left out.
 */
class GeminiProvider(
	private val apiKey: String,
	private val baseUrl: String,
	override val id: String,
	private val client: OkHttpClient = DeepSeekProvider.shared,
) : ChatProvider {

	override suspend fun listModels(): List<RemoteModel> = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url("${GeminiWire.base(baseUrl)}/models?pageSize=1000")
			.header("x-goog-api-key", apiKey)
			.header("Accept", "application/json")
			.build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) {
				error("HTTP ${response.code} ${response.body.string().take(300)}")
			}
			val root = GeminiWire.json.parseToJsonElement(response.body.string()).jsonObject
			root["models"]?.jsonArray.orEmpty().mapNotNull { element ->
				val model = element.jsonObject
				val raw = model["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				val methods = model["supportedGenerationMethods"]?.jsonArray
					?.mapNotNull { it.jsonPrimitive.contentOrNull }
					.orEmpty()
				if ("generateContent" !in methods) return@mapNotNull null
				RemoteModel(
					id = raw.removePrefix("models/"),
					displayName = model["displayName"]?.jsonPrimitive?.contentOrNull,
					contextWindow = model["inputTokenLimit"]?.jsonPrimitive?.intOrNull,
					maxOutputTokens = model["outputTokenLimit"]?.jsonPrimitive?.intOrNull,
					inputModalities = listOf("text", "image"),
				)
			}
		}
	}

	override fun stream(request: ChatRequest): Flow<StreamChunk> = callbackFlow {
		val body = GeminiWire.requestBody(request).toString()
		val httpRequest = Request.Builder()
			.url(GeminiWire.modelPath(baseUrl, request.model) + ":streamGenerateContent?alt=sse")
			.header("x-goog-api-key", apiKey)
			.header("Accept", "text/event-stream")
			.header("Content-Type", "application/json")
			.post(body.toRequestBody(JSON_MEDIA_TYPE))
			.build()
		val startedAt = System.currentTimeMillis()
		val call = client.newCall(httpRequest)
		val parser = SseParser()
		val decoder = GeminiDecoder()

		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				Diag.w("http", "gemini request failed: ${e.message}")
				trySend(StreamChunk.Failure(e.message ?: "network error", e))
				close()
			}

			override fun onResponse(call: Call, response: Response) {
				response.use { res ->
					Diag.i("http", "gemini response HTTP ${res.code}")
					if (!res.isSuccessful) {
						val detail = runCatching { res.body.string().take(600) }.getOrNull()
						trySend(
							StreamChunk.Failure(
								"HTTP ${res.code} ${res.message}" +
									if (detail.isNullOrBlank()) "" else ": $detail",
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
						Diag.d("http", "gemini stream cancelled")
					} catch (e: IOException) {
						Diag.e("http", "gemini stream read failed", e)
						trySend(StreamChunk.Failure(e.message ?: "stream read failed", e))
					} finally {
						Diag.i(
							"http",
							"gemini stream end lines=$lines frames=$frames chunks=$chunks " +
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

	override fun previewRequest(request: ChatRequest): JsonObject = GeminiWire.requestBody(request)

	private companion object {
		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}
