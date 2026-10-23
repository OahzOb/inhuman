package com.inhuman.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The repositories on a plain JVM.
 *
 * These used to be Android-only: they read `context.filesDir` and opened the
 * database from a `Context`, which is why the desktop host had nothing to call.
 * The roots are injected now, and this is the evidence that the same code opens
 * a real Room database and the same directory layout without Android.
 */
class ConversationRepositoryTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var db: InhumanDatabase
	private lateinit var paths: DataPaths
	private lateinit var repository: ConversationRepository

	@Before
	fun setUp() {
		db = Room.inMemoryDatabaseBuilder<InhumanDatabase>()
			.setDriver(BundledSQLiteDriver())
			.build()
		paths = DataPaths(tmp.newFolder("files"))
		repository = ConversationRepository(db, paths)
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun `a conversation owns a temporary workspace directory`() = runBlocking {
		val conversation = repository.create(title = "JVM conversation")

		assertTrue(paths.workspaceDir(conversation.workspaceId).isDirectory)
		assertTrue(File(paths.workspacesRoot, conversation.workspaceId).isDirectory)

		repository.delete(conversation)

		assertFalse(paths.workspaceDir(conversation.workspaceId).exists())
		assertNull(db.conversations().byId(conversation.id))
	}

	@Test
	fun `a permanent workspace survives its conversation`() = runBlocking {
		val workspace = repository.createWorkspace("Shared")
		val directory = paths.workspaceDir(workspace.id)
		val conversation = repository.create(title = "One", workspaceId = workspace.id)

		repository.delete(conversation)

		assertTrue(directory.isDirectory)
		assertEquals(workspace.id, db.workspaces().byId(workspace.id)?.id)
	}

	@Test
	fun `messages append in transcript order`() = runBlocking {
		val conversation = repository.create(title = "Order")
		val user = message("m-user", conversation.id, null, "user", "hello", 1L)
		val assistant = message("m-assistant", conversation.id, user.id, "assistant", "hi", 2L)

		repository.appendMessage(user, null)
		repository.appendMessage(assistant, user.id)

		val loaded = repository.conversation(conversation.id)
		assertEquals(listOf("m-user", "m-assistant"), loaded?.messages?.map { it.id })
	}

	@Test
	fun `deleting a subtree is one transaction on the JVM`() = runBlocking {
		val conversation = repository.create(title = "Rewind")
		val user = message("t-user", conversation.id, null, "user", "hello", 1L)
		val reply = message("t-reply", conversation.id, user.id, "assistant", "hi", 2L)
		repository.appendMessage(user, null)
		repository.appendMessage(reply, user.id)

		repository.deleteSubtree(user.id, null, conversation.id)

		assertTrue(repository.conversation(conversation.id)?.messages.orEmpty().isEmpty())
	}

	private fun message(
		id: String,
		conversationId: String,
		parentId: String?,
		role: String,
		content: String,
		createdAt: Long,
	) = MessageEntity(
		id = id,
		conversationId = conversationId,
		parentId = parentId,
		role = role,
		content = content,
		createdAt = createdAt,
	)
}
