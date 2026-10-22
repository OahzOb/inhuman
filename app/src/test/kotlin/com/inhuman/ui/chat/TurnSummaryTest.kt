package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn footer's arithmetic and grouping.
 *
 * The UI cannot be asserted from a JVM test, but the two things that were wrong
 * in the first draft can be: a turn that is still running must not look
 * finished, and four rounds must add up rather than show the last round's
 * numbers.
 */
class TurnSummaryTest {

	private fun assistant(id: String, usage: UsageUi? = null, streaming: Boolean = false) =
		ChatMessageUi(id = id, fromUser = false, usage = usage, isStreaming = streaming)

	private fun user(id: String) = ChatMessageUi(id = id, fromUser = true)

	private fun tool(id: String) = ChatMessageUi(id = id, fromUser = false, isToolResult = true)

	@Test
	fun `usage from every round is added`() {
		val total = aggregateUsage(
			listOf(
				UsageUi(
					promptTokens = 100,
					completionTokens = 20,
					cachedPromptTokens = 80,
					generationMs = 1000,
					timeToFirstTokenMs = 400,
				),
				UsageUi(
					promptTokens = 300,
					completionTokens = 30,
					cachedPromptTokens = 200,
					generationMs = 2000,
					timeToFirstTokenMs = 900,
				),
			),
		)!!

		assertEquals(400, total.promptTokens)
		assertEquals(50, total.completionTokens)
		assertEquals(280, total.cachedPromptTokens)
		assertEquals(3000L, total.generationMs)
		assertEquals(400L, total.timeToFirstTokenMs)
		assertEquals(280.0 / 400.0, total.cacheHitRatio!!, 0.0001)
	}

	@Test
	fun `a missing field stays missing instead of becoming zero`() {
		val total = aggregateUsage(
			listOf(
				UsageUi(promptTokens = 100),
				UsageUi(promptTokens = 50, cachedPromptTokens = 10),
			),
		)!!
		assertEquals(150, total.promptTokens)
		assertEquals(10, total.cachedPromptTokens)
		assertNull(total.completionTokens)
		assertNull(total.timeToFirstTokenMs)
	}

	@Test
	fun `no usage at all is no line`() {
		assertNull(aggregateUsage(emptyList()))
		assertNull(aggregateUsage(listOf(UsageUi())))
	}

	@Test
	fun `a turn is keyed by its last assistant and carries every row`() {
		val turns = turnSummariesOf(
			listOf(
				user("u1"),
				assistant("a1", UsageUi(promptTokens = 10, completionTokens = 5), streaming = false),
				tool("t1"),
				assistant("a2", UsageUi(promptTokens = 20, completionTokens = 7)),
			),
		)

		val turn = turns["a2"]!!
		assertEquals(listOf("u1", "a1", "t1", "a2"), turn.messages.map { it.id })
		assertEquals(listOf("a1", "a2"), turn.assistantMessages.map { it.id })
		val usage = turn.usage!!
		assertEquals(30, usage.promptTokens)
		assertEquals(12, usage.completionTokens)
		assertTrue(turn.complete)
		assertNull(turns["a1"])
	}

	@Test
	fun `a turn with a tool result still in flight is not complete`() {
		val turns = turnSummariesOf(
			listOf(
				user("u1"),
				assistant("a1"),
				tool("t1"),
			),
		)
		assertFalse(turns["a1"]!!.complete)
	}

	@Test
	fun `a streaming final assistant is not complete`() {
		val turns = turnSummariesOf(
			listOf(
				user("u1"),
				assistant("a1", streaming = true),
			),
		)
		assertFalse(turns["a1"]!!.complete)
	}

	@Test
	fun `a turn whose tool call is still running is not complete`() {
		val calls = listOf(
			ToolCallUi(index = 0, id = "c1", name = "read_file", state = ToolCallUi.State.Running),
		)
		val turns = turnSummariesOf(
			listOf(
				user("u1"),
				assistant("a1").copy(toolCalls = calls),
			),
		)
		assertFalse(turns["a1"]!!.complete)
	}

	@Test
	fun `two turns get two independent footers`() {
		val turns = turnSummariesOf(
			listOf(
				user("u1"),
				assistant("a1", UsageUi(promptTokens = 5, completionTokens = 1)),
				user("u2"),
				assistant("a2", UsageUi(promptTokens = 7, completionTokens = 2)),
			),
		)
		assertEquals(setOf("a1", "a2"), turns.keys)
		assertEquals(5, turns["a1"]!!.usage!!.promptTokens)
		assertEquals(7, turns["a2"]!!.usage!!.promptTokens)
	}
}
