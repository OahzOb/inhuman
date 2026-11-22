package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.common.diag.Diag
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek's standalone speech tools: ASR and TTS.
 *
 * `POST /v1/tools/asr` takes one audio file and returns its transcript;
 * `POST /v1/tools/tts` takes text and streams WAV back. Both are billed per
 * unit of work (audio second / billed character) on the same DeepSeek account
 * as the conversation, which is why the tools that use them are marked `paid`.
 *
 * ## Why these do not reuse [DeepSeekProvider.shared]
 *
 * That client carries `HeaderTimeoutInterceptor`: it cancels a call that has not
 * received response *headers* within 90 seconds, which is correct for a streamed
 * chat completion and wrong for these endpoints. ASR and TTS upload or generate
 * the whole answer before the metadata arrives; a long recording can legitimately
 * take longer than 90 seconds. A dedicated client with explicit timeouts keeps
 * the chat rule from turning a valid speech call into "no response".
 *
 * ## Why the local checks are not optional
 *
 * Every rejection that can be decided from the bytes (format, size, duration,
 * billed characters) happens before the request. A paid call spent to discover
 * that a file is an MP3 is a failure the user cannot diagnose; an immediate
 * message naming the limit is one they can act on.
 */
class DeepSeekSpeechApi(
	/**
	 * Read per call rather than captured, so a key added after a conversation
	 * was opened is picked up without restarting.
	 */
	private val apiKey: () -> String?,
	private val baseUrl: String = DEFAULT_BASE_URL,
	private val client: OkHttpClient = dedicatedClient(),
) {

	/** Transcribe one WAV or Ogg Opus file. */
	suspend fun transcribe(audio: ByteArray, format: String): DeepSeekTranscription =
		withContext(Dispatchers.IO) {
			val key = apiKey()?.takeIf { it.isNotBlank() }
				?: throw IOException("no DeepSeek API key is configured")

			val normalized = format.lowercase()
			if (normalized !in ACCEPTED_FORMATS) {
				throw IOException(
					"DeepSeek speech-to-text accepts WAV or Ogg Opus audio, not .$format",
				)
			}
			if (audio.isEmpty()) throw IOException("the audio file is empty")
			if (audio.size > MAX_AUDIO_BYTES) {
				throw IOException(
					"the audio is ${audio.size / 1024 / 1024} MiB; " +
						"DeepSeek speech-to-text accepts at most " +
						"${MAX_AUDIO_BYTES / 1024 / 1024} MiB per request",
				)
			}
			// The API's 120-second limit is enforced per file. The recorder
			// already stops at 120 s; a workspace file is the case that needs
			// this check, and a WAV header is enough to decide it.
			if (normalized == "wav") {
				val duration = wavDurationMillis(audio)
					?: throw IOException(
						"the WAV file is malformed and cannot be sent to DeepSeek speech-to-text",
					)
				if (duration > MAX_AUDIO_MILLIS) {
					throw IOException(
						"the audio is ${(duration + 999) / 1000} s; " +
							"DeepSeek speech-to-text accepts at most " +
							"${MAX_AUDIO_MILLIS / 1000} s per request",
					)
				}
			}

			Diag.i("speech", "request api=asr format=$normalized bytes=${audio.size}")
			val body = MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart(
					"file",
					"speech.$normalized",
					audio.toRequestBody(mimeFor(normalized)),
				)
				.build()
			val request = Request.Builder()
				.url("${baseUrl.trimEnd('/')}/v1/tools/asr")
				.header("Authorization", "Bearer $key")
				.post(body)
				.build()

			client.newCall(request).execute().use { response ->
				val text = response.body.string()
				Diag.i(
					"speech",
					"response api=asr HTTP ${response.code} bytes=${text.length}",
				)
				if (!response.isSuccessful) throw failure("transcription", response.code, response.message, text)

				val root = runCatching {
					DeepSeekJson.parseToJsonElement(text).jsonObject
				}.getOrNull()
					?: throw IOException("transcription failed: DeepSeek returned no JSON")
				val transcript = (root["text"] as? JsonPrimitive)?.contentOrNull
					?: throw IOException("transcription failed: DeepSeek returned no text")
				val duration = (root["duration_ms"] as? JsonPrimitive)
					?.contentOrNull?.toLongOrNull()
				Diag.i(
					"speech",
					"transcript chars=${transcript.length} durationMs=${duration ?: "?"}",
				)
				DeepSeekTranscription(text = transcript, durationMillis = duration)
			}
		}

	/** Synthesize one text into a WAV file. */
	suspend fun synthesize(text: String, voiceId: String? = null): DeepSeekAudio = withContext(Dispatchers.IO) {
		val key = apiKey()?.takeIf { it.isNotBlank() }
			?: throw IOException("no DeepSeek API key is configured")

		if (text.isBlank()) throw IOException("the text to speak is empty")
		val billed = billedCharacters(text)
		if (billed > MAX_BILLED_CHARACTERS) {
			throw IOException(
				"the text is $billed billed characters; DeepSeek text-to-speech " +
					"accepts at most $MAX_BILLED_CHARACTERS per request",
			)
		}
		val payload = buildJsonObject {
			put("text", text)
			// WAV rather than PCM: a bare PCM stream has no container for the
			// workspace or the system player to read.
			put("format", "wav")
			voiceId?.takeIf { it.isNotBlank() }?.let { put("voice_id", it) }
		}.toString()
		if (payload.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
			throw IOException(
				"the request is larger than DeepSeek text-to-speech accepts " +
					"(${MAX_REQUEST_BYTES / 1024} KiB)",
			)
		}

		Diag.i("speech", "request api=tts chars=${text.length} billed=$billed")
		val request = Request.Builder()
			.url("${baseUrl.trimEnd('/')}/v1/tools/tts")
			.header("Authorization", "Bearer $key")
			.header("Content-Type", "application/json")
			.post(payload.toRequestBody(JSON))
			.build()

		client.newCall(request).execute().use { response ->
			val contentType = response.header("Content-Type").orEmpty()
			if (!response.isSuccessful) {
				throw failure("speech synthesis", response.code, response.message, response.body.string())
			}
			val bytes = response.body.bytes()
			Diag.i(
				"speech",
				"response api=tts HTTP ${response.code} type=$contentType bytes=${bytes.size}",
			)
			if (!looksLikeWav(bytes)) {
				// A refusal or a proxy error can arrive as HTTP 200 with a JSON
				// body; returning those bytes as "audio" would save a file that
				// no player can open.
				throw IOException(
					"speech synthesis failed: DeepSeek returned " +
						"${contentType.ifBlank { "an unknown content type" }} instead of WAV audio",
				)
			}
			if (!wavDataComplete(bytes)) {
				// The guide is explicit that a response which ends early is a
				// failure, and a truncated WAV would look like a short answer.
				throw IOException(
					"speech synthesis failed: the audio response ended before the WAV was complete",
				)
			}
			// The live endpoint streams its WAV and marks the `data` chunk
			// `0xFFFFFFFF`. Android's extractor (and some desktop players) treat
			// that as a broken file even though the payload is complete, so the
			// unknown length is resolved before the bytes are handed on.
			val normalized = normalizeWav(bytes)
			DeepSeekAudio(bytes = normalized, mimeType = "audio/wav", extension = "wav")
		}
	}

	private fun failure(action: String, code: Int, message: String, body: String): IOException {
		Diag.w("speech", "$action HTTP $code ${message}: ${body.take(200)}")
		return IOException("$action failed: HTTP $code $message — ${body.take(300)}")
	}

	companion object {

		const val DEFAULT_BASE_URL = "https://api.deepseek.com"

		/** The API's upload ceiling for ASR. */
		const val MAX_AUDIO_BYTES = 8 * 1024 * 1024

		/** The API's duration ceiling for one ASR request. */
		const val MAX_AUDIO_MILLIS = 120_000L

		/** A Chinese character counts as two billed characters; see [billedCharacters]. */
		const val MAX_BILLED_CHARACTERS = 10_000

		/** The API's request-body ceiling for TTS. */
		const val MAX_REQUEST_BYTES = 64 * 1024

		/**
		 * The voices the TTS endpoint documents: `stella` (female, default) and
		 * `tide` (male).
		 */
		val VOICE_IDS = listOf("stella", "tide")

		val ACCEPTED_FORMATS = setOf("wav", "opus")

		/**
		 * Give a streamed WAV its real sizes.
		 *
		 * The TTS endpoint returns the audio as it is synthesized and writes
		 * `0xFFFFFFFF` into `data` (and often `RIFF`). The bytes are complete --
		 * [wavDataComplete] accepts the unknown length -- but a player that
		 * trusts the header can reject the file. Patching the two size fields is
		 * a copy of the header, not a re-encode.
		 */
		internal fun normalizeWav(bytes: ByteArray): ByteArray {
			val data = dataChunk(bytes) ?: return bytes
			val declared = data.second
			if (declared >= 0) return bytes
			val body = data.first
			val dataBytes = bytes.size - body
			if (dataBytes <= 0) return bytes
			val normalized = bytes.copyOf()
			writeLeInt(normalized, body - 4, dataBytes)
			writeLeInt(normalized, 4, normalized.size - 8)
			return normalized
		}

		private val JSON = "application/json; charset=utf-8".toMediaType()
		private val WAV = "audio/wav".toMediaType()
		private val OGG = "audio/ogg".toMediaType()

		internal fun mimeFor(format: String) = if (format == "wav") WAV else OGG

		/**
		 * The billed character count for a TTS request.
		 *
		 * DeepSeek's rule is one Chinese character = 2, every other character = 1.
		 * Only the CJK ideographic blocks are treated as Chinese here; the server
		 * remains the authority, and the local count exists to refuse an obviously
		 * over-limit request before it is sent.
		 */
		internal fun billedCharacters(text: String): Int {
			var index = 0
			var total = 0
			while (index < text.length) {
				val codePoint = text.codePointAt(index)
				total += if (isCjkIdeograph(codePoint)) 2 else 1
				index += Character.charCount(codePoint)
			}
			return total
		}

		private fun isCjkIdeograph(codePoint: Int): Boolean =
			codePoint in 0x4E00..0x9FFF ||
				codePoint in 0x3400..0x4DBF ||
				codePoint in 0xF900..0xFAFF ||
				codePoint in 0x20000..0x2A6DF ||
				codePoint in 0x2A700..0x2EBEF ||
				codePoint in 0x30000..0x323AF

		/** `RIFF....WAVE`, the only shape the TTS endpoint returns for `format: wav`. */
		internal fun looksLikeWav(bytes: ByteArray): Boolean =
			bytes.size >= 44 &&
				ascii(bytes, 0, 4) == "RIFF" &&
				ascii(bytes, 8, 4) == "WAVE"

		/**
		 * Whether the `data` chunk inside a WAV is fully present.
		 *
		 * The live TTS endpoint writes `0xFFFFFFFF` as the chunk size, so a
		 * negative size means "until the end of the body" and is accepted. A
		 * positive size larger than the remaining bytes is a truncated response.
		 */
		internal fun wavDataComplete(bytes: ByteArray): Boolean {
			if (!looksLikeWav(bytes)) return false
			val data = dataChunk(bytes) ?: return false
			val declared = data.second
			val available = bytes.size - data.first
			return declared < 0 || declared <= available
		}

		/**
		 * The duration of a PCM WAV from its own header, or null when it is not
		 * a readable WAV.
		 *
		 * Reads `fmt ` for channels/sample rate/bits and `data` for the payload
		 * length. A streamed `data` size of `-1` uses the remaining bytes.
		 */
		internal fun wavDurationMillis(bytes: ByteArray): Long? {
			if (!looksLikeWav(bytes)) return null

			var position = 12
			var channels = 0
			var sampleRate = 0
			var bitsPerSample = 0
			var dataBytes = -1L

			while (position + 8 <= bytes.size) {
				val id = ascii(bytes, position, 4) ?: return null
				val size = leInt(bytes, position + 4)
				val body = position + 8
				when (id) {
					"fmt " -> {
						if (size < 16 || body + 16 > bytes.size) return null
						channels = leShort(bytes, body + 2)
						sampleRate = leInt(bytes, body + 4)
						bitsPerSample = leShort(bytes, body + 14)
					}

					"data" -> {
						val available = (bytes.size - body).toLong()
						dataBytes = if (size < 0) available else minOf(size.toLong(), available)
						break
					}
				}
				if (size < 0) return null
				position = body + size + (size and 1)
			}

			if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || dataBytes <= 0) return null
			val frameBytes = channels * (bitsPerSample / 8)
			if (frameBytes <= 0) return null
			val millis = dataBytes * 1_000.0 / (sampleRate.toDouble() * frameBytes)
			return millis.toLong()
		}

		/** Offset and declared size of the `data` chunk, or null. */
		private fun dataChunk(bytes: ByteArray): Pair<Int, Int>? {
			var position = 12
			while (position + 8 <= bytes.size) {
				val id = ascii(bytes, position, 4) ?: return null
				val size = leInt(bytes, position + 4)
				val body = position + 8
				if (id == "data") return body to size
				if (size < 0) return null
				position = body + size + (size and 1)
			}
			return null
		}

		private fun ascii(bytes: ByteArray, offset: Int, length: Int): String? {
			if (offset < 0 || offset + length > bytes.size) return null
			val chars = CharArray(length) { index ->
				val value = bytes[offset + index].toInt() and 0xff
				if (value !in 0x20..0x7e) return null
				value.toChar()
			}
			return String(chars)
		}

		private fun leShort(bytes: ByteArray, offset: Int): Int {
			if (offset < 0 || offset + 2 > bytes.size) return 0
			return (bytes[offset].toInt() and 0xff) or
				((bytes[offset + 1].toInt() and 0xff) shl 8)
		}

		private fun leInt(bytes: ByteArray, offset: Int): Int {
			if (offset < 0 || offset + 4 > bytes.size) return 0
			return (bytes[offset].toInt() and 0xff) or
				((bytes[offset + 1].toInt() and 0xff) shl 8) or
				((bytes[offset + 2].toInt() and 0xff) shl 16) or
				((bytes[offset + 3].toInt() and 0xff) shl 24)
		}

		private fun writeLeInt(bytes: ByteArray, offset: Int, value: Int) {
			if (offset < 0 || offset + 4 > bytes.size) return
			for (index in 0..3) {
				bytes[offset + index] = ((value ushr (8 * index)) and 0xff).toByte()
			}
		}

		/**
		 * The speech endpoints' client.
		 *
		 * No header-timeout interceptor: see the class comment. The read timeout
		 * covers a full ASR response and a complete TTS synthesis.
		 */
		private fun dedicatedClient(): OkHttpClient = OkHttpClient.Builder()
			.connectTimeout(20, TimeUnit.SECONDS)
			.writeTimeout(180, TimeUnit.SECONDS)
			.readTimeout(300, TimeUnit.SECONDS)
			.build()
	}

	data class DeepSeekTranscription(
		val text: String,
		val durationMillis: Long? = null,
	)

	data class DeepSeekAudio(
		val bytes: ByteArray,
		val mimeType: String,
		val extension: String,
	) {
		override fun equals(other: Any?): Boolean =
			this === other || (other is DeepSeekAudio &&
				bytes.contentEquals(other.bytes) &&
				mimeType == other.mimeType &&
				extension == other.extension)

		override fun hashCode(): Int =
			listOf(bytes.contentHashCode(), mimeType, extension).hashCode()
	}
}
