package com.inhuman.ui.chat

import com.inhuman.core.engine.projection.ProjectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMaintenanceTest {

	private fun message(id: String, fromUser: Boolean, text: String) = ChatMessageUi(
		id = id,
		fromUser = fromUser,
		plainText = text,
	)

	@Test
	fun `a brief under the ceiling is kept verbatim`() {
		val raw = "### Goal & current state\nsmall brief"
		val capped = capBrief(raw, maxBriefTokens = 100)
		assertEquals(raw, capped.text)
		assertFalse(capped.truncated)
	}

	@Test
	fun `an over-long brief is cut at a section boundary and says so`() {
		val raw = buildString {
			append("### Goal & current state\n")
			append("goal ".repeat(80))
			append("\n### Timeline\n")
			append("entry ".repeat(200))
		}
		val capped = capBrief(raw, maxBriefTokens = 120)
		assertTrue(capped.truncated)
		assertTrue(capped.text.startsWith("### Goal & current state"))
		assertFalse(capped.text.contains("### Timeline"))
	}

	@Test
	fun `ASCII fits more characters than CJK under the same ceiling`() {
		val latin = "a".repeat(400)
		val han = "汉".repeat(400)
		assertFalse(capBrief(latin, maxBriefTokens = 120).truncated)
		assertTrue(capBrief(han, maxBriefTokens = 120).truncated)
	}

	@Test
	fun `a batch stops at a complete turn before the token cap`() {
		val messages = listOf(
			message("u1", fromUser = true, "汉".repeat(100)),
			message("a1", fromUser = false, "汉".repeat(100)),
			message("u2", fromUser = true, "汉".repeat(100)),
			message("a2", fromUser = false, "汉".repeat(100)),
		)
		val end = chooseBatchEnd(
			messages = messages,
			start = 0,
			targetEnd = 4,
			maxBatchTokens = 300,
			ratio = 1.0,
			policy = ProjectionPolicy(),
		)
		assertEquals("the first complete turn, not half of the second", 2, end)
	}

	@Test
	fun `a target inside a turn stops at the turn before it`() {
		val messages = listOf(
			message("u1", fromUser = true, "one"),
			message("a1", fromUser = false, "answer"),
			message("u2", fromUser = true, "two"),
		)
		val end = chooseBatchEnd(
			messages = messages,
			start = 0,
			targetEnd = 2,
			maxBatchTokens = 32_000,
			ratio = 1.0,
			policy = ProjectionPolicy(),
		)
		assertEquals(2, end)
	}

	@Test
	fun `a single turn over the cap is folded alone, not split`() {
		val messages = listOf(
			message("u1", fromUser = true, "汉".repeat(10_000)),
			message("a1", fromUser = false, "汉".repeat(10_000)),
		)
		val end = chooseBatchEnd(
			messages = messages,
			start = 0,
			targetEnd = 2,
			maxBatchTokens = 10,
			ratio = 1.0,
			policy = ProjectionPolicy(),
		)
		assertEquals(2, end)
	}

	@Test
	fun `an empty range folds nothing`() {
		val messages = listOf(message("u1", fromUser = true, "hello"))
		val end = chooseBatchEnd(
			messages = messages,
			start = 1,
			targetEnd = 1,
			maxBatchTokens = 32_000,
			ratio = 1.0,
			policy = ProjectionPolicy(),
		)
		assertEquals(1, end)
	}
}
