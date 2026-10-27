package com.inhuman.core.ai

import kotlinx.serialization.json.JsonObject

/**
 * One piece of a streaming response.
 *
 * ## Why a sealed type rather than a `String` of accumulated text
 *
 * Providers do not agree on how a reply is shaped, and the differences are not
 * cosmetic:
 *
 * | what | how it arrives |
 * | --- | --- |
 * | visible text | `content` delta (OpenAI), `text_delta` (Anthropic) |
 * | reasoning | a separate `reasoning_content` field (DeepSeek), `thinking` blocks (Anthropic), `thought: true` parts (Gemini) |
 * | tool calls | JSON **fragments** that must be concatenated before they parse |
 *
 * A model that emits reasoning and then text one token at a time, interleaved,
 * cannot be represented as a growing string without losing which part is which.
 * Modelling the stream as a sequence of typed pieces is what lets the UI render
 * a collapsed "thought for 12s" block above the answer, and lets tool-call
 * arguments be buffered until they are valid JSON.
 *
 * ## The Start/Delta/End shape
 *
 * Text, reasoning and tool calls all follow it. `Start` and `End` carry the
 * metadata a renderer needs to open and close a region (an id, a name, a tool
 * name); deltas carry payload. A renderer that only cares about the final
 * string can ignore the boundaries entirely, but one that draws a live cursor
 * or a tool-call card needs to know when a region begins.
 */
sealed interface StreamChunk {

	/** A block of visible answer text is opening. */
	data class TextStart(val id: String? = null) : StreamChunk

	/** More visible text. Concatenate onto the current text region. */
	data class TextDelta(val text: String) : StreamChunk

	data class TextEnd(val id: String? = null) : StreamChunk

	/**
	 * A reasoning block is opening.
	 *
	 * [type] distinguishes provider flavours of "thinking" so the UI can label
	 * it correctly rather than guessing from content.
	 */
	data class ReasoningStart(val id: String? = null, val type: ReasoningType = ReasoningType.Summary) : StreamChunk

	data class ReasoningDelta(val text: String) : StreamChunk

	data class ReasoningEnd(val id: String? = null) : StreamChunk

	/**
	 * An opaque signature for the reasoning block that just ended.
	 *
	 * Anthropic sends one `signature_delta` per thinking block. The engine
	 * attaches it to the assistant message; the provider replays the thinking
	 * block with it on the next request.
	 */
	data class ReasoningSignature(val signature: String) : StreamChunk

	/**
	 * A tool call is opening.
	 *
	 * [index] is the provider's own ordering key, not arrival order. OpenAI
	 * streams parallel tool calls interleaved and identifies each by `index`;
	 * keying on arrival order silently corrupts the arguments of every call
	 * after the first.
	 */
	data class ToolCallStart(
		val index: Int,
		val id: String? = null,
		val name: String? = null,
	) : StreamChunk

	/**
	 * A fragment of a tool call's arguments.
	 *
	 * [inputDelta] is a **string fragment**, not an object, and is deliberately
	 * not parsed here: a partial JSON document is not valid JSON, and parsing
	 * per fragment is the single most common way tool calling is implemented
	 * wrongly. Accumulate, then parse once at [ToolCallEnd].
	 *
	 * [nameDelta] exists because some providers stream the function name in
	 * pieces too.
	 */
	data class ToolCallDelta(
		val index: Int,
		val nameDelta: String? = null,
		val inputDelta: String? = null,
	) : StreamChunk

	/** The tool call's arguments are complete and may now be parsed. */
	data class ToolCallEnd(val index: Int) : StreamChunk

	/**
	 * An opaque signature for the function-call part at [index].
	 *
	 * Gemini returns this alongside `functionCall`; it belongs to that call, not
	 * to the assistant message as a whole, because a turn may contain several
	 * calls with different signatures.
	 */
	data class ToolCallSignature(val index: Int, val signature: String) : StreamChunk

	/** Token accounting, when the provider reports it. */
	data class Usage(
		val promptTokens: Int? = null,
		val completionTokens: Int? = null,
		val totalTokens: Int? = null,
		/** Tokens served from the provider's prompt cache, when reported. */
		val cachedPromptTokens: Int? = null,
		/** Reasoning tokens, when reported separately. */
		val reasoningTokens: Int? = null,
	) : StreamChunk

	/**
	 * The provider says the response is over.
	 *
	 * This is an explicit signal, not an inference from the socket closing.
	 * OpenAI ends with a literal `[DONE]`; most others just stop. Having the
	 * decoder state it means the transport never has to guess, and a truncated
	 * connection can be told apart from a clean finish -- which matters,
	 * because a truncated one should be retried and a clean one should not.
	 */
	data class Finish(
		val reason: FinishReason,
		val responseId: String? = null,
		val model: String? = null,
	) : StreamChunk

	/**
	 * A transport-level or provider-level error.
	 *
	 * Surfaced as a chunk rather than thrown so it lands in the same stream the
	 * UI is already collecting, in order, after whatever text arrived first.
	 */
	data class Failure(val message: String, val cause: Throwable? = null) : StreamChunk

	/** Extra provider payload that has no common representation yet. */
	data class Raw(val data: JsonObject) : StreamChunk
}

/** Which flavour of reasoning a [StreamChunk.ReasoningStart] opens. */
enum class ReasoningType {
	/** A model-generated summary of its thinking; safe to show. */
	Summary,

	/** Raw chain of thought, when a provider exposes it. */
	Raw,
}

/** Why generation stopped. */
enum class FinishReason {
	/** The model produced a complete answer. */
	Stop,

	/** The model wants a tool executed before continuing. */
	ToolCalls,

	/** The output limit was reached; the answer is truncated. */
	Length,

	/** Content was withheld by the provider. */
	ContentFilter,

	/** Cancelled by the user. Synthesised locally, never sent by a provider. */
	Cancelled,

	/** The stream ended without a recognisable finish signal. */
	Unknown,
}
