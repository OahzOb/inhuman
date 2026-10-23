package com.inhuman.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.InhumanDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Workspace lifetime at the repository level.
 *
 * These are the behaviours a directory-based model cannot express:
 * a temporary workspace dies with its conversation, a permanent one does not,
 * and promoting only changes the row rather than moving files.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceLifecycleTest {

	private lateinit var context: Context
	private lateinit var db: InhumanDatabase
	private lateinit var repository: ConversationRepository
	private val createdWorkspaces = mutableSetOf<String>()

	@Before
	fun setUp() {
		context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
		db = Room.inMemoryDatabaseBuilder(context, InhumanDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		repository = ConversationRepository(db, DataPaths(context.filesDir))
	}

	@After
	fun tearDown() {
		createdWorkspaces.forEach { id ->
			Database.workspaceDir(context, id).deleteRecursively()
		}
		db.close()
	}

	@Test
	fun `a temporary workspace is owned by its conversation and dies with it`() = runBlocking {
		val conversation = repository.create(title = "Temporary")
		createdWorkspaces += conversation.workspaceId
		val workspace = db.workspaces().byId(conversation.workspaceId)
		assertNotNull(workspace)
		assertFalse(workspace!!.isPermanent)
		assertTrue(workspace.ownerConversationId == conversation.id)
		assertTrue(Database.workspaceDir(context, workspace.id).isDirectory)

		repository.delete(conversation)

		assertNull(db.workspaces().byId(workspace.id))
		assertFalse(Database.workspaceDir(context, workspace.id).exists())
	}

	@Test
	fun `a permanent workspace survives a conversation and can hold another`() = runBlocking {
		val workspace = repository.createWorkspace("Shared")
		createdWorkspaces += workspace.id
		val first = repository.create(title = "First", workspaceId = workspace.id)
		val second = repository.create(title = "Second", workspaceId = workspace.id)

		assertTrue(Database.workspaceDir(context, workspace.id).isDirectory)
		assertTrue(repository.workspaceConversationCount(workspace.id) == 2)

		repository.delete(first)
		assertNotNull(db.workspaces().byId(workspace.id))
		assertTrue(Database.workspaceDir(context, workspace.id).isDirectory)
		assertTrue(repository.workspaceConversationCount(workspace.id) == 1)

		repository.delete(second)
		assertNotNull(db.workspaces().byId(workspace.id))
		assertTrue(repository.deleteWorkspace(workspace))
		assertNull(db.workspaces().byId(workspace.id))
		assertFalse(Database.workspaceDir(context, workspace.id).exists())
	}

	@Test
	fun `promoting a temporary workspace changes the row and not the directory`() = runBlocking {
		val conversation = repository.create(title = "Promote me")
		createdWorkspaces += conversation.workspaceId
		val before = Database.workspaceDir(context, conversation.workspaceId).absolutePath

		val promoted = repository.promoteWorkspace(conversation, "Promoted")
		assertNotNull(promoted)
		assertTrue(promoted!!.isPermanent)
		assertNull(promoted.ownerConversationId)
		assertTrue(promoted.name == "Promoted")
		assertTrue(before == Database.workspaceDir(context, conversation.workspaceId).absolutePath)
		assertTrue(Database.workspaceDir(context, conversation.workspaceId).isDirectory)

		repository.delete(conversation)
		assertNotNull(db.workspaces().byId(conversation.workspaceId))
		assertTrue(Database.workspaceDir(context, conversation.workspaceId).isDirectory)
	}
}
