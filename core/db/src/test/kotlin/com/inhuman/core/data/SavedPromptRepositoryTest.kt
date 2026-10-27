package com.inhuman.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.data.db.InhumanDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt library's two rules: one row per title, and the text is stored as
 * written.
 *
 * The update path is the interesting one: "save this improved version" under the
 * same name must replace the body, not add a near-duplicate beside it -- the
 * problem the library exists to solve.
 */
class SavedPromptRepositoryTest {

	private fun open(): InhumanDatabase = Room.inMemoryDatabaseBuilder<InhumanDatabase>()
		.setDriver(BundledSQLiteDriver())
		.build()

	@Test
	fun `saving a title creates a row and keeps the text verbatim`() = runBlocking {
		val db = open()
		try {
			val repo = SavedPromptRepository(db)
			val text = "  Review this diff.\n\n- be terse\n"

			val id = repo.save("  Review  ", text)

			assertNotNull(id)
			val row = repo.all().single()
			assertEquals("Review", row.title)
			assertEquals(text, row.text)
			assertEquals(row.createdAt, row.updatedAt)
		} finally {
			db.close()
		}
	}

	@Test
	fun `saving the same title updates rather than duplicating`() = runBlocking {
		val db = open()
		try {
			val repo = SavedPromptRepository(db)
			val first = repo.save("Review", "v1")
			val second = repo.save("Review", "v2")

			assertEquals(first, second)
			val row = repo.all().single()
			assertEquals("v2", row.text)
			assertTrue("updatedAt must move", row.updatedAt >= row.createdAt)
		} finally {
			db.close()
		}
	}

	@Test
	fun `different titles are different entries`() = runBlocking {
		val db = open()
		try {
			val repo = SavedPromptRepository(db)
			repo.save("Review", "a")
			repo.save("Translate", "b")

			assertEquals(setOf("Review", "Translate"), repo.all().map { it.title }.toSet())
		} finally {
			db.close()
		}
	}

	@Test
	fun `blank input is not saved`() = runBlocking {
		val db = open()
		try {
			val repo = SavedPromptRepository(db)

			assertNull(repo.save("   ", "text"))
			assertNull(repo.save("Title", "  \n "))
			assertTrue(repo.all().isEmpty())
		} finally {
			db.close()
		}
	}

	@Test
	fun `delete removes only the named entry`() = runBlocking {
		val db = open()
		try {
			val repo = SavedPromptRepository(db)
			val keep = repo.save("Keep", "a")!!
			val drop = repo.save("Drop", "b")!!

			repo.delete(drop)

			assertEquals(listOf(keep), repo.all().map { it.id })
		} finally {
			db.close()
		}
	}
}