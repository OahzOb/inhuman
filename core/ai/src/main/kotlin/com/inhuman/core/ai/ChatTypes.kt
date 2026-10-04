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
 * A tool call attached to an assistant message.
 *
 * [argumentsJson] is the **assembled** string, not a fragment: it is only
 * meaningful once every delta has arrived, and it is stored as the provider
 * sent it rather than re-serialised from a parsed object. Re-serialising would
 * change key order and whitespace, and the transcript sent back on the next
 * turn is compared against what the provider produced.
 */
data class ToolCall(
	val id: String,
	val name: String,
	val argumentsJson: String,
)

/**
 * A message as sent to a provider.
 *
 * Text-only for now. Multimodal parts are an addition rather than a redesign:
 * [content] would become a list of parts.
 */
data class ChatMessage(
	val role: Role,
	val content: String,
	/**
	 * Reasoning text produced alongside this message, when the provider
	 * requires it back.
	 *
	 * **Measured against the live API, and the rule is not the one the
	 * documentation implies.** DeepSeek's docs tie this to `tools`, but testing
	 * shows the deciding factor is thinking mode, not tool use:
	 *
	 * | request | result |
	 * | --- | --- |
	 * | thinking on, tools present, reasoning omitted | **HTTP 400** |
	 * | thinking on, tools present, reasoning echoed | 200 |
	 * | thinking off, tools present, reasoning omitted | 200 |
	 * | **thinking on, NO tools, reasoning omitted** | **HTTP 400** |
	 * | thinking on, no tools, reasoning echoed | 200 |
	 *
	 * The error is explicit: *"The `reasoning_content` in the thinking mode
	 * must be passed back to the API."* So the requirement follows the previous
	 * turn's thinking state, not the presence of tools -- which means a
	 * transcript that drops reasoning works right up until a thinking turn is
	 * replayed, and then fails with a 400 that points at the wrong thing.
	 *
	 * The consequence for storage: reasoning must be persisted with the message
	 * whenever the turn used thinking, and included when that turn is sent
	 * again. Sending it when thinking is off is harmless (also verified).
	 */
	val reasoning: String? = null,
	/** Tool calls this assistant turn requested. */
	val toolCalls: List<ToolCall> = emptyList(),
	/**
	 * For a [Role.Tool] message: which call it answers.
	 *
	 * The wire format requires every tool call to be answered, and answering
	 * the wrong one is worse than not answering -- the model sees a result
	 * attached to an operation that did not produce it.
	 */
	val toolCallId: String? = null,
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
