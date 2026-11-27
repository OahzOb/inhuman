package com.inhuman.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.inhuman.core.data.db.InhumanDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 27 -> 28 migration for the workspace kind.
 *
 * The empty default is the contract: every workspace an upgrade already has
 * stays an ordinary one. A default that promoted some workspace to the
 * role-play workshop would give an install a built-in surface it never asked
 * for, and the app's lazy creation would then find a workshop row it did not
 * write.
 */
class WorkshopKindMigrationTest {

	@Test
	fun `a v27 database gains kind with an empty default`() {
		val connection = BundledSQLiteDriver().open(":memory:")
		try {
			connection.execSQL("CREATE TABLE workspaces (id TEXT NOT NULL PRIMARY KEY)")
			connection.execSQL("INSERT INTO workspaces (id) VALUES ('w1')")

			InhumanDatabase.MIGRATION_27_28.migrate(connection)

			assertEquals(
				"",
				text(connection, "SELECT kind FROM workspaces WHERE id = 'w1'"),
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
