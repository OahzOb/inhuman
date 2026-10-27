package com.inhuman.ui.chat

import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.capability.ProviderCapability
import com.inhuman.core.ai.model.ModelCapability
import com.inhuman.core.ai.provider.qwen.QwenTokenPlanClient
import com.inhuman.core.data.secret.SecureStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The paid media tools, which all speak the Qwen Token Plan API families.
 *
 * These are separate from [com.inhuman.core.ai.tools.ToolCatalog] on purpose:
 * the catalogue is the tool the model calls, this is where the user points it.
 * Web search/fetch have the same split (`WebToolProviders` plus a key in
 * Settings); the media tools had been tied to the assistant's chat provider,
 * which meant changing the chat model silently changed who billed the image.
 */
enum class MediaCapability(
	val key: String,
	val defaultModel: String,
	/** The provider kind must offer this capability to be selectable. */
	val providerCapability: ProviderCapability,
	/** Which tag a model must carry to be offered for this tool. */
	val modelCapability: ModelCapability,
) {
	Image(
		key = "image",
		defaultModel = QwenTokenPlanClient.DEFAULT_IMAGE_MODEL,
		providerCapability = ProviderCapability.Image,
		modelCapability = ModelCapability.Image,
	),
	Video(
		key = "video",
		defaultModel = QwenTokenPlanClient.DEFAULT_VIDEO_MODEL,
		providerCapability = ProviderCapability.Video,
		modelCapability = ModelCapability.Video,
	),
	Speech(
		key = "speech",
		defaultModel = QwenTokenPlanClient.DEFAULT_TTS_MODEL,
		providerCapability = ProviderCapability.Speech,
		modelCapability = ModelCapability.Audio,
	),
	Transcription(
		key = "transcribe",
		defaultModel = QwenTokenPlanClient.DEFAULT_ASR_MODEL,
		providerCapability = ProviderCapability.Speech,
		modelCapability = ModelCapability.Audio,
	),
	Decision(
		key = "decision",
		defaultModel = QwenTokenPlanClient.DEFAULT_DECISION_MODEL,
		providerCapability = ProviderCapability.Decision,
		modelCapability = ModelCapability.Text,
	),
}

/** Where one media tool gets its key, endpoint and model. */
enum class MediaMode {
	/** The assistant's own provider, as before this setting existed. */
	Assistant,

	/** A provider profile configured under 01 / Models. */
	Provider,

	/** A key and endpoint entered in this section, independent of any profile. */
	Direct,
}

/**
 * One media tool's backend.
 *
 * [model] is blank for "the tool's default", which keeps existing installs and
 * the common case from writing a versioned model id into storage. [apiKey] is
 * only used by [MediaMode.Direct]; the whole object is stored through
 * `SecureStore`, so the key is Keystore-wrapped at rest like every other secret.
 */
data class MediaToolConfig(
	val mode: MediaMode = MediaMode.Assistant,
	val providerId: String = "",
	val model: String = "",
	val baseUrl: String = "",
	val apiKey: String = "",
) {
	fun encode(): String = buildJsonObject {
		put("mode", mode.name.lowercase())
		if (providerId.isNotBlank()) put("providerId", providerId)
		if (model.isNotBlank()) put("model", model)
		if (baseUrl.isNotBlank()) put("baseUrl", baseUrl)
		if (apiKey.isNotBlank()) put("apiKey", apiKey)
	}.toString()

	/** The model the tool should send, or its built-in default. */
	fun modelOrDefault(capability: MediaCapability): String =
		model.ifBlank { capability.defaultModel }

	val isDefault: Boolean
		get() = mode == MediaMode.Assistant && model.isBlank() &&
			providerId.isBlank() && baseUrl.isBlank() && apiKey.isBlank()

	companion object {
		fun decode(raw: String?): MediaToolConfig {
			if (raw.isNullOrBlank()) return MediaToolConfig()
			val obj: JsonObject = runCatching {
				Json.parseToJsonElement(raw).jsonObject
			}.getOrNull() ?: return MediaToolConfig()
			val mode = MediaMode.entries.firstOrNull {
				it.name.equals(obj.string("mode"), ignoreCase = true)
			} ?: MediaMode.Assistant
			return MediaToolConfig(
				mode = mode,
				providerId = obj.string("providerId"),
				model = obj.string("model"),
				baseUrl = obj.string("baseUrl"),
				apiKey = obj.string("apiKey"),
			)
		}

		private fun JsonObject.string(name: String): String = runCatching {
			this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
		}.getOrDefault("")
	}
}

object MediaToolStore {

	/** One secret-store entry per capability; the value is [MediaToolConfig] JSON. */
	fun key(capability: MediaCapability): String = "media.${capability.key}"

	fun load(secure: SecureStore, capability: MediaCapability): MediaToolConfig =
		MediaToolConfig.decode(secure.get(key(capability)))

	fun save(secure: SecureStore, capability: MediaCapability, config: MediaToolConfig) {
		if (config.isDefault) secure.remove(key(capability))
		else secure.put(key(capability), config.encode())
	}
}

/** A resolved media backend: one endpoint, one key, one model. */
data class MediaBackend(
	val client: QwenTokenPlanClient,
	val model: String,
)

/**
 * The editable state of one media-tool dialog.
 *
 * Kept out of [MediaToolConfig] because the dialog also holds an in-flight model
 * fetch and a key field that is intentionally empty until the user types a
 * replacement; persisting that draft would mean a half-edited key.
 */
data class MediaToolDraft(
	val capability: MediaCapability,
	val mode: MediaMode,
	val providerId: String = "",
	val model: String = "",
	val baseUrl: String = "",
	val apiKeyInput: String = "",
	val hasKey: Boolean = false,
	val models: List<RemoteModel> = emptyList(),
	val loadingModels: Boolean = false,
	val modelError: String? = null,
	val showModelPicker: Boolean = false,
	val error: String? = null,
)