package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-memory queue behind "send while the agent is working".
 *
 * The view model cannot be constructed without Android, so the decisions that
 * matter are pure functions and are tested here: what an insertion becomes when
 * the turn has no next round, and when the composer may offer "insert now".
 */
class TurnQueueTest {

	@Test
	fun `an insertion that never found a round becomes an ordinary queue entry`() {
		val queue = listOf(
			QueuedMessage("1", "insert me", insert = true),
			QueuedMessage("2", "send me"),
			QueuedMessage(
				"3",
				"image",
				attachments = listOf(PendingAttachment("a.png", "image/png", 1)),
				insert = true,
			),
		)

		val after = afterTurn(queue)

		assertFalse(after[0].insert)
		assertFalse(after[1].insert)
		// An entry with an image was never insertable; the flag is cleared too,
		// so the strip stops promising something the engine cannot deliver.
		assertFalse(after[2].insert)
		assertEquals(listOf("1", "2", "3"), after.map { it.id })
		assertEquals(listOf("insert me", "send me", "image"), after.map { it.text })
	}

	@Test
	fun `afterTurn leaves an ordinary queue untouched`() {
		val queue = listOf(QueuedMessage("1", "first"), QueuedMessage("2", "second"))
		assertEquals(queue, afterTurn(queue))
	}

	@Test
	fun `insert is offered for text without attachments only`() {
		assertTrue(canInsertNow("hello", emptyList()))
		assertFalse(canInsertNow("", emptyList()))
		assertFalse(canInsertNow("   ", emptyList()))
		assertFalse(
			canInsertNow("hello", listOf(PendingAttachment("a.png", "image/png", 1))),
		)
	}

	@Test
	fun `only a text-only message is insertable`() {
		assertTrue(QueuedMessage("1", "hello").insertable)
		assertFalse(QueuedMessage("2", "   ").insertable)
		assertFalse(
			QueuedMessage(
				"3",
				"hello",
				attachments = listOf(PendingAttachment("a.png", "image/png", 1)),
			).insertable,
		)
	}

	@Test
	fun `the queue preview is the first non-empty line, bounded`() {
		assertEquals("first line", QueuedMessage("1", "  \n first line \n second").preview)
		assertEquals(80, QueuedMessage("2", "x".repeat(200)).preview.length)
	}
}
