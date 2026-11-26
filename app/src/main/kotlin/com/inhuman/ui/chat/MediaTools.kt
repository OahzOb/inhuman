package com.inhuman.ui.chat

import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.capability.ProviderCapability
import com.inhuman.core.ai.capability.ProviderCapabilities
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
 * The paid media tools.
 *
 * Image, video and decision speak the Qwen Token Plan API families. Speech and
 * transcription can also be served by DeepSeek's standalone ASR/TTS endpoints,
 * which is why their backend resolves to a `SpeechBackend` (in `:core:ai`)
 * rather than to [MediaBackend].
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

/**
 * A resolved Qwen media backend: one endpoint, one key, one model.
 *
 * Only image, video and decision resolve to this. Speech and transcription use
 * a `SpeechBackend` because DeepSeek serves them from a different wire API.
 */
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
	/**
	 * The selected provider's profile kind, so the dialog can say that DeepSeek's
	 * speech tools take no model. Cosmetic only; the resolver reads the kind from
	 * the profile again.
	 */
	val providerKind: String = "",
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

/**
 * What one provider profile contributes to media availability.
 *
 * The resolver needs exactly three facts and nothing else: whether the kind
 * offers the capability, whether a key is stored, and (for the assistant's own
 * provider) the base URL. Passing them in makes the check testable without a
 * database or a Keystore, and keeps the "is this usable?" question separate
 * from "build the client" which stays in `ChatViewModel`.
 */
data class MediaProviderFacts(
	val kind: String,
	val hasKey: Boolean,
	val baseUrl: String?,
)

/** The catalogue names one media capability serves. */
val MediaCapability.toolNames: List<String>
	get() = when (this) {
		MediaCapability.Image -> listOf("generate_image")
		MediaCapability.Video -> listOf("generate_video", "video_status")
		MediaCapability.Speech -> listOf("speak")
		MediaCapability.Transcription -> listOf("transcribe")
		MediaCapability.Decision -> listOf("decide")
	}

/**
 * Whether this capability's tools write a file and so need a workspace.
 *
 * Decision classifies text and returns text; the other four save audio, video
 * or an image, which is also why `ChatViewModel.buildTools` registers them only
 * when a workspace exists. The pickers must apply the same rule, or they offer
 * a row the request will not carry.
 */
val MediaCapability.needsWorkspace: Boolean
	get() = this != MediaCapability.Decision

/**
 * Whether a capability's backend can actually be resolved, without building a
 * client.
 *
 * The conditions mirror `ChatViewModel.mediaBackend`/`speechBackend` exactly --
 * a mode that cannot produce both a key and an endpoint is not configured --
 * and exist as one function because the pickers must ask the same question the
 * builder answers. Two copies of this test would drift, and the failure mode is
 * silent in both directions: a tool offered and unusable, or a working tool
 * hidden.
 */
suspend fun MediaToolConfig.isConfigured(
	capability: MediaCapability,
	assistant: MediaProviderFacts?,
	profile: suspend (String) -> MediaProviderFacts?,
): Boolean = when (mode) {
	MediaMode.Assistant ->
		assistant != null &&
			ProviderCapabilities.supports(assistant.kind, capability.providerCapability) &&
			assistant.hasKey &&
			assistant.baseUrl != null

	MediaMode.Provider -> profile(providerId)?.let { facts ->
		ProviderCapabilities.supports(facts.kind, capability.providerCapability) && facts.hasKey
	} == true

	MediaMode.Direct -> apiKey.isNotBlank() && baseUrl.startsWith("https://")
}

/**
 * The media tool names a picker may offer.
 *
 * @param assistantFacts the provider the assistant is bound to, per capability
 *   (Settings asks the question for "any provider", which is a different fact
 *   for each capability); null when no such provider resolves.
 * @param profileFacts the explicitly chosen media provider profile, if any.
 * @param workspaceAvailable false hides the four tools that save a file, which
 *   is the same condition `buildTools` uses before registering them.
 */
suspend fun mediaConfiguredToolNames(
	configFor: (MediaCapability) -> MediaToolConfig,
	workspaceAvailable: Boolean,
	assistantFacts: (MediaCapability) -> MediaProviderFacts?,
	profileFacts: suspend (String) -> MediaProviderFacts?,
): Set<String> {
	val names = linkedSetOf<String>()
	for (capability in MediaCapability.entries) {
		if (capability.needsWorkspace && !workspaceAvailable) continue
		val config = configFor(capability)
		if (config.isConfigured(capability, assistantFacts(capability), profileFacts)) {
			names += capability.toolNames
		}
	}
	return names
}