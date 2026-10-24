package com.inhuman.desktop

import com.inhuman.core.data.db.StoredToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopRewindTest {
	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `rewinding a middle turn removes it and every later turn`() = runBlocking {
		val store = DesktopStore.open(tmp.newFolder("desktop-rewind-tree"))
		try {
			val conversation = store.create("Tree")
			val workspace = store.workspaceFor(conversation.id)
			assertNotNull(workspace)
			val note = workspace!!.rootPath().resolve("note.txt")

			val first = store.appendUser(conversation.id, "first", null)
			store.captureTurnStart(conversation.id, first.id)
			note.writeText("one")
			val firstAnswer = store.appendAssistant(conversation.id, "a1", "", first.id)

			val second = store.appendUser(conversation.id, "second", firstAnswer.id)
			store.captureTurnStart(conversation.id, second.id)
			note.writeText("two")
			val secondAnswer = store.appendAssistant(conversation.id, "a2", "", second.id)

			val third = store.appendUser(conversation.id, "third", secondAnswer.id)
			store.captureTurnStart(conversation.id, third.id)
			note.writeText("three")
			store.appendAssistant(conversation.id, "a3", "", third.id)

			assertEquals(3, store.rewindPoints(conversation.id).size)
			val outcome = store.rewindTo(conversation.id, second.id)
			assertNull(outcome.error)
			assertEquals("second", outcome.userText)
			assertEquals("the workspace is back to before the second turn", "one", note.readText())
			assertEquals(
				listOf("first", "a1"),
				store.load(conversation.id).map { it.text },
			)
			assertEquals(1, store.rewindPoints(conversation.id).size)
		} finally {
			store.close()
		}
	}

	@Test
	fun `a turn with an irreversible remote call is refused`() = runBlocking {
		val store = DesktopStore.open(tmp.newFolder("desktop-rewind-irreversible"))
		try {
			val conversation = store.create("Remote")
			val user = store.appendUser(conversation.id, "run it", null)
			store.captureTurnStart(conversation.id, user.id)
			store.appendAssistant(
				conversationId = conversation.id,
				text = "",
				reasoning = "",
				parentId = user.id,
				toolCalls = listOf(
					StoredToolCall(
						index = 0,
						id = "call-1",
						name = "ssh_exec",
						argumentsJson = """{"host":"example.test"}""",
					),
				),
			)

			val outcome = store.rewindTo(conversation.id, user.id)
			assertNotNull(outcome.error)
			assertTrue(outcome.error!!.contains("ssh_exec"))
			assertTrue("the rows are untouched", store.load(conversation.id).isNotEmpty())
		} finally {
			store.close()
		}
	}
}
