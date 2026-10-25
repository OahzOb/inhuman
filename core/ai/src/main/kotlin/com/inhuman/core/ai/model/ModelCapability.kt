package com.inhuman.core.ai.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What a model is for, as far as the app can tell.
 *
 * ## Why this exists
 *
 * A provider's `/models` list is a list of everything the account can call, not
 * a list of chat models. Qwen's includes `wan2.7-image`; selecting it as the
 * conversation's model produced `HTTP 400 InvalidParameter: Input should be
 * 'user': input.messages.0.role ...` in the transcript, which names nothing the
 * user did and offers nothing to do about it.
 *
 * So a model carries a small set of these, inferred from its id and overridable
 * by the user. The inference is a guess -- ids are the only signal available
 * before a request is made -- and the override is not a nicety: it is how a
 * guess that is wrong gets fixed without a release.
 */
enum class ModelCapability(val key: String) {
	/** Answers messages. Everything else is a reason not to offer it as one. */
	Text("text"),
	Image("image"),
	Video("video"),
	Audio("audio"),

	/** Can be relied on to call tools. Tagged by hand; no id says this. */
	Tools("tools"),
	;

	companion object {
		fun fromKey(key: String): ModelCapability? = entries.firstOrNull { it.key == key }
	}
}

object ModelCapabilities {

	/**
	 * Words that mean "this is not a chat model".
	 *
	 * Deliberately few and boring. A long list becomes a liability the first
	 * time a provider ships `foo-instruct-vision-preview` and the app decides
	 * it is an image generator; every miss is recoverable by tagging the model,
	 * and a wrong hit is visible in the picker before anything is sent.
	 */
	private val imageWords = listOf(
		"image", "dall-e", "dalle", "imagen", "flux", "seedream", "stable-diffusion",
		"sdxl", "cogview", "midjourney", "wanx", "wan2", "qwen-image",
	)
	private val videoWords = listOf(
		"video", "sora", "veo", "kling", "hailuo", "seedance", "wan-video",
	)
	private val audioWords = listOf(
		"tts", "text-to-speech", "speech", "audio", "voice", "whisper", "transcri",
	)

	/**
	 * What [modelId] probably is.
	 *
	 * A model that matches none of the words is text, because that is the only
	 * thing a picker with no better information can safely assume: hiding a
	 * chat model is worse than showing a tag on one that turns out to be odd.
	 */
	fun infer(modelId: String): Set<ModelCapability> {
		val id = modelId.lowercase()
		return when {
			videoWords.any { it in id } -> setOf(ModelCapability.Video)
			audioWords.any { it in id } -> setOf(ModelCapability.Audio)
			imageWords.any { it in id } -> setOf(ModelCapability.Image)
			else -> setOf(ModelCapability.Text)
		}
	}

	/**
	 * The tags that apply, user first.
	 *
	 * A stored tag set replaces the guess entirely rather than adding to it: an
	 * empty set is a real answer ("I tagged this and left everything off"), and
	 * merging would make it impossible to untag an image model.
	 */
	fun effective(modelId: String, tagged: Set<ModelCapability>?): Set<ModelCapability> =
		tagged ?: infer(modelId)

	/** Whether a model can be offered as the conversation's chat model. */
	fun isChatModel(capabilities: Set<ModelCapability>): Boolean =
		ModelCapability.Text in capabilities
}

/**
 * The stored form of the per-model tags: `{"model-id":["image"]}`.
 *
 * Kept with the capability type rather than in the database layer so the picker
 * and the migration read the same format. Unknown keys are dropped on read, so
 * a tag removed from the enum cannot make an old row unreadable. A model whose
 * tag set would be empty is not stored at all: absent means "not tagged".
 */
object ModelTagCodec {

	private val json = Json { ignoreUnknownKeys = true }

	fun decode(raw: String): Map<String, Set<ModelCapability>> {
		if (raw.isBlank()) return emptyMap()
		return runCatching {
			val obj = json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
			obj.mapNotNull { (model, value) ->
				val array = value as? JsonArray ?: return@mapNotNull null
				val caps = array.mapNotNull { element ->
					(element as? JsonPrimitive)?.contentOrNull?.let(ModelCapability::fromKey)
				}.toSet()
				if (caps.isEmpty()) null else model to caps
			}.toMap()
		}.getOrDefault(emptyMap())
	}

	fun encode(tags: Map<String, Set<ModelCapability>>): String {
		val stored = tags.filterValues { it.isNotEmpty() }
		if (stored.isEmpty()) return ""
		return buildJsonObject {
			stored.toSortedMap().forEach { (model, caps) ->
				put(
					model,
					buildJsonArray { caps.map { it.key }.sorted().forEach { add(it) } },
				)
			}
		}.toString()
	}
}