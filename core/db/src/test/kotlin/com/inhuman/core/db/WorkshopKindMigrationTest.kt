package com.inhuman.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.inhuman.core.data.db.InhumanDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 27 -> 28 and 28 -> 29 migrations for the workspace kind.
 *
 * 27 -> 28: the empty default is the contract; every workspace an upgrade
 * already has stays an ordinary one, and no row is promoted to a built-in
 * surface it never asked for.
 *
 * 28 -> 29: the two built-in workshops become one. The role-play row survives
 * when both exist, because scene conversations are bound to it; the
 * configuration row is demoted to an ordinary workspace, keeping its files and
 * conversations. No row is deleted and nothing is moved.
 */
class WorkshopKindMigrationTest {

	@Test
	fun `a v27 database gains kind with an empty default`() {
		val connection = BundledSQLiteDriver().open(":memory:")
		try {
			// v27 has no kind column at all; this migration is the one that
			// adds it.
			connection.execSQL("CREATE TABLE workspaces (id TEXT NOT NULL PRIMARY KEY)")
			connection.execSQL("INSERT INTO workspaces (id) VALUES ('w1')")

			InhumanDatabase.MIGRATION_27_28.migrate(connection)

			assertEquals("", text(connection, "SELECT kind FROM workspaces WHERE id = 'w1'"))
		} finally {
			connection.close()
		}
	}

	@Test
	fun `v29 promotes a lone role-play workshop`() {
		withKindTable { connection ->
			connection.execSQL("INSERT INTO workspaces (id, kind) VALUES ('rp', 'roleplay')")
			connection.execSQL("INSERT INTO workspaces (id, kind) VALUES ('notes', '')")

			InhumanDatabase.MIGRATION_28_29.migrate(connection)

			assertEquals("workshop", kindOf(connection, "rp"))
			assertEquals("", kindOf(connection, "notes"))
		}
	}

	@Test
	fun `v29 promotes a lone configuration workshop`() {
		withKindTable { connection ->
			connection.execSQL("INSERT INTO workspaces (id, kind) VALUES ('studio', 'studio')")

			InhumanDatabase.MIGRATION_28_29.migrate(connection)

			assertEquals("workshop", kindOf(connection, "studio"))
		}
	}

	@Test
	fun `v29 keeps the role-play row and demotes the configuration row`() {
		withKindTable { connection ->
			// Inserted configuration first so the result cannot depend on row
			// order.
			connection.execSQL("INSERT INTO workspaces (id, kind) VALUES ('studio', 'studio')")
			connection.execSQL("INSERT INTO workspaces (id, kind) VALUES ('rp', 'roleplay')")

			InhumanDatabase.MIGRATION_28_29.migrate(connection)

			assertEquals("workshop", kindOf(connection, "rp"))
			assertEquals("", kindOf(connection, "studio"))
			assertEquals(2, count(connection, "SELECT COUNT(*) FROM workspaces"))
		}
	}

	@Test
	fun `v29 is a no-op when there is nothing to merge`() {
		withKindTable { connection ->
			connection.execSQL("INSERT INTO workspaces (id, kind) VALUES ('notes', '')")

			InhumanDatabase.MIGRATION_28_29.migrate(connection)

			assertEquals("", kindOf(connection, "notes"))
		}
	}

	private fun withKindTable(block: (SQLiteConnection) -> Unit) {
		val connection = BundledSQLiteDriver().open(":memory:")
		try {
			connection.execSQL(
				"CREATE TABLE workspaces (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL DEFAULT '')",
			)
			block(connection)
		} finally {
			connection.close()
		}
	}

	private fun kindOf(connection: SQLiteConnection, id: String): String? =
		text(connection, "SELECT kind FROM workspaces WHERE id = '$id'")

	private fun count(connection: SQLiteConnection, sql: String): Int {
		val statement = connection.prepare(sql)
		try {
			return if (statement.step()) statement.getInt(0) else 0
		} finally {
			statement.close()
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
