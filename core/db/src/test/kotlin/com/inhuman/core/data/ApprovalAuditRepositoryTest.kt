package com.inhuman.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.ai.policy.PolicySource
import com.inhuman.core.data.db.ApprovalDecision
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.WorkspaceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit's two claims: the answer is recorded once, and the conversation is
 * its retention boundary.
 *
 * These run on the JVM against the real schema rather than a fake, because the
 * interesting parts are the SQL guard on the update and the foreign key that
 * makes deletion clean up. A hand-written fake would test neither.
 */
class ApprovalAuditRepositoryTest {

	private suspend fun open(): InhumanDatabase {
		val db = Room.inMemoryDatabaseBuilder<InhumanDatabase>()
			.setDriver(BundledSQLiteDriver())
			.build()
		db.workspaces().upsert(
			WorkspaceEntity(id = "w1", name = "w", createdAt = 1, updatedAt = 1),
		)
		db.conversations().upsert(
			ConversationEntity(
				id = "c1",
				title = "t",
				workspaceId = "w1",
				createdAt = 1,
				updatedAt = 1,
			),
		)
		return db
	}

	@Test
	fun `asked then allowed is one row with both timestamps`() = runBlocking {
		val db = open()
		try {
			val repo = ApprovalAuditRepository(db)
			val id = repo.asked(
				conversationId = "c1",
				messageId = "m1",
				callId = "call-1",
				toolName = "write_file",
				path = "notes.md",
				sources = setOf(PolicySource.Global),
				toolAsked = false,
				at = 100,
			)
			repo.decided(id, ApprovalDecision.Allowed, at = 200)

			val rows = repo.forConversation("c1")
			assertEquals(1, rows.size)
			val row = rows.single()
			assertEquals("write_file", row.toolName)
			assertEquals("notes.md", row.path)
			assertEquals("global", row.rule)
			assertEquals(ApprovalDecision.Allowed, row.decision)
			assertEquals(100L, row.askedAt)
			assertEquals(200L, row.decidedAt)
		} finally {
			db.close()
		}
	}

	@Test
	fun `a row that was only asked reads as asked`() = runBlocking {
		val db = open()
		try {
			val repo = ApprovalAuditRepository(db)
			repo.asked(
				conversationId = "c1",
				messageId = "m1",
				callId = "call-1",
				toolName = "delete_file",
				path = "a.txt",
				sources = setOf(PolicySource.Path),
				toolAsked = false,
				at = 100,
			)

			val row = repo.forConversation("c1").single()
			assertEquals(ApprovalDecision.Asked, row.decision)
			assertNull(row.decidedAt)
		} finally {
			db.close()
		}
	}

	@Test
	fun `the first terminal decision wins`() = runBlocking {
		val db = open()
		try {
			val repo = ApprovalAuditRepository(db)
			val id = repo.asked(
				conversationId = "c1",
				messageId = "m1",
				callId = "call-1",
				toolName = "write_file",
				path = "a.txt",
				sources = setOf(PolicySource.Assistant),
				toolAsked = false,
				at = 100,
			)
			repo.decided(id, ApprovalDecision.Allowed, at = 200)
			repo.decided(id, ApprovalDecision.TimedOut, at = 300)

			val row = repo.forConversation("c1").single()
			assertEquals(ApprovalDecision.Allowed, row.decision)
			assertEquals(200L, row.decidedAt)
		} finally {
			db.close()
		}
	}

	@Test
	fun `a tool's own requirement is stored as tool`() = runBlocking {
		val db = open()
		try {
			val repo = ApprovalAuditRepository(db)
			repo.asked(
				conversationId = "c1",
				messageId = "m1",
				callId = "call-1",
				toolName = "web_fetch",
				path = null,
				sources = emptySet(),
				toolAsked = true,
				at = 100,
			)

			assertEquals("tool", repo.forConversation("c1").single().rule)
		} finally {
			db.close()
		}
	}

	@Test
	fun `two tied sources are both recorded, in a stable order`() = runBlocking {
		val db = open()
		try {
			val repo = ApprovalAuditRepository(db)
			repo.asked(
				conversationId = "c1",
				messageId = "m1",
				callId = "call-1",
				toolName = "write_file",
				path = "a.txt",
				sources = setOf(PolicySource.Path, PolicySource.Global),
				toolAsked = true,
				at = 100,
			)

			assertEquals("global,path,tool", repo.forConversation("c1").single().rule)
		} finally {
			db.close()
		}
	}

	@Test
	fun `deleting the conversation removes its audit`() = runBlocking {
		val db = open()
		try {
			val repo = ApprovalAuditRepository(db)
			repo.asked(
				conversationId = "c1",
				messageId = "m1",
				callId = "call-1",
				toolName = "write_file",
				path = "a.txt",
				sources = setOf(PolicySource.Global),
				toolAsked = false,
				at = 100,
			)

			db.conversations().delete("c1")

			assertTrue(repo.forConversation("c1").isEmpty())
		} finally {
			db.close()
		}
	}
}