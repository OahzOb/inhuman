package com.inhuman.core.ai.session

import kotlinx.serialization.json.JsonObject

/**
 * What a conversation currently is, folded from its log.
 *
 * ## The one rule this type exists to enforce
 *
 * > Every fact is an event; the state is a fold over events; the watermark is
 * > what makes the fold resumable.
 *
 * That is the reference implementation's model, and it is the reason a
 * conversation survives a crash, a rotation and a process kill: text that has
 * been appended is text that exists, and nothing lives only in a view model.
 * The defects this replaces are on record -- a reply whose last 40 ms never
 * reached the transcript, a rotation mid-stream losing everything received
 * before it -- and both are the same mistake: *keeping the truth in memory and
 * copying it out later.*
 *
 * ## Why folding is incremental rather than a pure function of the whole list
 *
 * A pure `fold(events)` would be simpler and is what [of] is for. But a session
 * log is read on every cold start and grows without bound, so the runtime folds
 * **forward** from a stored watermark and keeps the result. [apply] is that
 * step, and it is deliberately a function of one event plus the current state:
 * no event needs to see its neighbours, which is what makes the watermark
 * meaningful rather than decorative.
 *
 * ## What is folded here, and what is not
 *
 * The measured vocabulary is 43 durable kinds. This folds the ten that carry
 * the conversation -- turn and step boundaries, messages, tool calls and their
 * results -- and ignores the rest by name. Ignoring is the correct default:
 * a log can be written by a newer release, and a projection that threw on an
 * unknown kind would refuse to open a conversation it could otherwise show.
 */
data class SessionProjection(
	/** Sequence of the last event folded, or null before anything was. */
	val watermark: Long? = null,
	/** The turn currently open, if any. */
	val openTurnSeq: Long? = null,
	/** The step currently open, if any. */
	val openStepSeq: Long? = null,
	/** Completed turns, in order. */
	val turns: List<Turn> = emptyList(),
	/** Everything said, in order, across all turns. */
	val messages: List<TranscriptEntry> = emptyList(),
) {

	/** One completed or in-flight turn. */
	data class Turn(
		val startSeq: Long,
		val endSeq: Long?,
		val steps: Int,
	)

	/**
	 * One thing said, by someone.
	 *
	 * Tool calls and their results are entries rather than a separate list
	 * because that is their position in the log, and the order is what a
	 * transcript renders. Keeping them apart and re-merging at display time is
	 * how a tool result ends up under the wrong message.
	 */
	data class TranscriptEntry(
		val seq: Long,
		val kind: Kind,
		val role: String,
		/** For a tool entry: which call this answers. */
		val toolCallId: String? = null,
		val toolName: String? = null,
		val text: String = "",
	) {
		enum class Kind { Message, ToolCall, ToolResult }
	}

	/**
	 * Fold one event in. Returns the same instance for anything it ignores, so
	 * a caller can compare identity rather than contents to know whether
	 * anything changed.
	 */
	fun apply(event: SessionEvent): SessionProjection {
		val seq = event.seq ?: return this

		// Out-of-order or replayed events are dropped rather than folded twice.
		// The watermark is the guard: a log read twice from the start must not
		// produce two turns, and a resumed fold must not re-apply what it
		// already has.
		if (watermark != null && seq <= watermark) return this

		val d = event.data

		// Kinds that are durable but not conversational -- policy, telemetry,
		// vendor bookkeeping. They are *named* rather than matched by prefix: a
		// rule keyed on the `tool` namespace would silently swallow a future
		// tool event that does matter. Recognised and skipped is a different
		// thing from unknown.
		if (event.type in IGNORED) return copy(watermark = seq)

		return when (event.type) {
			"turn/start" -> copy(
				watermark = seq,
				openTurnSeq = seq,
				turns = turns + Turn(startSeq = seq, endSeq = null, steps = 0),
			)

			"turn/end" -> copy(
				watermark = seq,
				openTurnSeq = null,
				turns = turns.map { if (it.startSeq == openTurnSeq) it.copy(endSeq = seq) else it },
			)

			"step/start" -> copy(
				watermark = seq,
				openStepSeq = seq,
				turns = turns.map { if (it.startSeq == openTurnSeq) it.copy(steps = it.steps + 1) else it },
			)

			"step/end" -> copy(watermark = seq, openStepSeq = null)

			"user/message" -> copy(
				watermark = seq,
				messages = messages + TranscriptEntry(
					seq = seq,
					kind = TranscriptEntry.Kind.Message,
					role = "user",
					text = d.string("text") ?: d.string("content").orEmpty(),
				),
			)

			"assistant/message" -> copy(
				watermark = seq,
				messages = messages + TranscriptEntry(
					seq = seq,
					kind = TranscriptEntry.Kind.Message,
					role = "assistant",
					text = d.string("text") ?: d.string("content").orEmpty(),
				),
			)

			"tool/call" -> copy(
				watermark = seq,
				messages = messages + TranscriptEntry(
					seq = seq,
					kind = TranscriptEntry.Kind.ToolCall,
					role = "assistant",
					toolCallId = d.string("id") ?: d.string("toolCallId"),
					toolName = d.string("name") ?: d.string("toolName"),
				),
			)

			"tool/result" -> copy(
				watermark = seq,
				messages = messages + TranscriptEntry(
					seq = seq,
					kind = TranscriptEntry.Kind.ToolResult,
					role = "tool",
					toolCallId = d.string("toolCallId") ?: d.string("id"),
					toolName = d.string("name") ?: d.string("toolName"),
					text = d.string("content") ?: d.string("text").orEmpty(),
				),
			)

			// An unknown kind still advances the watermark. Not advancing would
			// make the next resume re-read it, and re-reading is what a
			// watermark exists to prevent -- so a log from a newer release keeps
			// folding instead of stalling.
			else -> copy(watermark = seq)
		}
	}

	companion object {
		/**
		 * Kinds that are durable but not conversation.
		 *
		 * Named individually rather than matched by prefix, because a rule keyed
		 * on the `tool` namespace would swallow a future tool event that does
		 * matter.
		 */
		val IGNORED: Set<String> = setOf(
			"agent/inbox/spliced",
			"approval/asked",
			"approval/decided",
			"approval/policy",
			"permission/preset",
			"sandbox/mode",
			"workspace/changes",
			"model/selection",
			"agent-preset/selected",
			"session/title",
			"session/end-seed",
			"todo/write",
			"goal/change",
			"command/run",
			"command/done",
			"deliverables/presented",
			"compaction/start",
			"compaction/summary",
			"compaction/end",
			"compaction/prune",
			"subagent/catalog",
			"subagent/descriptor",
			"system/message",
			"developer/message",
			"assistant/chunk",
			"assistant/attempt",
			"request/header",
			"request/context",
			"session-log-deepseek/delivery-accepted",
			"web/deepseek-search-llm-request",
			"session/title-llm-request",
			"tool-workflow/agent-start",
			"tool-workflow/agent-end",
			"tool-workflow/run-start",
			"tool-workflow/run-end",
		)

		/** Fold a whole log, as a cold start with no stored watermark would. */
		fun of(events: List<SessionEvent>): SessionProjection =
			events.fold(SessionProjection()) { acc, e -> acc.apply(e) }
	}
}
