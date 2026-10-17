package com.inhuman.ui.chat

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request-history repair for a process death during an approval or SSH
 * consent dialog.
 *
 * The device failure: an assistant row with two `tool_calls` and no tool rows
 * was sent as the next request, and DeepSeek rejected the whole conversation
 * with HTTP 400. The repair has to insert the missing tool message *before* the
 * next user message, not at the end of the transcript.
 */
class WireHistoryTest {

	@Test
	fun `a missing tool result is inserted before the next user message`() {
		val messages = listOf(
			ChatMessage(Role.User, "do the thing"),
			ChatMessage(
				role = Role.Assistant,
				content = "",
				toolCalls = listOf(
					ToolCall("call-1", "ssh_exec", "{}"),
					ToolCall("call-2", "ssh_exec", "{}"),
				),
			),
			ChatMessage(Role.Tool, "one result", toolCallId = "call-1"),
			ChatMessage(Role.User, "next question"),
		)

		val repaired = closeDanglingCalls(messages, "interrupted")

		assertEquals(5, repaired.size)
		assertEquals(Role.Assistant, repaired[1].role)
		assertEquals("one result", repaired[2].content)
		assertEquals(Role.Tool, repaired[3].role)
		assertEquals("call-2", repaired[3].toolCallId)
		assertEquals("interrupted", repaired[3].content)
		assertEquals("next question", repaired[4].content)
	}

	@Test
	fun `a complete call block is left alone`() {
		val messages = listOf(
			ChatMessage(
				role = Role.Assistant,
				content = "",
				toolCalls = listOf(ToolCall("call-1", "ssh_exec", "{}")),
			),
			ChatMessage(Role.Tool, "ok", toolCallId = "call-1"),
			ChatMessage(Role.User, "thanks"),
		)

		assertEquals(messages, closeDanglingCalls(messages, "interrupted"))
	}

	@Test
	fun `assistant text without calls is left alone`() {
		val messages = listOf(
			ChatMessage(Role.User, "hi"),
			ChatMessage(Role.Assistant, "hello"),
		)

		assertEquals(messages, closeDanglingCalls(messages, "interrupted"))
	}

	@Test
	fun `a dangling call with no result gets one`() {
		val messages = listOf(
			ChatMessage(Role.User, "go"),
			ChatMessage(
				role = Role.Assistant,
				content = "",
				toolCalls = listOf(ToolCall("call-1", "ssh_exec", "{}")),
			),
		)

		val repaired = closeDanglingCalls(messages, "interrupted")
		assertEquals(3, repaired.size)
		assertTrue(repaired[2].role == Role.Tool)
		assertEquals("call-1", repaired[2].toolCallId)
	}

	@Test
	fun `ssh credentials are removed from the stored arguments`() {
		val stored = redactSshArguments(
			"{\"host\":\"example.test\",\"port\":22,\"username\":\"root\"," +
				"\"command\":\"id\",\"password\":\"secret\",\"passphrase\":\"phrase\"}",
		)
		assertTrue(stored.contains("\"host\":\"example.test\""))
		assertTrue(stored.contains("\"command\":\"id\""))
		assertTrue(!stored.contains("secret"))
		assertTrue(!stored.contains("phrase"))
		assertTrue(!stored.contains("password"))
	}

	@Test
	fun `a wrapped ssh argument string is unwrapped before redaction`() {
		val stored = redactSshArguments(
			"""{"arguments":"{\"hostname\":\"example.test\",\"username\":\"root\",\"command\":\"id\",\"privateKey\":\"KEY\"}"}""",
		)
		assertTrue(stored.contains("\"host\":\"example.test\""))
		assertTrue(!stored.contains("KEY"))
	}
}
