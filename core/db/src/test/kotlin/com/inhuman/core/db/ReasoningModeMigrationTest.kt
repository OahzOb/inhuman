package com.inhuman.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.inhuman.core.data.db.InhumanDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 19 -> 20 data migration, without Room.
 *
 * This one changes rows rather than columns, so the risk is different from the
 * additive migrations: getting the predicate wrong silently leaves an OpenCode
 * profile on `none`, and every effort chip continues to do nothing. The SQL is
 * the whole migration, so a minimal table and four rows exercise it.
 */
class ReasoningModeMigrationTest {

	@Test
	fun `opencode profiles move to the opencode reasoning mode`() {
		val connection = BundledSQLiteDriver().open(":memory:")
		try {
			connection.execSQL(
				"CREATE TABLE provider_profiles (" +
					"id TEXT NOT NULL PRIMARY KEY, " +
					"baseUrl TEXT NOT NULL, " +
					"reasoningMode TEXT NOT NULL)",
			)
			insert(connection, "go", "https://opencode.ai/zen/go/v1", "none")
			// A trailing slash is a different string and the same endpoint.
			insert(connection, "zen", "https://opencode.ai/zen/v1/", "none")
			insert(connection, "deepseek", "https://api.deepseek.com", "none")
			// An explicit mode on an OpenCode URL is a choice, not the old
			// default, and must survive.
			insert(connection, "kept", "https://opencode.ai/zen/go/v1", "deepseek")

			InhumanDatabase.MIGRATION_19_20.migrate(connection)

			assertEquals(
				mapOf(
					"go" to "opencode",
					"zen" to "opencode",
					"deepseek" to "none",
					"kept" to "deepseek",
				),
				modes(connection),
			)
		} finally {
			connection.close()
		}
	}

	private fun insert(
		connection: SQLiteConnection,
		id: String,
		baseUrl: String,
		mode: String,
	) {
		connection.execSQL(
			"INSERT INTO provider_profiles (id, baseUrl, reasoningMode) " +
				"VALUES ('$id', '$baseUrl', '$mode')",
		)
	}

	private fun modes(connection: SQLiteConnection): Map<String, String> {
		val result = mutableMapOf<String, String>()
		val statement = connection.prepare("SELECT id, reasoningMode FROM provider_profiles")
		try {
			while (statement.step()) {
				result[statement.getText(0)] = statement.getText(1)
			}
		} finally {
			statement.close()
		}
		return result
	}
}
