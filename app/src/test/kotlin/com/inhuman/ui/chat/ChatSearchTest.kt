package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-conversation search and turn index are pure list operations, so they
 * are tested here rather than through the screen: a jump is a position in a
 * `LazyColumn`, and the device cannot observe which row the list chose.
 */
class ChatSearchTest {

	private fun user(id: String, text: String) =
		ChatMessageUi(id = id, fromUser = true, plainText = text)

	private fun assistant(id: String, text: String) =
		ChatMessageUi(id = id, fromUser = false, plainText = text)

	private fun tool(id: String, text: String) =
		ChatMessageUi(id = id, fromUser = false, isToolResult = true, plainText = text)

	@Test
	fun `searches both sides of the conversation`() {
		val matches = searchConversation(
			listOf(
				user("u1", "the quick brown fox"),
				assistant("a1", "a quick answer"),
			),
			"quick",
		)

		assertEquals(listOf("u1", "a1"), matches.map { it.messageId })
		assertEquals(listOf(0, 1), matches.map { it.messageIndex })
		assertTrue(matches.first().fromUser)
		assertFalse(matches.last().fromUser)
	}

	@Test
	fun `a tool result is not a search hit`() {
		val matches = searchConversation(
			listOf(
				user("u1", "look at the notes"),
				tool("t1", "notes.txt notes.txt notes.txt"),
				assistant("a1", "the notes are there"),
			),
			"notes",
		)

		assertEquals(listOf("u1", "a1"), matches.map { it.messageId })
	}

	@Test
	fun `matching is case insensitive`() {
		val matches = searchConversation(listOf(user("u1", "MiXeD Case")), "mixed")
		assertEquals(1, matches.size)
	}

	@Test
	fun `a blank query matches nothing`() {
		assertTrue(searchConversation(listOf(user("u1", "anything")), "").isEmpty())
		assertTrue(searchConversation(listOf(user("u1", "anything")), "   ").isEmpty())
	}

	@Test
	fun `the snippet contains the match and marks a cut`() {
		val long = "x".repeat(80) + " needle " + "y".repeat(80)
		val match = searchConversation(listOf(user("u1", long)), "needle").single()

		assertTrue(match.snippet.contains("needle"))
		assertTrue(match.snippet.startsWith("…"))
		assertTrue(match.snippet.endsWith("…"))
	}

	@Test
	fun `a short message is quoted without ellipses`() {
		val match = searchConversation(listOf(user("u1", "a short note")), "short").single()
		assertEquals("a short note", match.snippet)
	}

	@Test
	fun `user turns keep transcript order and ids`() {
		val turns = userTurnsOf(
			listOf(
				user("u1", "first question"),
				assistant("a1", "first answer"),
				tool("t1", "tool output"),
				user("u2", "second question\nwith detail"),
				assistant("a2", "second answer"),
			),
		)

		assertEquals(listOf("u1", "u2"), turns.map { it.messageId })
		assertEquals(listOf(0, 3), turns.map { it.messageIndex })
		assertEquals("first question", turns[0].preview)
		assertEquals("second question", turns[1].preview)
	}

	@Test
	fun `an attachment-only turn has an empty preview`() {
		val turns = userTurnsOf(
			listOf(
				ChatMessageUi(id = "u1", fromUser = true, plainText = "\n \n"),
			),
		)
		assertEquals("", turns.single().preview)
	}

	@Test
	fun `a preview keeps only the first non-empty line`() {
		val turns = userTurnsOf(listOf(user("u1", "\n  \n first line \n second line")))
		assertEquals("first line", turns.single().preview)
		assertFalse(turns.single().preview.contains("second"))
	}
}
