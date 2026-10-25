package com.inhuman.core.ai.provider.qwen

import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Qwen Token Plan's non-chat endpoints.
 *
 * A Token Plan key is one credential for several API families, and they do not
 * share a path: chat lives under `/compatible-mode/v1`, image/video/speech under
 * `/api/v1/services/...`, and decisions at `/compatible-mode/v1/systemone`.
 * This class is deliberately separate from [com.inhuman.core.ai.ChatProvider]:
 * the shapes are unrelated (URL results, async task ids, typed decisions).
 */
class QwenTokenPlanClient(
	private val apiKey: String,
	chatBaseUrl: String,
	private val client: OkHttpClient = DeepSeekProvider.shared,
) {

	/** E.g. `https://token-plan.cn-beijing.maas.aliyuncs.com`. */
	val root: String = chatBaseUrl.substringBefore("/compatible-mode").trimEnd('/')

	/** E.g. `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`. */
	val compatibleBase: String =
		if (chatBaseUrl.contains("/compatible-mode/")) chatBaseUrl.trimEnd('/')
		else "$root/compatible-mode/v1"

	suspend fun listModels(): List<RemoteModel> = withContext(Dispatchers.IO) {
		val rootJson = getJson("$compatibleBase/models")
		rootJson["data"]?.jsonArray.orEmpty().mapNotNull { element ->
			val model = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
			val id = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
			RemoteModel(id = id, displayName = id)
		}
	}

	/**
	 * Generate one image and download it immediately.
	 *
	 * The API returns an OSS URL, not bytes, and that URL expires. A caller that
	 * stores the URL has stored a broken image.
	 */
	suspend fun generateImage(
		prompt: String,
		model: String = DEFAULT_IMAGE_MODEL,
		size: String = "1024*1024",
	): QwenImageResult = withContext(Dispatchers.IO) {
		val body = JsonObject(
			mapOf(
				"model" to JsonPrimitive(model),
				"input" to JsonObject(
					mapOf(
						"messages" to kotlinx.serialization.json.JsonArray(
							listOf(
								JsonObject(
									mapOf(
										"role" to JsonPrimitive("user"),
										"content" to kotlinx.serialization.json.JsonArray(
											listOf(JsonObject(mapOf("text" to JsonPrimitive(prompt)))),
										),
									),
								),
							),
						),
					),
				),
				"parameters" to JsonObject(mapOf("size" to JsonPrimitive(size))),
			),
		)
		val json = postJson(
			"$root/api/v1/services/aigc/multimodal-generation/generation",
			body,
		)
		val imageUrl = json["output"]?.jsonObject
			?.get("choices")?.jsonArray
			?.asSequence()
			?.flatMap { it.jsonObject["message"]?.jsonObject?.get("content")?.jsonArray.orEmpty().asSequence() }
			?.mapNotNull { it.jsonObject["image"]?.jsonPrimitive?.contentOrNull }
			?.firstOrNull()
			?: error("Qwen returned no image URL: ${json.toString().take(300)}")
		val bytes = download(imageUrl)
		QwenImageResult(
			bytes = bytes,
			mimeType = if (imageUrl.contains(".webp", ignoreCase = true)) "image/webp" else "image/png",
			model = model,
			usage = json["usage"]?.jsonObject,
		)
	}

	/**
	 * Speech to text through the multimodal-generation endpoint.
	 *
	 * The ASR API does not live under `/audio`; it accepts the audio as a base64
	 * data URI in the same `messages` shape as image generation, and returns the
	 * transcript at the top level.
	 */
	suspend fun transcribe(
		audio: ByteArray,
		format: String = "mp3",
		mimeType: String = "audio/mpeg",
		sampleRate: Int = 24000,
	): QwenTranscription = withContext(Dispatchers.IO) {
		val dataUri = "data:$mimeType;base64," +
			java.util.Base64.getEncoder().encodeToString(audio)
		val body = JsonObject(
			mapOf(
				"model" to JsonPrimitive(DEFAULT_ASR_MODEL),
				"input" to JsonObject(
					mapOf(
						"messages" to kotlinx.serialization.json.JsonArray(
							listOf(
								JsonObject(
									mapOf(
										"role" to JsonPrimitive("user"),
										"content" to kotlinx.serialization.json.JsonArray(
											listOf(
												JsonObject(
													mapOf(
														"type" to JsonPrimitive("input_audio"),
														"input_audio" to JsonObject(
															mapOf("data" to JsonPrimitive(dataUri)),
														),
													),
												),
											),
										),
									),
								),
							),
						),
					),
				),
				"parameters" to JsonObject(
					mapOf(
						"format" to JsonPrimitive(format),
						"sample_rate" to JsonPrimitive(sampleRate.toString()),
					),
				),
			),
		)
		val json = postJson(
			"$root/api/v1/services/aigc/multimodal-generation/generation",
			body,
			extraHeaders = mapOf("X-DashScope-SSE" to "disable"),
		)
		val text = json["text"]?.jsonPrimitive?.contentOrNull
			?: json["output"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
			?: error("Qwen returned no transcript: ${json.toString().take(300)}")
		QwenTranscription(
			text = text,
			durationSeconds = json["usage"]?.jsonObject
				?.get("duration")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
		)
	}

	/** Jev/System One style typed decision. */
	suspend fun decide(		state: JsonElement,
		questions: JsonObject,
		model: String = DEFAULT_DECISION_MODEL,
	): JsonObject = withContext(Dispatchers.IO) {
		postJson(
			"$compatibleBase/systemone",
			JsonObject(
				mapOf(
					"model" to JsonPrimitive(model),
					"state" to state,
					"questions" to questions,
				),
			),
		)
	}

	/** Text to speech; the service returns a JSON URL, so this downloads it. */
	suspend fun synthesize(
		text: String,
		model: String = DEFAULT_TTS_MODEL,
		voice: String = "longanhuan_v3.6",
		format: String = "mp3",
	): QwenAudioResult = withContext(Dispatchers.IO) {
		val body = JsonObject(
			mapOf(
				"model" to JsonPrimitive(model),
				"input" to JsonObject(
					mapOf(
						"text" to JsonPrimitive(text),
						"voice" to JsonPrimitive(voice),
						"format" to JsonPrimitive(format),
						"sample_rate" to JsonPrimitive(24000),
					),
				),
			),
		)
		val request = Request.Builder()
			.url("$root/api/v1/services/audio/tts/SpeechSynthesizer")
			.header("Authorization", "Bearer $apiKey")
			.header("Content-Type", "application/json")
			.post(body.toString().toRequestBody(JSON_MEDIA))
			.build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) fail(response.code, response.body.string())
			val contentType = response.header("Content-Type").orEmpty()
			if (contentType.startsWith("audio/")) {
				return@use QwenAudioResult(response.body.bytes(), contentType.substringBefore(';'))
			}
			val json = JSON.parseToJsonElement(response.body.string()).jsonObject
			val url = json["output"]?.jsonObject
				?.get("audio")?.jsonObject
				?.get("url")?.jsonPrimitive?.contentOrNull
				?: error("Qwen returned no audio URL: ${json.toString().take(300)}")
			QwenAudioResult(download(url), if (format == "mp3") "audio/mpeg" else "audio/$format")
		}
	}

	suspend fun submitVideo(
		prompt: String,
		model: String = DEFAULT_VIDEO_MODEL,
		resolution: String = "720P",
		ratio: String = "16:9",
		duration: Int = 5,
	): String = withContext(Dispatchers.IO) {
		val body = JsonObject(
			mapOf(
				"model" to JsonPrimitive(model),
				"input" to JsonObject(mapOf("prompt" to JsonPrimitive(prompt))),
				"parameters" to JsonObject(
					mapOf(
						"resolution" to JsonPrimitive(resolution),
						"ratio" to JsonPrimitive(ratio),
						"duration" to JsonPrimitive(duration),
					),
				),
			),
		)
		val json = postJson(
			"$root/api/v1/services/aigc/video-generation/video-synthesis",
			body,
			extraHeaders = mapOf("X-DashScope-Async" to "enable"),
		)
		json["output"]?.jsonObject?.get("task_id")?.jsonPrimitive?.contentOrNull
			?: error("Qwen returned no task id: ${json.toString().take(300)}")
	}

	suspend fun videoTask(taskId: String): QwenVideoTask = withContext(Dispatchers.IO) {
		val json = getJson("$root/api/v1/tasks/$taskId")
		val output = json["output"]?.jsonObject
		QwenVideoTask(
			id = taskId,
			status = output?.get("task_status")?.jsonPrimitive?.contentOrNull ?: "UNKNOWN",
			videoUrl = output?.get("video_url")?.jsonPrimitive?.contentOrNull,
			message = output?.get("message")?.jsonPrimitive?.contentOrNull,
		)
	}

	/** Download a finished video URL; signed links expire. */
	suspend fun downloadVideo(url: String): ByteArray = withContext(Dispatchers.IO) {
		download(url)
	}

	private fun getJson(url: String): JsonObject {
		val request = Request.Builder()
			.url(url)
			.header("Authorization", "Bearer $apiKey")
			.header("Accept", "application/json")
			.build()
		return client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) fail(response.code, response.body.string())
			JSON.parseToJsonElement(response.body.string()).jsonObject
		}
	}

	private fun postJson(
		url: String,
		body: JsonObject,
		extraHeaders: Map<String, String> = emptyMap(),
	): JsonObject {
		val builder = Request.Builder()
			.url(url)
			.header("Authorization", "Bearer $apiKey")
			.header("Content-Type", "application/json")
			.post(body.toString().toRequestBody(JSON_MEDIA))
		extraHeaders.forEach { (name, value) -> builder.header(name, value) }
		client.newCall(builder.build()).execute().use { response ->
			if (!response.isSuccessful) fail(response.code, response.body.string())
			return JSON.parseToJsonElement(response.body.string()).jsonObject
		}
	}

	private fun download(url: String): ByteArray {
		// DashScope result links are sometimes issued as http://. The app's
		// network policy is HTTPS-only for public hosts; the OSS endpoint serves
		// the same object over TLS.
		val safeUrl = if (url.startsWith("http://") && url.contains("aliyuncs.com")) {
			url.replaceFirst("http://", "https://")
		} else {
			url
		}
		val request = Request.Builder().url(safeUrl).build()
		return client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("media download HTTP ${response.code}")
			response.body.bytes()
		}
	}

	private fun fail(code: Int, body: String): Nothing {
		Diag.w("qwen", "HTTP $code: ${body.take(300)}")
		error("Qwen HTTP $code: ${body.take(400)}")
	}

	data class QwenImageResult(
		val bytes: ByteArray,
		val mimeType: String,
		val model: String,
		val usage: JsonObject?,
	) {
		override fun equals(other: Any?): Boolean =
			this === other || (other is QwenImageResult &&
				bytes.contentEquals(other.bytes) && mimeType == other.mimeType &&
				model == other.model && usage == other.usage)

		override fun hashCode(): Int =
			listOf(bytes.contentHashCode(), mimeType, model, usage).hashCode()
	}

	data class QwenAudioResult(val bytes: ByteArray, val mimeType: String) {
		override fun equals(other: Any?): Boolean =
			this === other || (other is QwenAudioResult &&
				bytes.contentEquals(other.bytes) && mimeType == other.mimeType)

		override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
	}

	data class QwenVideoTask(
		val id: String,
		val status: String,
		val videoUrl: String? = null,
		val message: String? = null,
	)

	data class QwenTranscription(
		val text: String,
		val durationSeconds: Double? = null,
	)

	companion object {
		const val DEFAULT_IMAGE_MODEL = "wan2.7-image"
		const val DEFAULT_VIDEO_MODEL = "wan3.0-video"
		const val DEFAULT_DECISION_MODEL = "decision-model-preview"
		const val DEFAULT_TTS_MODEL = "qwen-audio-3.0-tts-plus"
		const val DEFAULT_ASR_MODEL = "qwen-audio-3.0-asr-flash"

		private val JSON = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
		}
		private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
	}
}
