package com.inhuman.ui.chat

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.tools.SshExecTool
import com.inhuman.core.common.diag.Diag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
 * Remove SSH credentials before the tool-call arguments are stored.
 *
 * The user may legitimately paste a password or key into the conversation, and
 * it has to reach the model and the remote host. It does **not** have to become
 * a durable row: `toolCallsJson` is only a record of what was asked, and a
 * stored credential is a credential the user cannot revoke by rotating it at
 * the other end. The in-memory call keeps the real arguments for the turn; the
 * persisted form keeps host/port/user/command/identityFile and drops the three
 * secret fields.
 *
 * Returns `{}` if the arguments cannot be parsed rather than falling back to
 * the raw string, because the fallback is exactly where a secret would survive.
 */
internal fun redactSshArguments(raw: String): String {
	if (raw.isBlank()) return raw
	return runCatching {
		val normalized = SshExecTool.normalizeArguments(Json.parseToJsonElement(raw).jsonObject)
		val safe = normalized.toMutableMap()
		safe.remove("password")
		safe.remove("privateKey")
		safe.remove("passphrase")
		JsonObject(safe).toString()
	}.getOrElse { "{}" }
}

