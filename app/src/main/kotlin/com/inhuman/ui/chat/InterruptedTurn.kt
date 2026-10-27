package com.inhuman.ui.chat

/**
 * Whether the active transcript ends in the middle of a turn.
 *
 * A turn is finished only by an assistant message that is not asking for a
 * tool: the engine persists every model round as an assistant row, and an
 * interrupted process leaves the last row one of these shapes:
 *
 * - a user message, when the process died before the first round was saved;
 * - a tool result, when it died between rounds (the call ran, the next model
 *   answer did not);
 * - an assistant message with `tool_calls`, when it died before the calls had
 *   results. Reload normally repairs this tail with `[interrupted]` results,
 *   which turns it into the previous case; the check still covers a repair that
 *   could not be written.
 *
 * An assistant message carrying [ChatMessageUi.error] is **not** interrupted:
 * a provider failure or a user stop already has a Retry control, and offering
 * Continue beside it would run a second kind of retry from the same screen.
 *
 * The function is pure and tested without a device: the states are exactly the
 * ones a killed process can leave, and getting one wrong either hides the way
 * to resume or offers it for a turn that is already answerable.
 */
internal fun isInterruptedTurn(
	messages: List<ChatMessageUi>,
	isStreaming: Boolean,
): Boolean {
	if (isStreaming) return false
	val last = messages.lastOrNull() ?: return false
	if (last.fromUser) return true
	if (last.isToolResult) {
		// A tool result is only half a round. Look at the assistant that asked
		// for it: an error there is a stop or a provider failure, and the error
		// banner already owns the retry; no error means the next round never
		// started, which is the interruption this function is for.
		val owner = messages.dropLast(1).lastOrNull { !it.fromUser && !it.isToolResult }
		return owner?.error == null
	}
	if (last.error != null) return false
	// The last row cannot have results after it, so a call here never ran.
	if (last.toolCalls.isNotEmpty()) return true
	// A row with no finish reason is a placeholder or a partial answer from a
	// build that persisted mid-stream; either way the turn never closed.
	return last.finishReason == null
}