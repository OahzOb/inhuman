package com.inhuman.ui.chat

/**
 * One rendered region of an assistant reply.
 *
 * Mirrors the parts of `StreamChunk` that have been received, with the text
 * already accumulated. The separation between reasoning and answer is kept all
 * the way to the UI because it is the difference between a collapsed "thought
 * for a while" line and a wall of text the user did not ask to read.
 */
data class AssistantPart(
	/** Stable key, so a LazyColumn does not rebuild parts that did not change. */
	val key: String,
	val kind: Kind,
	val text: String,
) {
	enum class Kind { Reasoning, Text, ToolCall }
}

/**
 * The state of one conversation view.
 *
 * [isStreaming] is separate from the parts rather than inferred from them: an
 * empty streaming reply and a finished empty one look identical in content but
 * need different affordances (a cursor versus a retry).
 */
data class ChatUiState(
	val title: String = "New conversation",
	val messages: List<ChatMessageUi> = emptyList(),
	val input: String = "",
	val isStreaming: Boolean = false,
	val model: String? = null,
	val error: String? = null,
	/** True once the provider has been configured with a usable key. */
	val configured: Boolean = false,
	/** True when replies come from the built-in scripted source. */
	val demoMode: Boolean = false,
	/** A tool call waiting on the user's consent. */
	val pendingApproval: PendingApproval? = null,
)

data class ChatMessageUi(
	val id: String,
	/** The message this one answers. Kept so a branch can be extended later. */
	val parentId: String? = null,
	val fromUser: Boolean,
	/** True for a tool result, which is rendered as a card rather than speech. */
	val isToolResult: Boolean = false,
	/** For a tool result: the call it answers. */
	val toolResultFor: String? = null,
	val toolName: String? = null,
	val parts: List<AssistantPart> = emptyList(),
	val plainText: String = "",
	/**
	 * Reasoning, retained rather than discarded after rendering.
	 *
	 * Replaying a thinking turn without it is rejected by the API with a 400,
	 * so this is request state, not just something that was shown once.
	 */
	val reasoningText: String = "",
	val isStreaming: Boolean = false,
	val finishReason: String? = null,
	val usage: UsageUi? = null,
	val error: String? = null,
	/** Tool calls this turn requested. */
	val toolCalls: List<ToolCallUi> = emptyList(),
)

/**
 * What one turn cost.
 *
 * Kept as reported rather than derived where possible: a zero and an absent
 * field mean different things (a provider that reports no cache hit versus one
 * that does not report caching at all), so nullable fields are preserved rather
 * than defaulted.
 */
data class UsageUi(
	val promptTokens: Int? = null,
	val completionTokens: Int? = null,
	val cachedPromptTokens: Int? = null,
	val reasoningTokens: Int? = null,
	/**
	 * Milliseconds from the request being sent to the first token arriving.
	 *
	 * Separate from the total because it is the number a user actually feels:
	 * a reasoning model can spend many seconds here before a single character
	 * appears, and without it a slow start looks like a hang.
	 */
	val timeToFirstTokenMs: Long? = null,
	/** Milliseconds from first token to the stream finishing. */
	val generationMs: Long? = null,
) {
	/**
	 * Output tokens per second, over the generating period only.
	 *
	 * Measured from the first token rather than from the request, because
	 * including the wait for the first one folds two unrelated things together
	 * -- how fast the model writes, and how long it thought first -- and
	 * produces a number that moves for reasons the reader cannot see.
	 */
	val tokensPerSecond: Double?
		get() {
			val completion = completionTokens ?: return null
			val ms = generationMs ?: return null
			if (ms <= 0 || completion <= 0) return null
			return completion * 1000.0 / ms
		}

	/**
	 * Share of the prompt served from cache, as a fraction.
	 *
	 * DeepSeek bills a cache hit at roughly a fiftieth of a miss, so this is the
	 * number that explains a surprising bill -- and, because it is measured per
	 * request, it is also how someone learns their prefix is or is not being
	 * reused across turns.
	 */
	val cacheHitRatio: Double?
		get() {
			val cached = cachedPromptTokens ?: return null
			val prompt = promptTokens ?: return null
			if (prompt <= 0) return null
			return (cached.toDouble() / prompt).coerceIn(0.0, 1.0)
		}

	val hasAnything: Boolean
		get() = promptTokens != null || completionTokens != null ||
			cachedPromptTokens != null || reasoningTokens != null ||
			timeToFirstTokenMs != null
}
