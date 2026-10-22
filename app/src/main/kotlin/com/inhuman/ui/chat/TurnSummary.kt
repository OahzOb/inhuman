package com.inhuman.ui.chat

/**
 * One user message and everything that answered it, up to the next user message.
 *
 * The transcript is a flat list so LazyColumn can key and recycle rows; a turn
 * is a presentation grouping over that list. It exists because per-message
 * usage repeated after every tool round: a five-round turn showed five
 * `tok/s · out · in · cache` lines and five wire buttons, which is a dashboard
 * in the middle of a conversation. The numbers belong once, at the end.
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
	/** Every row of the turn, for the combined wire inspector. */
	val messages: List<ChatMessageUi>,
	/** Assistant rows only, in order; the usage sum is built from these. */
	val assistantMessages: List<ChatMessageUi>,
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
)

/**
 * Group [messages] into turns and attach the footer data to each end id.
 *
 * A leading assistant block (a compacted history can begin mid-turn) is treated
 * as one turn, so a reloaded transcript does not lose its last usage line.
 */
internal fun turnSummariesOf(messages: List<ChatMessageUi>): Map<String, TurnSummary> {
	val out = LinkedHashMap<String, TurnSummary>()
	var current = mutableListOf<ChatMessageUi>()

	fun close() {
		if (current.isEmpty()) return
		val assistants = current.filter { !it.fromUser && !it.isToolResult }
		val end = assistants.lastOrNull()
		if (end != null) {
			out[end.id] = TurnSummary(
				endId = end.id,
				messages = current.toList(),
				assistantMessages = assistants,
				usage = aggregateUsage(assistants.mapNotNull { it.usage }),
				complete = !end.isStreaming &&
					!end.hasUnsettledCalls() &&
					current.lastOrNull()?.id == end.id,
			)
		}
		current = mutableListOf()
	}

	messages.forEach { message ->
		if (message.fromUser && current.isNotEmpty()) close()
		current += message
	}
	close()
	return out
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
