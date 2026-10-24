package com.inhuman.desktop

import kotlinx.coroutines.runBlocking
import com.inhuman.core.data.db.ToolsColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The host-facing store the window talks to.
 *
 * The repository tests already open the database; this covers the part that is
 * new for the window: several conversations, switching between them, and a
 * reopen that still lists and loads each one separately.
 */
class DesktopStoreTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `conversations list, switch and survive a reopen`() = runBlocking {
		val dataDir = tmp.newFolder("desktop-data")
		var store = DesktopStore.open(dataDir)
		try {
			assertTrue("a fresh store starts empty", store.list().isEmpty())

			val first = store.create(title = "First")
			val second = store.create(title = "Second")
			assertEquals(
				setOf(first.id, second.id),
				store.list().map { it.id }.toSet(),
			)

			val user = store.appendUser(first.id, "hello", null)
			store.appendAssistant(first.id, "hi", "a thought", user.id)
			store.autoTitle(first.id, "A titled conversation")

			assertEquals(2, store.load(first.id).size)
			assertTrue("the other conversation stays empty", store.load(second.id).isEmpty())
		} finally {
			store.close()
		}

		// Closing and reopening is what the next launch does; the two
		// conversations must not collapse into one or lose their messages.
		store = DesktopStore.open(dataDir)
		try {
			assertEquals(2, store.list().size)
			val firstId = store.list().first { it.title == "A titled conversation" }.id
			val lines = store.load(firstId)
			assertEquals(2, lines.size)
			assertEquals("hello", lines.first().text)
			assertEquals("hi", lines.last().text)
		} finally {
			store.close()
		}
	}

	@Test
	fun `provider keys are stored through DPAPI and survive a reopen`() = runBlocking {
		val dataDir = tmp.newFolder("desktop-keys")
		var store = DesktopStore.open(dataDir)
		try {
			assertFalse(store.hasProviderKey("deepseek"))
			assertTrue(store.saveProviderKey("deepseek", "sk-desktop-test"))
			assertTrue(store.hasProviderKey("deepseek"))
			assertNotNull("a saved key must build a provider", store.provider("deepseek"))
		} finally {
			store.close()
		}

		store = DesktopStore.open(dataDir)
		try {
			assertTrue("the key survives the next launch", store.hasProviderKey("deepseek"))
			assertNotNull(store.provider("deepseek"))
		} finally {
			store.close()
		}
	}

	@Test
	fun `rename pin search and delete are one store operation each`() = runBlocking {
		val store = DesktopStore.open(tmp.newFolder("desktop-manage"))
		try {
			val first = store.create("First")
			val second = store.create("Second")
			store.appendUser(first.id, "alpha unique needle", null)

			store.rename(first.id, "Renamed")
			store.setPinned(first.id, true)

			val all = store.list()
			val renamed = all.first { it.id == first.id }
			assertEquals("Renamed", renamed.title)
			assertTrue("the pin is visible to the rail", renamed.pinned)
			assertEquals("a pinned conversation sorts first", first.id, all.first().id)

			val hits = store.search("needle")
			assertEquals(listOf(first.id), hits.map { it.id })

			store.delete(second.id)
			assertFalse(store.list().any { it.id == second.id })
		} finally {
			store.close()
		}
	}

	@Test
	fun `a conversation model is stored on the conversation`() = runBlocking {
		val store = DesktopStore.open(tmp.newFolder("desktop-model"))
		try {
			val conversation = store.create("Model")
			store.setConversationModel(conversation.id, "deepseek-reasoner")
			assertEquals(
				"deepseek-reasoner",
				store.conversation(conversation.id)?.entity?.model,
			)
		} finally {
			store.close()
		}
	}

	@Test
	fun `a conversation created with an assistant copies its settings`() = runBlocking {
		val store = DesktopStore.open(tmp.newFolder("desktop-assistant"))
		try {
			val base = store.defaultAssistant()
			assertNotNull(base)
			val chosen = base!!.copy(
				name = "Chosen assistant",
				systemPrompt = "Be terse.",
				model = "deepseek-chat",
				thinking = true,
				toolsJson = ToolsColumn.encode(listOf("read_file", "read_time")),
			)
			store.saveAssistant(chosen)

			val conversation = store.create("With assistant", chosen)
			val entity = store.conversation(conversation.id)?.entity
			assertNotNull(entity)
			assertEquals(chosen.id, entity!!.agentId)
			assertEquals("deepseek-chat", entity.model)
			assertEquals(chosen.toolsJson, entity.toolsJson)
			assertTrue(entity.thinking)
		} finally {
			store.close()
		}
	}
}
