package com.inhuman.ui.chat

/**
 * One user message and everything that answered it, up to the next user message.
 *
 * The transcript is a flat list so LazyColumn can key and recycle rows; a turn
 * is a presentation grouping over that list. It exists because per-message
 * usage repeated after every tool round: a five-round turn showed five
 * `tok/s · out · in · cache` lines and five wire buttons, which is a dashboard
 * in the middle of a conversation. The numbers belong once, at the end.
 *
 * ## Why this holds indices rather than rows
 *
 * It used to hold `messages: List<ChatMessageUi>` and
 * `assistantMessages: List<ChatMessageUi>`, both rebuilt on every publish -- and
 * a publish happens up to every 40 ms while a reply streams. Two list copies per
 * turn per tick is pure garbage: the rows are already in the transcript, in
 * order, and a turn is a contiguous run of them.
 *
 * Storing the range is also what makes memoisation *sound* later: a summary that
 * points at indices stays true for a changed transcript as long as the turn
 * boundaries did not move, which a summary holding copies would not.
 */
internal data class TurnSummary(
	/**
	 * The assistant message the footer is attached to.
	 *
	 * Deliberately the last assistant message, not the literal last row: a
	 * turn can end with a tool result when it was interrupted, and a footer on
	 * a tool result would be both misplaced and invisible (tool rows return
	 * before the footer is composed).
	 */
	val endId: String,
	/** Index of the turn's first row in the transcript, inclusive. */
	val start: Int,
	/** Index of the turn's last row in the transcript, inclusive. */
	val end: Int,
	val usage: UsageUi?,
	/**
	 * True once the turn has finished producing.
	 *
	 * The last assistant message of an in-flight turn looks exactly like the
	 * last assistant message of a finished one, so this cannot be inferred from
	 * the id alone. A turn is complete when its final assistant is no longer
	 * streaming **and** nothing (a tool result, a placeholder) follows it.
	 */
	val complete: Boolean,
) {
	/**
	 * The rows of this turn, cut from the transcript the range was measured on.
	 *
	 * Copied rather than returned as a `subList` view: the caller hands this to
	 * a dialog that outlives the recomposition, and a view into a list that a
	 * stream is replacing is a moving target.
	 */
	fun messagesIn(transcript: List<ChatMessageUi>): List<ChatMessageUi> =
		transcript.subList(start, (end + 1).coerceAtMost(transcript.size)).toList()
}

/**
 * Group [messages] into turns and attach the footer data to each end id.
 *
 * A leading assistant block (a compacted history can begin mid-turn) is treated
 * as one turn, so a reloaded transcript does not lose its last usage line.
 */
internal fun turnSummariesOf(messages: List<ChatMessageUi>): Map<String, TurnSummary> {
	val out = LinkedHashMap<String, TurnSummary>()
	var start = 0

	fun close(endExclusive: Int) {
		if (endExclusive <= start) return
		turnSummaryFor(messages, start, endExclusive)?.let { out[it.endId] = it }
		start = endExclusive
	}

	messages.forEachIndexed { index, message ->
		// An in-turn insertion is a user message but not a new turn: closing
		// here would draw one turn as two and hang the footer off the insertion.
		if (message.isTurnRoot && index > start) close(index)
	}
	close(messages.size)
	return out
}

/**
 * The footer data for one turn's row range, or null when the range has no
 * assistant row.
 *
 * Extracted so the incremental index (`TranscriptIndex.kt`) computes exactly
 * what a full pass would: the `complete` rule decides whether a footer is drawn
 * at all, and two copies of that arithmetic would drift. A tool result after the
 * final assistant means the turn is still going, which is the case
 * `isStreaming` alone got wrong.
 */
internal fun turnSummaryFor(
	messages: List<ChatMessageUi>,
	start: Int,
	endExclusive: Int,
): TurnSummary? {
	if (endExclusive <= start) return null
	var lastAssistant: ChatMessageUi? = null
	val usages = ArrayList<UsageUi>(2)
	for (index in start until endExclusive) {
		val message = messages[index]
		if (message.fromUser || message.isToolResult) continue
		lastAssistant = message
		message.usage?.let { usages += it }
	}
	val end = lastAssistant ?: return null
	return TurnSummary(
		endId = end.id,
		start = start,
		end = endExclusive - 1,
		usage = aggregateUsage(usages),
		complete = !end.isStreaming &&
			!end.hasUnsettledCalls() &&
			messages[endExclusive - 1].id == end.id,
	)
}

/**
 * Whether an assistant row still has a call that has not settled.
 *
 * A tool call executes after the assistant message itself stops streaming, so
 * `isStreaming` alone declared the turn finished while the call was still
 * running: the footer appeared mid-turn and then vanished when the result row
 * arrived. A call in Streaming, Ready or Running means the turn is still going.
 */
private fun ChatMessageUi.hasUnsettledCalls(): Boolean = toolCalls.any { call ->
	when (call.state) {
		ToolCallUi.State.Done, ToolCallUi.State.Declined, ToolCallUi.State.Failed -> false
		else -> true
	}
}

/**
 * Add up the rounds of one turn.
 *
 * Missing fields stay missing rather than becoming zero: "the provider did not
 * report a cache hit" and "the cache hit was zero" are different statements,
 * and `UsageUi` keeps that distinction. The sums are over the rounds that
 * reported each field, so a provider that omits usage on one round still
 * produces a usable total.
 */
internal fun aggregateUsage(usages: List<UsageUi>): UsageUi? {
	if (usages.isEmpty()) return null

	fun sum(select: (UsageUi) -> Int?): Int? =
		usages.mapNotNull(select).takeIf { it.isNotEmpty() }?.sum()

	val generation = usages.mapNotNull { it.generationMs }.takeIf { it.isNotEmpty() }?.sum()
	val total = UsageUi(
		promptTokens = sum { it.promptTokens },
		completionTokens = sum { it.completionTokens },
		cachedPromptTokens = sum { it.cachedPromptTokens },
		reasoningTokens = sum { it.reasoningTokens },
		// The first token delay belongs to the first request; adding the
		// rounds together would describe a wait that never happened.
		timeToFirstTokenMs = usages.firstNotNullOfOrNull { it.timeToFirstTokenMs },
		generationMs = generation,
	)
	return total.takeIf { it.hasAnything }
}