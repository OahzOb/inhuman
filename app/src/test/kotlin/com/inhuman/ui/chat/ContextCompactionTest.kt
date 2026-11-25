package com.inhuman.ui.chat

import com.inhuman.core.ai.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactionTest {

	private fun user(id: String, text: String = "question $id") = ChatMessageUi(
		id = id,
		fromUser = true,
		plainText = text,
	)

	private fun assistant(id: String, text: String = "answer $id") = ChatMessageUi(
		id = id,
		fromUser = false,
		plainText = text,
	)

	private fun conversation(turns: Int): List<ChatMessageUi> = buildList {
		for (turn in 1..turns) {
			add(user("u$turn"))
			add(assistant("a$turn"))
		}
	}

	@Test
	fun `keeps the requested recent turns and summarizes the rest`() {
		val plan = planContextCompaction(conversation(5), keepTurns = 2)

		requireNotNull(plan)
		assertEquals("a3", plan.throughMessageId)
		assertEquals(3, plan.omittedTurns)
		assertEquals(6, plan.omittedMessages)
		assertEquals(2, plan.keptTurns)
	}

	@Test
	fun `does nothing when every turn is recent`() {
		assertNull(planContextCompaction(conversation(2), keepTurns = 2))
		assertNull(planContextCompaction(emptyList()))
	}

	@Test
	fun `a tool group is never split`() {
		val messages = mutableListOf<ChatMessageUi>()
		messages += user("u1")
		messages += ChatMessageUi(
			id = "a1",
			fromUser = false,
			plainText = "",
			toolCalls = listOf(
				ToolCallUi(
					index = 0,
					id = "call-1",
					name = "read_file",
					argumentsJson = "{\"path\":\"a.txt\"}",
				),
			),
		)
		messages += ChatMessageUi(
			id = "t1",
			fromUser = false,
			isToolResult = true,
			toolResultFor = "call-1",
			plainText = "contents",
		)
		messages += assistant("a1-final")
		messages += user("u2")
		messages += assistant("a2")
		messages += user("u3")
		messages += assistant("a3")

		val plan = planContextCompaction(messages, keepTurns = 2)
		requireNotNull(plan)
		assertEquals("a1-final", plan.throughMessageId)
		assertEquals(4, plan.omittedMessages)
	}

	@Test
	fun `an unfinished last turn is not counted as a kept turn`() {
		val messages = conversation(2) + user("u3")
		val plan = planContextCompaction(messages, keepTurns = 2)

		requireNotNull(plan)
		assertEquals("a1", plan.throughMessageId)
	}

	@Test
	fun `the summarizer input is bounded`() {
		val huge = conversation(20).map {
			it.copy(plainText = "x".repeat(10_000))
		}
		val text = compactionTranscript(huge, maxChars = 5_000)

		assertTrue("must not exceed the budget", text.length <= 5_000)
		assertTrue("must contain useful content", text.contains("USER:"))
	}

	@Test
	fun `demo summary is deterministic and names the questions`() {
		val summary = demoCompactionSummary(
			listOf(user("u1", "write a report"), assistant("a1")),
		)

		assertTrue(summary.contains("Earlier context"))
		assertTrue(summary.contains("write a report"))
		assertTrue(summary.contains("1 earlier user turn"))
	}

	@Test
	fun `an in-turn insertion is not a turn to keep`() {
		// Two real turns plus one insertion inside the second. Keeping two
		// turns must find nothing older to fold; counting the insertion as a
		// third turn would offer a compaction whose boundary splits turn two.
		val messages = listOf(
			user("u1"),
			assistant("a1"),
			user("u2"),
			ChatMessageUi(id = "i2", fromUser = true, injected = true, plainText = "wait"),
			assistant("a2"),
		)

		assertNull(planContextCompaction(messages, keepTurns = 2))
	}

	@Test
	fun `a blank summary blames reasoning only when the budget ran out there`() {
		// The case that was reported: a model thought through the whole
		// `max_tokens` budget and stopped before writing the summary.
		assertTrue(summaryStarvedByReasoning(FinishReason.Length, reasoningChars = 3_500))

		// A model that stopped on its own and still wrote nothing is not the
		// same failure, and the message must not claim it is.
		assertFalse(summaryStarvedByReasoning(FinishReason.Stop, reasoningChars = 3_500))
		assertFalse(summaryStarvedByReasoning(FinishReason.Length, reasoningChars = 0))
		// No finish reason at all (a transport that ended without one) cannot
		// be evidence of a budget.
		assertFalse(summaryStarvedByReasoning(null, reasoningChars = 3_500))
	}
}
