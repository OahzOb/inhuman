package com.inhuman.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.inhuman.core.data.db.InhumanDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 26 -> 27 migration for the frozen role-play persona.
 *
 * The empty default is the contract: it means "not pinned yet". An old scene
 * must keep reading the live assistant on its next turn and pin then, and an
 * ordinary conversation must never gain a persona snapshot. A column default
 * that claimed a snapshot would freeze every scene against whatever the
 * assistant happened to be at upgrade time without the app writing it.
 */
class RoleplaySnapshotMigrationTest {

	@Test
	fun `a v26 database gains rpSnapshotJson with an empty default`() {
		val connection = BundledSQLiteDriver().open(":memory:")
		try {
			connection.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY)")
			connection.execSQL("INSERT INTO conversations (id) VALUES ('c1')")

			InhumanDatabase.MIGRATION_26_27.migrate(connection)

			assertEquals(
				"",
				text(connection, "SELECT rpSnapshotJson FROM conversations WHERE id = 'c1'"),
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
