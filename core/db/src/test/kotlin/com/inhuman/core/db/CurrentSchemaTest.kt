package com.inhuman.core.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.data.db.InhumanDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opens the current schema (v17) on the JVM.
 *
 * The schema, entities and DAOs live in this module now; both `:core:data` and
 * `:desktopApp` depend on it. The test is the JVM counterpart of the Android
 * migration instrumentation tests.
 */
class CurrentSchemaTest {

	@Test
	fun `current v17 schema opens and queries on the JVM`() = runBlocking {
		val db = Room.inMemoryDatabaseBuilder<InhumanDatabase>()
			.setDriver(BundledSQLiteDriver())
			.build()
		try {
			assertTrue(db.workspaces().all().isEmpty())
			assertTrue(db.approvals().count("none") == 0)
			assertTrue(db.savedPrompts().all().isEmpty())
			assertTrue(db.messages().all("none").isEmpty())
		} finally {
			db.close()
		}
	}
}
