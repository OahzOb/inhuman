package com.inhuman.ui.chat

/**
 * A boundary at which the request can be shortened.
 *
 * [boundaryIndex] is an index into the visible transcript; everything up to and
 * including it can be replaced by a summary. The messages themselves are never
 * deleted. The boundary is always the end of a user turn, so a tool call and its
 * results cannot be split by the projection.
 */
internal data class CompactionPlan(
	val boundaryIndex: Int,
	val throughMessageId: String,
	val omittedTurns: Int,
	val omittedMessages: Int,
	val omittedChars: Long,
	val keptTurns: Int,
)

/**
 * Choose the oldest complete turns that can be summarized.
 *
 * Keeping two turns is the default: enough for the question above the current
 * one to still make sense, while removing the bulk of a long tool-heavy history.
 * The caller may ask for one when the window is especially tight.
 *
 * Returns null when there is nothing old enough to remove. It does not decide
 * whether compaction is necessary; that is the user's explicit decision.
 */
internal fun planContextCompaction(
	messages: List<ChatMessageUi>,
	keepTurns: Int = 2,
): CompactionPlan? {
	if (messages.isEmpty()) return null
	val keep = keepTurns.coerceAtLeast(1)
	val userIndices = messages.indices.filter { messages[it].fromUser }
	if (userIndices.size <= keep) return null

	// The first turn that stays verbatim. The boundary is the message just
	// before it, which is the last message of the previous (dropped) turn.
	val firstKept = userIndices[userIndices.size - keep]
	val boundaryIndex = firstKept - 1
	if (boundaryIndex < 0) return null

	val boundary = messages[boundaryIndex]
	val omitted = messages.subList(0, boundaryIndex + 1)
	val omittedTurns = omitted.count { it.fromUser }
	if (omittedTurns == 0) return null

	val chars = omitted.sumOf { message ->
		message.plainText.length.toLong() +
			message.reasoningText.length +
			message.toolCalls.sumOf { it.argumentsJson.length }
	}
	return CompactionPlan(
		boundaryIndex = boundaryIndex,
		throughMessageId = boundary.id,
		omittedTurns = omittedTurns,
		omittedMessages = omitted.size,
		omittedChars = chars,
		keptTurns = keep,
	)
}

/**
 * The text handed to the summarizer.
 *
 * Bounded on purpose: a compaction request that itself exceeds the window is
 * not a fix. User text gets the largest budget because constraints and
 * intentions live there; tool output is the first thing truncated.
 */
internal fun compactionTranscript(
	messages: List<ChatMessageUi>,
	maxChars: Int = COMPACTION_INPUT_LIMIT,
): String {
	if (messages.isEmpty()) return ""
	val perMessage = (maxChars / messages.size).coerceIn(1_000, 8_000)
	val out = StringBuilder()
	for (message in messages) {
		val role = when {
			message.fromUser -> "USER"
			message.isToolResult -> "TOOL ${message.toolName ?: ""}".trim()
			else -> "ASSISTANT"
		}
		val body = when {
			message.plainText.isNotBlank() -> message.plainText
			message.toolCalls.isNotEmpty() ->
				message.toolCalls.joinToString("\n") { call ->
					"called ${call.displayName} ${call.argumentsJson}"
				}
			else -> "(no text)"
		}.take(perMessage)
		val segment = "$role:\n$body\n\n"
		if (out.length + segment.length > maxChars) break
		out.append(segment)
	}
	return out.toString().trim()
}

/**
 * A deterministic summary for demo mode.
 *
 * Demo mode must keep the compaction path reachable without a model call. This
 * is intentionally a poor summarizer: it lists the questions and files rather
 * than pretending to understand them.
 */
internal fun demoCompactionSummary(messages: List<ChatMessageUi>): String {
	val userMessages = messages.filter { it.fromUser }
	val files = messages
		.flatMap { it.changedFiles }
		.map { it.path }
		.distinct()
	val questions = userMessages
		.joinToString("\n") { "- ${it.plainText.lineSequence().firstOrNull().orEmpty().take(180)}" }
		.ifBlank { "- (no user text)" }
	return buildString {
		append("### Earlier context\n\n")
		append("Summarized deterministically for demo mode: ")
		append("${userMessages.size} earlier user turn(s), ${messages.size} messages.\n\n")
		append("Questions:\n")
		append(questions)
		if (files.isNotEmpty()) {
			append("\n\nFiles touched:\n")
			files.take(20).forEach { append("- $it\n") }
		}
	}
}

internal const val COMPACTION_INPUT_LIMIT = 24_000
