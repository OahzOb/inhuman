package com.inhuman.core.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two ids on a conversation are not interchangeable.
 *
 * `WorkspaceDocumentsProvider` publishes a workspace id as a folder's document
 * id, while `id` keys the transcript. A lookup by the wrong one returns null
 * rather than failing, so the bug it caused was silent: a re-queried folder
 * lost its name.
 *
 * With schema v11 the provider resolves a document id through the workspaces
 * table, but the distinction still matters: `workspaceId` is a foreign key, not
 * the conversation's primary key.
 */
@RunWith(AndroidJUnit4::class)
class ConversationLookupTest {

	private lateinit var db: InhumanDatabase

	@Before
	fun open() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		db = Room.inMemoryDatabaseBuilder(context, InhumanDatabase::class.java)
			.allowMainThreadQueries()
			.build()
	}

	@After
	fun close() {
		db.close()
	}

	@Test
	fun workspaceIdAndConversationIdAreDifferentKeys() = runBlocking {
		val workspace = WorkspaceEntity(
			id = "workspace-1",
			name = "Lookup",
			isPermanent = true,
			createdAt = 1L,
			updatedAt = 1L,
		)
		val conversation = ConversationEntity(
			id = "conversation-1",
			title = "Lookup",
			workspaceId = "workspace-1",
			createdAt = 1L,
			updatedAt = 1L,
		)
		db.workspaces().upsert(workspace)
		db.conversations().upsert(conversation)

		assertEquals(workspace, db.workspaces().byId("workspace-1"))
		assertNull(db.workspaces().byId("conversation-1"))
		assertEquals(conversation, db.conversations().byId("conversation-1"))
		assertNull(db.conversations().byId("workspace-1"))
	}
}
