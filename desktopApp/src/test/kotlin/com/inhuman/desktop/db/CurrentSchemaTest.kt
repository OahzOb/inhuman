package com.inhuman.desktop.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.data.db.InhumanDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opens the **current** `:core:data` schema (v13) on the JVM, using the exact
 * `Entities.kt` / `InhumanDatabase.kt` sources via the source-set bridge in
 * `desktopApp/build.gradle`.
 *
 * This is the spike's Room proof, not the production module layout: those files
 * still need to move into a shared module, and a real v12 -> v13 migration test
 * still needs `MigrationTestHelper` or an equivalent JVM harness.
 */
class CurrentSchemaTest {

	@Test
	fun `current v13 schema opens and queries on the JVM`() = runBlocking {
		val db = Room.inMemoryDatabaseBuilder<InhumanDatabase>()
			.setDriver(BundledSQLiteDriver())
			.build()
		try {
			assertTrue(db.workspaces().all().isEmpty())
		} finally {
			db.close()
		}
	}
}
