package com.inhuman.core.ai

/**
 * Who said a message.
 *
 * Named to match the wire format rather than the UI, because the conversion
 * happens in one place and a mismatch there is invisible. Note that the
 * tool-result role differs per provider: OpenAI uses `tool`, Anthropic folds it
 * into a `user` message containing `tool_result` blocks. That difference is the
 * adapter's problem, not this enum's -- it carries the semantic role and each
 * adapter maps it.
 */
enum class Role {
	System,
	User,
	Assistant,
	Tool,
}

/**
 * A message as sent to a provider.
 *
 * Text-only for now. Multimodal parts, reasoning replay and tool-call payloads
 * are additions rather than a redesign: [content] would become a list of parts
 * and [toolCalls] gains a sibling for results.
 */
data class ChatMessage(
	val role: Role,
	val content: String,
	/**
	 * Reasoning text produced alongside this message, when the provider
	 * requires it back.
	 *
	 * DeepSeek's documentation is explicit: **when a request carries `tools`,
	 * every previous assistant turn's `reasoning_content` must be echoed back,
	 * including turns with no tool call, or the API answers HTTP 400.** When no
	 * tools are present it is ignored. That asymmetry is why this is stored on
	 * the message rather than dropped after display -- throwing it away works
	 * right up until tools are enabled.
	 */
	val reasoning: String? = null,
)

/**
 * A request for a completion.
 *
 * [tools] is a JSON-Schema list already in the provider's shape. Building it
 * belongs to the tool layer, not here: this module deliberately knows nothing
 * about what tools exist.
 */
data class ChatRequest(
	val model: String,
	val messages: List<ChatMessage>,
	val tools: List<ToolSpec> = emptyList(),
	val temperature: Double? = null,
	val maxTokens: Int? = null,
	/**
	 * Whether to ask the model to think before answering.
	 *
	 * DeepSeek has thinking on by default, which is not what a chat app wants
	 * for "hi". Left nullable so the provider default applies when unset.
	 */
	val thinking: Boolean? = null,
	/** Reasoning effort hint, when the model advertises levels. */
	val reasoningEffort: String? = null,
	/**
	 * Stable identifier for the end user, passed through to the provider.
	 *
	 * DeepSeek uses it for KV-cache isolation, scheduling and content-safety
	 * scoping. Giving each conversation its own value keeps one conversation's
	 * cache from being shared with another.
	 */
	val userId: String? = null,
)

/**
 * A tool the model may call.
 *
 * [parameters] is a raw JSON Schema object rather than a Kotlin type. Modelling
 * all of JSON Schema in Kotlin is a large job that buys nothing here, and the
 * provider passes the object through unexamined -- so a raw map is both less
 * code and more capable.
 */
data class ToolSpec(
	val name: String,
	val description: String,
	val parameters: Map<String, Any?>,
)
