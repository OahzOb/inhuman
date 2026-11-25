package com.inhuman.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.inhuman.core.data.db.InhumanDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 25 -> 26 migration for the in-turn user message marker.
 *
 * The default is the contract: a transcript written before this release has no
 * insertion, so every existing user row stays a turn root. A wrong default (1)
 * would make every old user message look like it belonged to the previous turn,
 * which would move Rewind's start point one conversation backwards.
 */
class InjectedMessageMigrationTest {

	@Test
	fun `a v25 database gains the injected marker with a false default`() {
		val connection = BundledSQLiteDriver().open(":memory:")
		try {
			// Only the table the migration touches, and only the columns the
			// ALTER statement needs to coexist with.
			connection.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY)")
			connection.execSQL("INSERT INTO messages (id) VALUES ('m1')")

			InhumanDatabase.MIGRATION_25_26.migrate(connection)

			assertEquals(
				"0",
				text(connection, "SELECT injected FROM messages WHERE id = 'm1'"),
			)
		} finally {
			connection.close()
		}
	}

	private fun text(connection: SQLiteConnection, sql: String): String? {
		val statement = connection.prepare(sql)
		try {
			return if (statement.step()) statement.getText(0) else null
		} finally {
			statement.close()
		}
	}
}
