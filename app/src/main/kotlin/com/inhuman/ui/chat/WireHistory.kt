package com.inhuman.ui.chat

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.tools.SshArguments
import com.inhuman.core.common.diag.Diag

/**
 * Put a tool result after every assistant `tool_calls` row on the wire.
 *
 * `buildWireHistory` reconstructs the transcript, but a process death while a
 * consent/approval dialog was open can leave an assistant call row with no
 * result rows. If the user has already sent another message, the result cannot
 * be appended at the tail without moving later text, so the synthetic result is
 * inserted **in the request history**. The stored transcript is repaired
 * separately when the dangling call is the tail.
 *
 * Extracted from the view model so the ordering can be tested without a
 * database or a running turn. The device failure this prevents is HTTP 400
 * `assistant message with 'tool_calls' must be followed by tool messages`, which
 * otherwise makes every later turn in the conversation fail.
 */
internal fun closeDanglingCalls(
	messages: List<ChatMessage>,
	content: String = "[interrupted] The app stopped before this call finished; it was not run.",
): List<ChatMessage> {
	val repaired = mutableListOf<ChatMessage>()
	var index = 0
	while (index < messages.size) {
		val message = messages[index]
		repaired += message
		if (message.role == Role.Assistant && message.toolCalls.isNotEmpty()) {
			val missing = message.toolCalls.mapNotNull { it.id }.toMutableSet()
			var resultIndex = index + 1
			while (resultIndex < messages.size && messages[resultIndex].role == Role.Tool) {
				val result = messages[resultIndex]
				result.toolCallId?.let { missing.remove(it) }
				repaired += result
				resultIndex++
			}
			for (id in missing) {
				Diag.w("tool", "closing dangling call on the wire id=$id")
				repaired += ChatMessage(
					role = Role.Tool,
					content = content,
					toolCallId = id,
				)
			}
			index = resultIndex
		} else {
			index++
		}
	}
	return repaired
}

/**
 * Android's name for the shared SSH redaction.
 *
 * The implementation lives on `SshArguments` so the desktop host, which writes
 * the same `toolCallsJson` column through `ToolCallsColumn`, cannot drift from
 * it. The in-memory call keeps the real arguments for the turn; the persisted
 * form keeps host/port/user/command/identityFile and drops the three secret
 * fields.
 */
internal fun redactSshArguments(raw: String): String = SshArguments.redact(raw)

