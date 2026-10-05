package com.inhuman.ui.chat

import com.inhuman.core.data.Branches

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
	val error: String? = null,
	/** True once the provider has been configured with a usable key. */
	val configured: Boolean = false,
	/** True when replies come from the built-in scripted source. */
	val demoMode: Boolean = false,
	/** A tool call waiting on the user's consent. */
	val pendingApproval: PendingApproval? = null,
	/**
	 * The model chosen for this conversation, or null to follow the assistant.
	 *
	 * Kept as the *choice* rather than the resolved id so the picker can show
	 * which option is selected: "the assistant's default" and an explicit
	 * selection of that same model send identically but are different states.
	 */
	val model: String? = null,
	/**
	 * The model actually in use: [model] if set, the assistant's otherwise.
	 *
	 * Separate from [model] because null there is a meaningful choice, and
	 * anything looking up capabilities needs the resolved id. Looking one up by
	 * a null id finds nothing, which is how the effort control came to report
	 * that a model with three levels had none.
	 */
	val effectiveModel: String? = null,
	/** Human-facing label for [model], since the id and the name differ. */
	val modelLabel: String = "default model",
	/** Reasoning effort for this conversation, as the provider spells it. */
	val reasoningEffort: String? = null,
	val thinking: Boolean = false,
	/**
	 * Images chosen but not yet sent, as workspace-relative paths.
	 *
	 * Staged into the workspace immediately rather than held as bytes: the file
	 * has to live there anyway for the transcript to refer to it, and bytes kept
	 * in the view model would be copied again by every recomposition that
	 * touched the list.
	 */
	val pendingAttachments: List<PendingAttachment> = emptyList(),
	/**
	 * Whether the model in use accepts images, from `input_modalities`.
	 *
	 * The app has to check this itself. Sending an image to a text-only model
	 * does **not** fail: verified against the live API it returns 200, and the
	 * model then answers about a literal `[Unsupported Image]` placeholder as
	 * though it had looked at something. There is no error to catch, so the
	 * guard has to be here.
	 */
	val modelSupportsVision: Boolean = false,
	/**
	 * How full the model's context window is.
	 *
	 * Computed by the view model rather than derived here, because the figure
	 * that fills the gauge is the last request's `promptTokens` and only the
	 * view model knows where in the transcript that was recorded -- the ring
	 * and the panel have to agree, and a screen computing its own would be a
	 * second implementation of the same arithmetic.
	 */
	val context: ContextUsage = ContextUsage(null, null),
) {

	/**
	 * Whether the current turn changed the workspace.
	 *
	 * The answer to whether the reply can be regenerated. It cannot be read off
	 * the *last reply*: a mutation is recorded on the message that **asked** for
	 * the tool, and the last reply is usually the model summarising afterwards --
	 * so the obvious check gives the wrong answer for the common case.
	 *
	 * ## Why this is derived rather than stored
	 *
	 * It used to be a stored field, assigned in `reload()` and set to `true` in
	 * `executeCalls`. `send()` never cleared it, so after one mutating turn
	 * every later reply in the session claimed it could not be regenerated --
	 * and reopening the conversation did not help, because the chat view model
	 * is keyed and activity-scoped, so the same instance came back without
	 * `init` or `reload()` running again. Only a process restart or a branch
	 * switch recalculated it.
	 *
	 * The bug was not a missing assignment. It was two expressions of one fact:
	 * a stored flag, and the predicate that had to be kept in step with it by
	 * hand. Reading it from the transcript makes them one expression, so there
	 * is nothing left to forget to clear.
	 */
	val currentTurnMutated: Boolean
		get() {
			val lastUser = messages.indexOfLast { it.fromUser }
			if (lastUser < 0) return false
			return messages.drop(lastUser).any { it.mutated }
		}
}

/** An image staged for the next message. */
data class PendingAttachment(
	val relativePath: String,
	val mimeType: String,
	val sizeBytes: Long,
) {
	val displayName: String get() = relativePath.substringAfterLast('/')
}

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
	/**
	 * The deltas this reply is still arriving in, or null once it is complete.
	 *
	 * Not part of the transcript and never persisted: it exists only so the
	 * Markdown renderer can parse incrementally while text is arriving. A
	 * message loaded from the database has none and never will, which is the
	 * right answer rather than a gap -- a complete document is parsed once.
	 *
	 * A `Channel` in a data class is unusual and deliberate: this field
	 * identifies a live stream rather than holding a value, equality on it is
	 * never used, and the *same instance* is published for the whole turn.
	 */
	val deltas: kotlinx.coroutines.channels.Channel<String>? = null,
	val finishReason: String? = null,
	val usage: UsageUi? = null,
	val error: String? = null,
	/** Tool calls this turn requested. */
	val toolCalls: List<ToolCallUi> = emptyList(),
	/**
	 * Where this message sits among the alternatives at its point in the
	 * conversation, or null when there is only one.
	 *
	 * Resolved once for the whole transcript when it loads, rather than asked
	 * of the database per row: a row querying its own siblings would be one
	 * query per message per recomposition.
	 */
	val branches: Branches? = null,
	/**
	 * Whether this turn changed the workspace.
	 *
	 * Regenerating re-runs the tools, so a reply that wrote or deleted files
	 * cannot be offered for regeneration -- a second attempt would write or
	 * delete a second time. Reads are free to replay.
	 */
	val mutated: Boolean = false,
	/**
	 * The message this one is an alternative to, when it has alternatives.
	 *
	 * Carried alongside [branches] because switching needs *both* ids and the
	 * one being switched to is not on the active path, so it cannot be looked up
	 * in the transcript.
	 */
	val branchParentId: String? = null,
	/**
	 * Images on this message, as workspace paths.
	 *
	 * Paths rather than bytes: this is the transcript, read on every screen
	 * open, and holding decoded images here would make that cost grow with the
	 * conversation. The composer shows a name and size; nothing needs the pixels
	 * except the request.
	 */
	val attachments: List<PendingAttachment> = emptyList(),
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
	 *
	 * **Null below a minimum window, and that floor is the point.** A one-word
	 * answer arrives whole: the first and last deltas are milliseconds apart, so
	 * dividing by that interval produced "22000.0 tok/s" on a live run. The
	 * arithmetic was right and the number was meaningless, which is worse than
	 * showing nothing -- a figure that implausible teaches the reader to distrust
	 * the whole panel, including the parts that are sound.
	 */
	val tokensPerSecond: Double?
		get() {
			val completion = completionTokens ?: return null
			val ms = generationMs ?: return null
			if (ms < MIN_SPEED_WINDOW_MS || completion <= 0) return null
			return completion * 1000.0 / ms
		}

	/** Whether the speed figure was withheld for being too small a sample. */
	val speedWithheldForShortReply: Boolean
		get() {
			// Held in locals so the null checks smart-cast: the `!!` this
			// replaces was on a receiver the compiler had already narrowed,
			// which is a warning rather than a safeguard.
			val completion = completionTokens ?: return false
			val ms = generationMs ?: return false
			return completion > 0 && ms < MIN_SPEED_WINDOW_MS
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

	companion object {
		/**
		 * Shortest generating window worth dividing by.
		 *
		 * Below roughly a tenth of a second the interval between the first and
		 * last delta is dominated by scheduling and network jitter rather than
		 * by the model, so the quotient says more about the device than about
		 * the provider. 100ms still reports speed for any answer of a sentence
		 * or more.
		 */
		const val MIN_SPEED_WINDOW_MS = 100L
	}
}
