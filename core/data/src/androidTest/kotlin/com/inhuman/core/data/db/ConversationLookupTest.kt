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
 * `WorkspaceDocumentsProvider` publishes `workspaceId` as a folder's document
 * id, while `id` keys the transcript. A lookup by the wrong one returns null
 * rather than failing, so the bug it caused was silent: a re-queried folder
 * lost its title, and the provider's delete/rename refusal never fired for the
 * ids it actually issued.
 *
 * This pins the DAO half of that distinction. The provider half needs an
 * Android `ContentResolver`; the call sites are verified by reading them, not
 * by this test.
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
		val conversation = ConversationEntity(
			id = "conversation-1",
			title = "Lookup",
			workspaceId = "workspace-1",
			createdAt = 1L,
			updatedAt = 1L,
		)
		db.conversations().upsert(conversation)

		assertEquals(conversation, db.conversations().byWorkspaceId("workspace-1"))
		assertNull(db.conversations().byId("workspace-1"))
		assertEquals(conversation, db.conversations().byId("conversation-1"))
	}
}
