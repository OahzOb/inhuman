package com.inhuman.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.inhuman.core.data.db.InhumanDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 23 -> 24 migration for role-play cards and scenes.
 *
 * Both columns are additive text with an empty default, and the default is the
 * contract: an upgraded install must not turn an existing assistant into an
 * in-character one, and an existing conversation must not gain a scene. The
 * test writes a row of each kind and reads the new columns back, which is all
 * the migration does.
 */
class RoleplayMigrationTest {

	@Test
	fun `a v23 database gains role-play columns with empty defaults`() {
		val connection = BundledSQLiteDriver().open(":memory:")
		try {
			// Only the tables the migration touches, and only the columns the
			// ALTER statements need to coexist with.
			connection.execSQL("CREATE TABLE agents (id TEXT NOT NULL PRIMARY KEY)")
			connection.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY)")
			connection.execSQL("INSERT INTO agents (id) VALUES ('a1')")
			connection.execSQL("INSERT INTO conversations (id) VALUES ('c1')")

			InhumanDatabase.MIGRATION_23_24.migrate(connection)

			assertEquals("", text(connection, "SELECT rpJson FROM agents WHERE id = 'a1'"))
			assertEquals("", text(connection, "SELECT sceneJson FROM conversations WHERE id = 'c1'"))
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
