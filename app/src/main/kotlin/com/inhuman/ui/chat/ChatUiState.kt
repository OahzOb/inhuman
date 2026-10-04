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
)

data class ChatMessageUi(
	val id: String,
	/** The message this one answers. Kept so a branch can be extended later. */
	val parentId: String? = null,
	val fromUser: Boolean,
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
)

data class UsageUi(
	val promptTokens: Int?,
	val completionTokens: Int?,
	val cachedPromptTokens: Int?,
	val reasoningTokens: Int?,
)
