package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.StreamChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Narrow readers for DeepSeek's response JSON.
 *
 * ## Why this is a set of accessors rather than `@Serializable` data classes
 *
 * Typed deserialisation is the better tool when the schema is fixed and known.
 * This one is neither. The provider adds fields, and a `@Serializable` class
 * with `ignoreUnknownKeys = true` handles that -- but it also means a frame
 * that is *almost* the expected shape throws, and one malformed frame in a
 * stream must not take down a response that is otherwise fine.
 *
 * Reading fields by name returns null for anything absent or of the wrong type,
 * so an unexpected frame degrades into "no chunks from this frame" rather than
 * an exception thrown from inside a coroutine that is collecting a socket.
 *
 * The cost is that field names live as strings here instead of as properties.
 * That is acceptable for one provider whose schema is documented and small.
 */
internal object DeepSeekJson {

	val parser = Json {
		ignoreUnknownKeys = true
		isLenient = true
		explicitNulls = false
	}

	fun parseToJsonElement(text: String): JsonElement = parser.parseToJsonElement(text)

	/**
	 * A member function rather than an extension on [JsonElement].
	 *
	 * An extension declared inside the object is only callable with the object
	 * as receiver, which reads as a bug at every call site. Taking the element
	 * as a parameter says the same thing without the surprise.
	 */
	fun asObjectOrNull(element: JsonElement): JsonObject? = element as? JsonObject

	private fun JsonObject.str(key: String): String? =
		this[key]?.jsonPrimitive?.contentOrNull

	private fun JsonObject.int(key: String): Int? =
		this[key]?.jsonPrimitive?.intOrNull

	// --- envelope ------------------------------------------------------------

	fun id(root: JsonObject): String? = root.str("id")

	fun model(root: JsonObject): String? = root.str("model")

	fun firstChoice(root: JsonObject): JsonObject? =
		root["choices"]?.runCatching { jsonArray }?.getOrNull()
			?.firstOrNull()
			?.runCatching { jsonObject }
			?.getOrNull()

	// --- delta ---------------------------------------------------------------

	fun delta(choice: JsonObject): JsonObject? =
		choice["delta"]?.runCatching { jsonObject }?.getOrNull()

	fun contentText(delta: JsonObject): String? = delta.str("content")

	/**
	 * DeepSeek-specific: reasoning is a sibling field of `content`, not a
	 * different message type. Anthropic models the same thing as `thinking`
	 * content blocks and Gemini as `thought: true` parts; normalising all three
	 * into [StreamChunk.ReasoningDelta] is what the sealed chunk type is for.
	 */
	fun reasoningText(delta: JsonObject): String? = delta.str("reasoning_content")

	fun finishReason(choice: JsonObject): String? = choice.str("finish_reason")

	// --- tool calls ----------------------------------------------------------

	/** One tool-call fragment as it appears on the wire. */
	data class ToolCallFragment(
		val index: Int,
		val id: String?,
		val name: String?,
		/** A **fragment** of the arguments JSON, not a complete document. */
		val arguments: String?,
	)

	fun toolCalls(delta: JsonObject): List<ToolCallFragment> {
		val array = delta["tool_calls"]?.runCatching { jsonArray }?.getOrNull() ?: return emptyList()
		return array.mapNotNull { element ->
			val obj = element.runCatching { jsonObject }.getOrNull() ?: return@mapNotNull null
			val fn = obj["function"]?.runCatching { jsonObject }?.getOrNull()
			ToolCallFragment(
				// Absent index means a single call; 0 is the correct default and
				// also what the provider sends in practice.
				index = obj.int("index") ?: 0,
				id = obj.str("id"),
				name = fn?.str("name"),
				arguments = fn?.str("arguments"),
			)
		}
	}

	// --- usage ---------------------------------------------------------------

	fun usage(root: JsonObject): StreamChunk.Usage? {
		val u = root["usage"]?.runCatching { jsonObject }?.getOrNull() ?: return null
		val prompt = u.int("prompt_tokens")
		val completion = u.int("completion_tokens")
		val total = u.int("total_tokens")
		if (prompt == null && completion == null && total == null) return null

		return StreamChunk.Usage(
			promptTokens = prompt,
			completionTokens = completion,
			totalTokens = total,
			// DeepSeek reports cache hits as `prompt_cache_hit_tokens`, which
			// is how a user learns their prefix caching is working -- and it is
			// roughly 50x cheaper per token, so it is worth surfacing.
			cachedPromptTokens = u.int("prompt_cache_hit_tokens"),
			reasoningTokens = u["completion_tokens_details"]
				?.runCatching { jsonObject }?.getOrNull()
				?.int("reasoning_tokens"),
		)
	}
}
