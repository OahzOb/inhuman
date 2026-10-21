package com.inhuman.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.WorkspaceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The search query is the only place user text is matched, so its escaping and
 * ordering are worth pinning on a real SQLite engine rather than a fake.
 */
@RunWith(AndroidJUnit4::class)
class ConversationSearchTest {

	private lateinit var db: InhumanDatabase
	private lateinit var repository: ConversationRepository

	@Before
	fun setUp() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		db = Room.inMemoryDatabaseBuilder(context, InhumanDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		repository = ConversationRepository(context, db)
		runBlocking {
			db.workspaces().upsert(
				WorkspaceEntity(
					id = "workspace-1",
					name = "Search",
					isPermanent = true,
					createdAt = 1L,
					updatedAt = 1L,
				),
			)
			db.conversations().upsert(
				ConversationEntity(
					id = "conversation-1",
					title = "Database notes",
					workspaceId = "workspace-1",
					createdAt = 1L,
					updatedAt = 1L,
				),
			)
			db.messages().upsert(
				MessageEntity(
					id = "message-1",
					conversationId = "conversation-1",
					parentId = null,
					role = "user",
					content = "Find the needle in this sentence.",
					createdAt = 2L,
				),
			)
			db.messages().upsert(
				MessageEntity(
					id = "message-2",
					conversationId = "conversation-1",
					parentId = "message-1",
					role = "assistant",
					content = "A literal 100% match, not a wildcard.",
					createdAt = 3L,
				),
			)
		}
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun findsMessageTextAndKeepsTheSnippet() = runBlocking {
		val hits = repository.search("needle")
		assertEquals(1, hits.size)
		assertEquals("message-1", hits.single().messageId)
		assertTrue(hits.single().snippet.contains("needle"))
	}

	@Test
	fun titleMatchesComeFirstAndHaveNoMessage() = runBlocking {
		val hits = repository.search("Database")
		assertEquals("conversation-1", hits.first().conversationId)
		assertEquals(null, hits.first().messageId)
		assertEquals("Conversation title", hits.first().snippet)
	}

	@Test
	fun percentIsLiteralRatherThanAWildcard() = runBlocking {
		val literal = repository.search("100%")
		assertEquals("message-2", literal.single().messageId)
		// A bare `%` matches only the literal percent sign in message-2; if the
		// escape were missing it would match every message in the database.
		assertEquals("message-2", repository.search("%").single().messageId)
	}
}
