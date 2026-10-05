package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The delta invariant: what is sent, joined, is exactly the reply.
 *
 * The Markdown parser appends and never compares, so these two failure modes
 * are the whole risk: text sent twice renders twice, text never sent is lost
 * from an answer the user is paying for. Both look identical to a screenshot
 * taken once the stream has finished, which is why they are asserted here
 * rather than eyeballed.
 */
class DeltaCursorTest {

	/** Feeds a reply in pieces and returns everything the cursor emitted. */
	private fun streamInPieces(full: String, pieces: Int): String {
		val cursor = DeltaCursor()
		val out = StringBuilder()
		val step = (full.length + pieces - 1) / pieces
		var i = 0
		while (i < full.length) {
			i = (i + step).coerceAtMost(full.length)
			cursor.next(full.substring(0, i))?.let { out.append(it) }
		}
		return out.toString()
	}

	@Test
	fun `nothing sent until the buffer grows`() {
		val cursor = DeltaCursor()

		assertNull(cursor.next(""))
		assertEquals("a", cursor.next("a"))
		assertNull("no new text means no emission", cursor.next("a"))
	}

	@Test
	fun `every slice is the part that is new`() {
		val cursor = DeltaCursor()

		assertEquals("Hel", cursor.next("Hel"))
		assertEquals("lo", cursor.next("Hello"))
		assertEquals(" there", cursor.next("Hello there"))
	}

	@Test
	fun `the pieces joined are the whole reply, however it is cut`() {
		// The property that matters, over a range of chunk sizes: one
		// character at a time, then two, then a few, up to the whole thing at
		// once. A cursor that skipped or repeated a slice fails on some of
		// these and passes on the others, which is exactly the bug that
		// survives hand-testing.
		val reply = "# A reply\n\nwith **markdown**, a list:\n\n- one\n- two\n\n```\ncode\n```\n"

		for (pieces in 1..reply.length) {
			assertEquals(
				"cut into $pieces pieces",
				reply,
				streamInPieces(reply, pieces),
			)
		}
	}

	@Test
	fun `a replayed prefix is not sent twice`() {
		// The view model publishes on a timer, and the buffer it reads is the
		// accumulated answer -- so the same text can be offered more than once.
		val cursor = DeltaCursor()

		assertEquals("abc", cursor.next("abc"))
		assertNull(cursor.next("abc"))
		assertNull(cursor.next("abc"))
		assertEquals("def", cursor.next("abcdef"))
	}

	@Test
	fun `a buffer that shrinks restarts rather than losing text`() {
		// Not how a reply streams, but it is how a *new* reply would look if
		// this cursor were ever reused across turns. Restarting is the only
		// reading under which the rendered document still equals the text.
		val cursor = DeltaCursor()

		assertEquals("first reply", cursor.next("first reply"))
		assertEquals("second", cursor.next("second"))
	}

	@Test
	fun `a cursor can start part-way, as it does after a reload`() {
		val cursor = DeltaCursor(initialText = "already there")

		assertNull(cursor.next("already there"))
		assertEquals(" and more", cursor.next("already there and more"))
	}

	@Test
	fun `an empty reply emits nothing`() {
		assertNull(DeltaCursor().next(""))
	}
}
