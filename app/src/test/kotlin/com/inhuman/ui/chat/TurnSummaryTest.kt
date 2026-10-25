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
		val transcript = listOf(
			user("u1"),
			assistant("a1", UsageUi(promptTokens = 10, completionTokens = 5), streaming = false),
			tool("t1"),
			assistant("a2", UsageUi(promptTokens = 20, completionTokens = 7)),
		)
		val turns = turnSummariesOf(transcript)

		val turn = turns["a2"]!!
		assertEquals(listOf("u1", "a1", "t1", "a2"), turn.messagesIn(transcript).map { it.id })
		val usage = turn.usage!!
		assertEquals(30, usage.promptTokens)
		assertEquals(12, usage.completionTokens)
		assertTrue(turn.complete)
		assertNull(turns["a1"])
	}

	@Test
	fun `the range covers exactly the rows of the turn`() {
		// The summary used to carry copies of these rows; it carries indices
		// now, and the wire inspector slices the transcript with them. An
		// off-by-one here shows the inspector the previous turn's last row.
		val transcript = listOf(
			user("u1"),
			assistant("a1"),
			user("u2"),
			assistant("a2"),
			tool("t2"),
		)
		val turns = turnSummariesOf(transcript)

		val first = turns["a1"]!!
		assertEquals(0, first.start)
		assertEquals(1, first.end)
		assertEquals(listOf("u1", "a1"), first.messagesIn(transcript).map { it.id })

		val second = turns["a2"]!!
		assertEquals(2, second.start)
		assertEquals(4, second.end)
		assertEquals(listOf("u2", "a2", "t2"), second.messagesIn(transcript).map { it.id })
	}

	@Test
	fun `a transcript that begins mid-turn is still one turn`() {
		// A compacted history can start at an assistant row. The range has to
		// start at 0 rather than at the first user message, or that turn loses
		// its footer and the inspector loses its first row.
		val transcript = listOf(assistant("a1"), tool("t1"), assistant("a2"))
		val turns = turnSummariesOf(transcript)

		assertEquals(0, turns["a2"]!!.start)
		assertEquals(2, turns["a2"]!!.end)
	}

	@Test
	fun `slicing is safe against a shorter transcript`() {
		// Not a state the app reaches today: a summary is always read with the
		// list it was built from. The clamp exists so a mistake here cannot
		// throw inside a dialog, which is how a crash becomes a mystery.
		val long = listOf(user("u1"), assistant("a1"), tool("t1"))
		val turn = turnSummariesOf(long)["a1"]!!
		assertEquals(listOf("u1", "a1"), turn.messagesIn(long.take(2)).map { it.id })
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
