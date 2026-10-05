package com.inhuman.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Walking the active branch.
 *
 * ## Why this is tested at all, and why here
 *
 * `activePath` is a pure function over rows -- it opens no database and touches
 * no Android API -- so the whole of it can be checked on the JVM. What it
 * decides is which messages are on screen, so getting it wrong is not a
 * cosmetic failure: a transcript in the wrong order, or a branch switch that
 * shows an answer nobody selected.
 *
 * The case that motivated the test is the tie. A tool result is written
 * immediately after the assistant turn that asked for it, which is routinely
 * inside the same millisecond, and the first version of this function broke
 * ties on `createdAt` alone. With two rows sharing a timestamp the order was
 * whatever SQLite happened to return.
 */
class ConversationStoreTest {

	private var clock = 1_000L

	/** A message with a fresh id and an explicit timestamp. */
	private fun message(
		id: String,
		parentId: String? = null,
		at: Long,
		selectedChildId: String? = null,
		role: String = "assistant",
	) = MessageEntity(
		id = id,
		conversationId = "c1",
		parentId = parentId,
		role = role,
		content = id,
		createdAt = at,
		selectedChildId = selectedChildId,
	)

	@Test
	fun `a straight line comes back in order`() {
		val rows = listOf(
			message("a", at = 1),
			message("b", parentId = "a", at = 2),
			message("c", parentId = "b", at = 3),
		)

		assertEquals(listOf("a", "b", "c"), activePath(rows).map { it.id })
	}

	@Test
	fun `the selected child wins over the newest one`() {
		// Two answers to the same question, with the *older* one selected. The
		// selection lives on the parent row, which is the only copy of "q" in
		// the table -- the walk reads `selectedChildId` off the node it is
		// standing on.
		val rows = listOf(
			message("q", at = 1, selectedChildId = "answer-1", role = "user"),
			message("answer-1", parentId = "q", at = 2),
			message("answer-2", parentId = "q", at = 3),
		)

		assertEquals(listOf("q", "answer-1"), activePath(rows).map { it.id })
	}

	@Test
	fun `with nothing selected the newest child is shown`() {
		val rows = listOf(
			message("q", at = 1, role = "user"),
			message("answer-1", parentId = "q", at = 2),
			message("answer-2", parentId = "q", at = 3),
		)

		assertEquals(listOf("q", "answer-2"), activePath(rows).map { it.id })
	}

	@Test
	fun `rows sharing a timestamp keep a stable order`() {
		// The bug this guards: an assistant turn and the tool result answering
		// it are written in the same millisecond, so `createdAt` cannot order
		// them and the id has to.
		val same = 5_000L
		val rows = listOf(
			message("user", at = 1, role = "user"),
			message("assistant", parentId = "user", at = same),
			message("tool-result", parentId = "assistant", at = same, role = "tool"),
		)

		val path = activePath(rows).map { it.id }

		// Whichever way the ids sort, the result must be the same every time
		// and must be a walk: user -> assistant -> tool result.
		assertEquals("user", path.first())
		assertEquals(setOf("user", "assistant", "tool-result"), path.toSet())
		assertEquals(
			"id order decides the tie",
			listOf("user", "assistant", "tool-result").sortedWith(
				compareBy<String> { if (it == "user") 1L else same }.thenBy { it },
			),
			path.sortedWith(compareBy<String> { if (it == "user") 1L else same }.thenBy { it }),
		)
	}

	@Test
	fun `a tie between siblings resolves the same way every time`() {
		// Two children of one parent with identical timestamps and no selected
		// child: the fallback has to be deterministic, or a reload can show a
		// different answer than the one just streamed.
		val rows = listOf(
			message("q", at = 1),
			message("m", parentId = "q", at = 7),
			message("a", parentId = "q", at = 7),
			message("z", parentId = "q", at = 7),
		)

		val first = activePath(rows).map { it.id }
		val shuffled = activePath(rows.reversed()).map { it.id }

		assertEquals("the same input order gives the same answer", first, activePath(rows).map { it.id })
		assertEquals("and so does a different input order", first, shuffled)
		assertEquals("the tie-break is the id", listOf("q", "z"), first)
	}

	@Test
	fun `several roots resolve to the earliest, deterministically`() {
		val rows = listOf(
			message("second", at = 20),
			message("first", at = 10),
		)

		assertEquals(listOf("first"), activePath(rows).map { it.id })
	}

	@Test
	fun `no messages is an empty path, not a crash`() {
		assertEquals(emptyList<String>(), activePath(emptyList()).map { it.id })
	}

	@Test
	fun `a cycle does not hang the walk`() {
		// Not reachable through the UI today, but a walk that trusts the data
		// to be a tree is one bad row away from an infinite loop, and this is
		// the cheapest place to find that out.
		val rows = listOf(
			message("a", parentId = "b", at = 1),
			message("b", parentId = "a", at = 2),
		)

		// No root exists, so there is no path to walk and the answer is empty.
		assertEquals(emptyList<String>(), activePath(rows).map { it.id })
	}

	/** The walk under test, reachable without a database instance. */
	private fun activePath(messages: List<MessageEntity>): List<MessageEntity> =
		ConversationStore.activePathOf(messages)
}
