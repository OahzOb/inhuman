package com.inhuman.ui.chat

import com.inhuman.core.data.Branches
import com.inhuman.core.data.SnapshotStats
import com.inhuman.core.data.SnapshotTurn
import com.inhuman.core.data.WorkspaceChange
import com.inhuman.core.data.db.SessionMode
import com.inhuman.core.engine.roleplay.RoleplayScene
import com.inhuman.ui.ssh.SshHostSummary

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
 * A point in the last turn from which the model may be asked again.
 *
 * [afterMessageId] is the last retained message; everything below it is
 * discarded and its workspace effects are restored. [checkpointId] is `start`
 * for the user's message, or the last tool result of a completed group.
 */
data class RegenerationPoint(
	val checkpointId: String,
	val label: String,
	val afterMessageId: String,
	val checkpointAvailable: Boolean = false,
	val blockedReason: String? = null,
	/** Whether this point starts at the user's message rather than a tool group. */
	val isUserMessage: Boolean = false,
)

/**
 * The exact provider body from the last request, kept for the wire inspector.
 *
 * This is in-memory rather than persisted: it is a view of what was sent, not a
 * second transcript, and a long image-bearing body would duplicate the
 * conversation on disk.
 */
data class WireSnapshot(
	val requestJson: String,
	val model: String,
	val capturedAt: Long,
	val preview: Boolean = false,
)

/**
 * The action the composer can offer beside an error.
 *
 * The raw provider message stays in [ChatUiState.error]; this only decides
 * which recovery is meaningful. `Retry` reuses the existing transcript, so it
 * cannot duplicate the user's message or re-run tools.
 */
enum class ChatErrorAction {
	Retry,
	CompactAndRetry,
	OpenProviderSettings,
}

/**
 * Classify an error string from the provider or the turn loop.
 *
 * This is deliberately string-based: the provider layer already flattens an
 * HTTP status and its body into one message, and changing that wire contract to
 * carry a typed error would be a larger change than the UI needs. The tests in
 * `ChatErrorActionTest` pin the common shapes.
 */
fun chatErrorAction(message: String?): ChatErrorAction? {
	val text = message?.lowercase() ?: return null
	val contextOverflow = listOf(
		"context length",
		"context window",
		"maximum context",
		"max context",
		"too many tokens",
		"token limit",
		"request too large",
		"prompt is too long",
	).any(text::contains)
	if (contextOverflow) return ChatErrorAction.CompactAndRetry

	val configuration = listOf(
		"api key",
		"invalid key",
		"unauthorized",
		"http 401",
		"http 403",
		"insufficient balance",
		"quota",
		"billing",
		"choose a model",
		"no usable model",
	).any(text::contains)
	if (configuration) return ChatErrorAction.OpenProviderSettings

	val transient = listOf(
		"http 429",
		"http 500",
		"http 502",
		"http 503",
		"http 504",
		"timeout",
		"timed out",
		"no response",
		"network",
		"connect",
		"stream",
		"socket",
		"unavailable",
		"overloaded",
	).any(text::contains)
	if (transient) return ChatErrorAction.Retry
	return null
}

/**
 * The state of one conversation view.
 *
 * [isStreaming] is separate from the parts rather than inferred from them: an
 * empty streaming reply and a finished empty one look identical in content but
 * need different affordances (a cursor versus a retry).
 */
data class ChatUiState(
	val title: String = "",
	val providerName: String = "DeepSeek",
	/** The conversation's current provider profile; switching is explicit. */
	val providerId: String = "deepseek",
	val workspaceName: String = "",
	val workspaceId: String? = null,
	val workspaceIsPermanent: Boolean = false,
	val workspaceConversationCount: Int = 1,
	val messages: List<ChatMessageUi> = emptyList(),
	val input: String = "",
	/**
	 * Messages written while a turn was running, waiting for delivery.
	 *
	 * Memory only, like [input]: the composer draft has never been persisted,
	 * and a queue that outlived the process would need a reconciliation with the
	 * turn it was written into. Cleared as each entry is sent or inserted.
	 */
	val queued: List<QueuedMessage> = emptyList(),
	val isStreaming: Boolean = false,
	val error: String? = null,
	/** True once the provider has been configured with a usable key. */
	val configured: Boolean = false,
	/** True when replies come from the built-in scripted source. */
	val demoMode: Boolean = false,
	/** A tool call waiting on the user's consent. */
	val pendingApproval: PendingApproval? = null,
	/** A first SSH call waiting on the conversation-level acknowledgement. */
	val pendingSshConsent: SshConsent? = null,
	/** Whether this conversation may run ssh_exec without asking again. */
	val sshAllowed: Boolean = false,
	/** A first URL download waiting on the conversation-level acknowledgement. */
	val pendingDownloadConsent: DownloadConsent? = null,
	/** Whether this conversation may download URLs without asking again. */
	val downloadAllowed: Boolean = false,
	/** Whether the global download capability is on; off hides every affordance. */
	val downloadEnabled: Boolean = false,
	/**
	 * The machines saved in Settings, for the host sheet to list.
	 *
	 * Names and addresses only -- the credentials never leave the secret store.
	 */
	val sshHosts: List<SshHostSummary> = emptyList(),
	/**
	 * The ids of [sshHosts] this conversation may use.
	 *
	 * Empty means none: with no host selected the SSH tools are not registered
	 * at all, so the model cannot reach a machine the user did not check.
	 */
	val sshHostIds: List<String> = emptyList(),
	/** Whether the global SSH capability is on; off hides every SSH affordance. */
	val sshToolEnabled: Boolean = false,
	/** Whether the follow-up tool is available to pick at all. */
	val suggestionsEnabled: Boolean = false,
	/**
	 * Whether the anonymous Bilibili read tools are switched on globally.
	 *
	 * Off hides them from every picker and from the request; on still requires
	 * the assistant's explicit selection. See [com.inhuman.core.ai.tools.ToolCatalog.Entry.gatedByBilibili].
	 */
	val bilibiliEnabled: Boolean = false,
	/**
	 * Whether an optional read-only Bilibili account is linked.
	 *
	 * Only `bili_transcript` depends on it; the anonymous four work either way.
	 * Off hides that one row (the login is configuration, and a row that would
	 * always fail is worse than an absent one).
	 */
	val bilibiliLoggedIn: Boolean = false,
	/**
	 * The conversation's stored tool selection.
	 *
	 * `null` is the historical "all", which excludes remote tools; the sheet
	 * expands it to the non-remote names for display.
	 */
	val conversationTools: List<String>? = null,
	/** The assistant's selection, the ceiling the conversation sheet may offer. */
	val agentTools: List<String>? = null,
	/**
	 * Media tool names whose backend resolves for this conversation, or null
	 * before the first resolution.
	 *
	 * The conversation tool sheet offers only these: a capability whose
	 * backend is not configured is absent rather than a checkbox that does
	 * nothing (`docs/13-product.md`). Null means "not resolved yet", which the
	 * sheet treats as "do not filter", so the frame before the first publish
	 * cannot hide a tool that works.
	 */
	val configuredMediaTools: Set<String>? = null,
	/** The conversation row has loaded; the tool sheet is disabled before that. */
	val toolsKnown: Boolean = false,
	/**
	 * Whether this conversation has pinned its `AGENTS.md` block.
	 *
	 * Once true, the conversation's system message is fixed: editing the file
	 * no longer changes this conversation, only new conversations or the
	 * explicit reload. False until the first request that would use it.
	 */
	val workspaceInstructionsPinned: Boolean = false,
	/** The pinned block's character count, for the chat menu. */
	val workspaceInstructionsChars: Int = 0,
	/** Whether the last user turn can be rewound safely. */
	val rollbackAvailable: Boolean = false,
	/** Why [rollbackAvailable] is false, when it is. */
	val rollbackBlockedReason: String? = null,
	/** Turns and bytes currently stored for this conversation. */
	val snapshotStats: SnapshotStats = SnapshotStats(0, 0),
	/** The retained turns, newest first; the chat menu lists them. */
	val snapshotTurns: List<SnapshotTurn> = emptyList(),
	/** Configured number of turns to keep, current turn included. */
	val snapshotRetention: Int = 3,
	/** How many tool rounds one turn may run before it stops. */
	val toolRoundLimit: Int = 8,
	/** How many individual tool calls one turn may execute. */
	val toolCallLimit: Int = 32,
	/** Places in the last turn a tool-using regeneration may start. */
	val regenerationPoints: List<RegenerationPoint> = emptyList(),
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
	val modelLabel: String = "",
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
	/** Video generation is async; these fields keep its progress across dialog dismissal. */
	val videoStatus: String? = null,
	val videoPath: String? = null,
	val videoError: String? = null,
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
	/** An active request projection, or null when the full transcript is sent. */
	val compaction: ContextCompactionUi? = null,
	/** True while the summarizer call is running; the composer is disabled. */
	val isCompacting: Boolean = false,
	/**
	 * How this conversation maintains its request projection.
	 *
	 * [SessionMode.CLASSIC] means the user triggers compaction; the gauge,
	 * compact button and summary banner are part of that mode. [SessionMode.CONTINUOUS]
	 * means the app maintains a rolling brief, the context UI is absent from the
	 * normal path, and the session-memory sheet is the audit surface.
	 */
	val sessionMode: String = SessionMode.CLASSIC,
	/** True while the app is folding older turns into the session brief. */
	val isMaintaining: Boolean = false,
	/** True while a send is waiting for a forced fold to make room. */
	val maintenancePreparing: Boolean = false,
	/**
	 * Whether this conversation's assistant has a role-play character card.
	 *
	 * Only decides whether the chat menu offers scene settings; the card
	 * itself is read by the request builder, never by the screen.
	 */
	val roleplayCharacter: Boolean = false,
	/**
	 * The conversation's scene, or null when it has none.
	 *
	 * Parsed for the scene dialog. The request builder reads the row through
	 * the same codec, so the dialog and the wire cannot disagree about what a
	 * scene says.
	 */
	val roleplayScene: RoleplayScene? = null,
	/**
	 * Whether the scene's frozen persona may be reloaded from the live assistant.
	 *
	 * False when the assistant has no decodable card: a scene created from a
	 * workspace folder or from an inline card has its persona only in
	 * `rpSnapshotJson`, and "reload" would overwrite it with an empty card. The
	 * button is hidden rather than left to empty a story.
	 */
	val roleplayReloadable: Boolean = false,
	/**
	 * A conversation the scene-proposal card just created, to open once.
	 *
	 * One-shot rather than a navigation call inside the view model: the screen
	 * owns navigation, and a value that stays set would re-open the scene on
	 * every recomposition. The screen consumes it and clears it.
	 */
	val openSceneConversationId: String? = null,
) {
	/** What the error banner should offer, derived from [error]. */
	val errorAction: ChatErrorAction? get() = chatErrorAction(error)
}

/**
 * The derived summary that shortens requests for one conversation.
 *
 * The transcript on screen is still complete. [omittedMessages] and
 * [omittedTurns] describe only what the provider will not receive.
 */
data class ContextCompactionUi(
	val throughMessageId: String,
	val summary: String,
	val omittedTurns: Int,
	val omittedMessages: Int,
)

/**
 * What the session-memory sheet shows, read on demand.
 *
 * Nullable coverage fields are deliberate: a summary written before schema v23
 * recorded no coverage, and showing 0 would claim the brief covers nothing.
 * [runs] and the token totals come from `maintenance_runs`, which is a cost
 * ledger rather than part of the projection -- the brief can be rebuilt, the
 * money spent cannot be un-spent.
 */
data class SessionMemoryUi(
	val sessionMode: String,
	val coverageTurns: Int?,
	val coverageMessages: Int?,
	val brief: String?,
	val briefKind: String,
	val briefUpdatedAt: Long?,
	val briefTruncated: Boolean,
	val briefTokens: Int?,
	val runs: Int,
	val inputTokens: Long,
	val outputTokens: Long,
	val lastRunAt: Long?,
)

/** An image staged for the next message. */
data class PendingAttachment(
	val relativePath: String,
	val mimeType: String,
	val sizeBytes: Long,
) {
	val displayName: String get() = relativePath.substringAfterLast('/')
}

/**
 * One image the picker or the camera returned, resolved before its bytes are
 * read.
 *
 * [open] is a stream factory rather than a stream: the preprocessor opens the
 * source three times (bounds, EXIF and pixels), and the camera path can only
 * delete its cache original once staging has ended.
 */
class PickedImage(
	val displayName: String,
	val mimeType: String,
	val open: () -> java.io.InputStream,
)

data class ChatMessageUi(
	val id: String,
	/** The message this one answers. Kept so a branch can be extended later. */
	val parentId: String? = null,
	val fromUser: Boolean,
	/**
	 * True for a user row sent while its turn was already running.
	 *
	 * The row stays a user message on the wire and in the transcript, but turn
	 * grouping, the jump list and Rewind must not treat it as a new turn; see
	 * `MessageEntity.injected`. It is loaded from the database so a reopened
	 * conversation keeps the same shape ([ChatMessageUi.isTurnRoot]).
	 */
	val injected: Boolean = false,
	/**
	 * When the row was stored, from `messages.createdAt`.
	 *
	 * Carried into the UI state because `buildWireHistory` needs it: the time
	 * hint on a user message is derived from the gap to the previous user
	 * message, and the transcript is the only ordered list of rows the request
	 * builder has. The transcript is rebuilt from the database on every load,
	 * so this is the stored value, not a display approximation.
	 */
	val createdAt: Long = 0L,
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
	/** Anthropic's opaque signature for [reasoningText], when one was stored. */
	val reasoningSignature: String? = null,
	/** OpenAI Responses input items to replay, as opaque JSON. */
	val providerState: String? = null,
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
	/**
	 * "Reconnecting (2/5)" while the current round is being retried.
	 *
	 * Transient and never persisted: it describes a request in flight, not the
	 * answer. The row is still `isStreaming`, and its parts were cleared when
	 * the failed attempt was discarded, so this is what tells the reader the
	 * spinner is doing something other than waiting for a first token.
	 */
	val retryNotice: String? = null,
	/** Tool calls this turn requested. */
	val toolCalls: List<ToolCallUi> = emptyList(),
	/**
	 * True once answer text arrives *after* a tool call has started.
	 *
	 * A model often says "I'll search for that" before calling a tool. That
	 * preamble is text, but it is not the answer, and treating it as one closed
	 * the tool section while the calls were still running. This separates the
	 * two: text before the first call does not count, text after it does.
	 *
	 * Not persisted and false on a loaded message, which is harmless: the
	 * live-section rule only applies while a turn is streaming.
	 */
	val answerAfterCalls: Boolean = false,
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
	 * A record, not a regeneration gate: the final-answer retry keeps the tool
	 * definitions for cache matching but suppresses calls, so it cannot write
	 * or delete again. This remains the fact a future replay-with-tools path or
	 * a warning would need.
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
	/** Files this turn changed, written after the turn; empty for reads. */
	val changedFiles: List<WorkspaceChange> = emptyList(),
	/**
	 * Follow-ups the model offered with this answer.
	 *
	 * Deliberately transient: tapping one fills the composer, and a reopened
	 * transcript has no composer context worth restoring them for. They are
	 * stripped from the stored text, so they never reach a later request.
	 */
	val suggestions: List<String> = emptyList(),
	/**
	 * A scene the workshop assistant proposed, rendered as a card on the tool
	 * result.
	 *
	 * Transient like [suggestions]: the proposal is parsed from the tool result
	 * that is already in the transcript, and a reopened conversation shows the
	 * plain result line instead. Nothing here is created until the user taps.
	 */
	val sceneProposal: SceneProposal? = null,
	/** True while the tap's create request is running; disables the card. */
	val sceneProposalBusy: Boolean = false,
	/** Why the last create attempt failed; shown on the card so retry is possible. */
	val sceneProposalError: String? = null,
)

/**
 * Whether this row starts a turn rather than continuing one.
 *
 * One definition for a question five different groupings ask -- the footer
 * summary, the turn jump list, Rewind's start point, the compaction boundary and
 * the context projection. A user row sent while the turn was running is a real
 * user message but not a new turn; if any one grouping disagreed about that, a
 * single insertion would show up as a turn with no start checkpoint.
 */
internal val ChatMessageUi.isTurnRoot: Boolean
	get() = fromUser && !injected

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

/**
 * Which working section of the live turn is allowed to stay open.
 *
 * ## Why this is a separate function
 *
 * The decision is about *position in the turn*, not about how a section looks:
 * only the newest section before the answer should be open, and it should close
 * as soon as anything newer exists. Expressing that inline in three composables
 * would make the three copies drift, and it would put the rule somewhere a JVM
 * test cannot reach it. The screen maps the answer back to two booleans.
 *
 * `isStreaming` matters: an old transcript that happens to end in reasoning is
 * not "live" and should open collapsed. Only a turn in progress has a current
 * section.
 */
internal enum class LiveAssistantSection { None, Reasoning, Tools }

internal fun liveAssistantSection(
	isStreaming: Boolean,
	isLatestAssistant: Boolean,
	hasReasoning: Boolean,
	hasText: Boolean,
	toolCallCount: Int,
	answerAfterCalls: Boolean,
): LiveAssistantSection = when {
	!isStreaming || !isLatestAssistant -> LiveAssistantSection.None
	// A preamble before the calls is not the answer: keep the calls open while
	// they are the thing being produced.
	toolCallCount > 0 && !answerAfterCalls -> LiveAssistantSection.Tools
	// The answer has started; working-out is no longer what the reader is
	// waiting for.
	hasText -> LiveAssistantSection.None
	// Tool calls come after reasoning in the layout, so they supersede it.
	toolCallCount > 0 -> LiveAssistantSection.Tools
	hasReasoning -> LiveAssistantSection.Reasoning
	else -> LiveAssistantSection.None
}
