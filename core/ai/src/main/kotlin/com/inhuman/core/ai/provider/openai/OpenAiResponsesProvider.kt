package com.inhuman.core.ai.provider.openai

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
 * OpenAI Responses provider.
 *
 * Bearer authentication and the same `/models` listing as the OpenAI-compatible
 * adapter; only the completion endpoint and wire shape differ. The base URL may
 * be entered with or without `/v1`; [OpenAiResponsesWire.endpoint] adds it.
 */
class OpenAiResponsesProvider(
	private val apiKey: String,
	private val baseUrl: String,
	override val id: String,
	private val client: OkHttpClient = DeepSeekProvider.shared,
) : ChatProvider {

	override suspend fun listModels(): List<RemoteModel> = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(OpenAiResponsesWire.endpoint(baseUrl, "/models?limit=200"))
			.header("Authorization", "Bearer $apiKey")
			.header("Accept", "application/json")
			.build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) {
				error("HTTP ${response.code} ${response.body.string().take(300)}")
			}
			val root = OpenAiResponsesWire.json.parseToJsonElement(response.body.string()).jsonObject
			root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
				val model = element.jsonObject
				val modelId = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				RemoteModel(
					id = modelId,
					displayName = modelId,
					// The models endpoint does not advertise modalities; the
					// capability guesser tags known multimodal IDs later.
					inputModalities = listOf("text"),
				)
			}
		}
	}

	override fun stream(request: ChatRequest): Flow<StreamChunk> = callbackFlow {
		val body = OpenAiResponsesWire.requestBody(request).toString()
		val httpRequest = Request.Builder()
			.url(OpenAiResponsesWire.endpoint(baseUrl, "/responses"))
			.header("Authorization", "Bearer $apiKey")
			.header("Accept", "text/event-stream")
			.header("Content-Type", "application/json")
			.post(body.toRequestBody(JSON_MEDIA_TYPE))
			.build()
		val startedAt = System.currentTimeMillis()
		val call = client.newCall(httpRequest)
		val parser = SseParser()
		val decoder = OpenAiResponsesDecoder()

		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				Diag.w("http", "openai responses request failed: ${e.message}")
				trySend(StreamChunk.Failure(e.message ?: "network error", e))
				close()
			}

			override fun onResponse(call: Call, response: Response) {
				response.use { res ->
					Diag.i("http", "openai responses HTTP ${res.code}")
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
						Diag.d("http", "openai responses stream cancelled")
					} catch (e: IOException) {
						Diag.e("http", "openai responses stream read failed", e)
						trySend(StreamChunk.Failure(e.message ?: "stream read failed", e))
					} finally {
						Diag.i(
							"http",
							"openai responses stream end lines=$lines frames=$frames chunks=$chunks " +
								"ms=${System.currentTimeMillis() - startedAt}",
						)
						close()
					}
				}
			}
		})

		awaitClose { call.cancel() }
	}
		.buffer(Channel.UNLIMITED)
		.flowOn(Dispatchers.IO)

	override fun previewRequest(request: ChatRequest): JsonObject =
		OpenAiResponsesWire.requestBody(request)

	private companion object {
		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}