package com.inhuman.core.ai.speech

import com.inhuman.core.ai.provider.deepseek.DeepSeekSpeechApi
import com.inhuman.core.ai.provider.qwen.QwenTokenPlanClient
import java.io.IOException

/**
 * One backend that can turn audio into text and text into audio.
 *
 * The app has two: Qwen Token Plan's multimodal endpoints (the original one)
 * and DeepSeek's standalone `/v1/tools/asr` + `/v1/tools/tts`. The speech tool
 * and the composer's voice button care only about this interface, so which
 * service bills the call is a settings decision, not a second implementation of
 * the tool.
 *
 * Implementations must not throw for an ordinary refusal that a person could
 * act on (too long, unsupported format, text over the billed-character limit);
 * they throw [IOException] with that sentence, and the tool layer turns it into
 * a tool error. A malformed argument is a programming error and may throw
 * [IllegalArgumentException].
 */
interface SpeechBackend {

	/** For diagnostics and settings summaries; never a credential. */
	val id: String

	/** Whether this backend accepts one workspace audio extension. */
	fun acceptsFormat(format: String): Boolean

	/** Transcribe audio; [format] is a lowercase extension (`wav`, `opus`, ...). */
	suspend fun transcribe(audio: ByteArray, format: String): String

	/** Synthesize text into workspace audio bytes. */
	suspend fun synthesize(text: String): SpeechAudio
}

/** One synthesized audio result, with the extension the workspace file needs. */
data class SpeechAudio(
	val bytes: ByteArray,
	val mimeType: String,
	val extension: String,
) {
	override fun equals(other: Any?): Boolean =
		this === other || (other is SpeechAudio &&
			bytes.contentEquals(other.bytes) &&
			mimeType == other.mimeType &&
			extension == other.extension)

	override fun hashCode(): Int =
		listOf(bytes.contentHashCode(), mimeType, extension).hashCode()
}

/** Qwen Token Plan's ASR/TTS, unchanged from the pre-DeepSeek backend. */
class QwenSpeechBackend(
	private val client: QwenTokenPlanClient,
	private val model: String,
) : SpeechBackend {

	override val id: String = "qwen"

	override fun acceptsFormat(format: String): Boolean = format.lowercase() in FORMATS

	override suspend fun transcribe(audio: ByteArray, format: String): String {
		val normalized = format.lowercase()
		if (normalized !in FORMATS) {
			throw IOException("the Qwen transcription backend does not accept .$format")
		}
		val mime = when (normalized) {
			"mp3" -> "audio/mpeg"
			"wav" -> "audio/wav"
			else -> "audio/opus"
		}
		return client.transcribe(
			audio = audio,
			format = normalized,
			mimeType = mime,
			model = model,
		).text
	}

	override suspend fun synthesize(text: String): SpeechAudio {
		val result = client.synthesize(text, model = model)
		return SpeechAudio(
			bytes = result.bytes,
			mimeType = result.mimeType,
			extension = extensionFor(result.mimeType),
		)
	}

	private companion object {
		val FORMATS = setOf("mp3", "wav", "opus")

		fun extensionFor(mimeType: String): String = when {
			mimeType.contains("wav", ignoreCase = true) -> "wav"
			mimeType.contains("mpeg", ignoreCase = true) ||
				mimeType.contains("mp3", ignoreCase = true) -> "mp3"
			else -> "bin"
		}
	}
}

/**
 * DeepSeek's ASR/TTS.
 *
 * The model field is deliberately absent: both endpoints take no model id. The
 * media settings still store one, because the Qwen backend needs it, but this
 * backend ignores it.
 */
class DeepSeekSpeechBackend(
	private val api: DeepSeekSpeechApi,
) : SpeechBackend {

	override val id: String = "deepseek"

	override fun acceptsFormat(format: String): Boolean =
		format.lowercase() in DeepSeekSpeechApi.ACCEPTED_FORMATS

	override suspend fun transcribe(audio: ByteArray, format: String): String =
		api.transcribe(audio, format.lowercase()).text

	override suspend fun synthesize(text: String): SpeechAudio {
		val audio = api.synthesize(text)
		return SpeechAudio(
			bytes = audio.bytes,
			mimeType = audio.mimeType,
			extension = audio.extension,
		)
	}
}
