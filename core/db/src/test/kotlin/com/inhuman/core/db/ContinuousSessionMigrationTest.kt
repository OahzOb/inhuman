package com.inhuman.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.inhuman.core.data.db.InhumanDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 22 -> 23 migration, without Room.
 *
 * Additive columns are easy to get subtly wrong: a default that is not the one
 * the entity declares, a nullable coverage column written as 0, or a new table
 * whose index name does not match what Room expects. The migration is the
 * whole change, so a minimal pair of tables and two old rows exercise it; the
 * assertions below are the contract from the design proposal.
 */
class ContinuousSessionMigrationTest {

	@Test
	fun `a v22 database gains continuous-session storage`() {
		val connection = BundledSQLiteDriver().open(":memory:")
		try {
			// The columns the migration touches are the only ones that matter
			// here. A full historical schema would add noise without testing
			// anything else: every statement is an ALTER or a CREATE.
			connection.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY)")
			connection.execSQL("CREATE TABLE context_summaries (conversationId TEXT NOT NULL PRIMARY KEY)")
			connection.execSQL("INSERT INTO conversations (id) VALUES ('c1')")
			connection.execSQL("INSERT INTO context_summaries (conversationId) VALUES ('c1')")

			InhumanDatabase.MIGRATION_22_23.migrate(connection)

			// An upgraded conversation keeps today's behaviour: no automatic
			// maintenance, no extra requests.
			assertEquals(
				"classic",
				text(connection, "SELECT sessionMode FROM conversations WHERE id = 'c1'"),
			)
			assertEquals(
				"manual",
				text(connection, "SELECT kind FROM context_summaries WHERE conversationId = 'c1'"),
			)
			// A manual summary written before these columns has no recorded
			// coverage; 0 would be a claim it cannot support.
			assertEquals(1, number(connection, "SELECT coveredTurns IS NULL FROM context_summaries"))
			assertEquals(1, number(connection, "SELECT coveredMessages IS NULL FROM context_summaries"))
			assertEquals(1, number(connection, "SELECT summaryTokens IS NULL FROM context_summaries"))
			assertEquals(0, number(connection, "SELECT truncated FROM context_summaries"))

			assertEquals(
				1,
				number(
					connection,
					"SELECT COUNT(*) FROM sqlite_master " +
						"WHERE type = 'table' AND name = 'maintenance_runs'",
				),
			)
			assertEquals(
				1,
				number(
					connection,
					"SELECT COUNT(*) FROM sqlite_master " +
						"WHERE type = 'index' AND name = 'index_maintenance_runs_conversationId_at'",
				),
			)

			// The created table accepts the row shape the entity will write.
			connection.execSQL(
				"INSERT INTO maintenance_runs " +
					"(id, conversationId, at, kind, model, inputTokens, outputTokens, " +
					"omittedMessages, omittedTurns, outcome, reason) " +
					"VALUES ('run-1', 'c1', 10, 'auto', 'demo-model', 100, 20, 4, 2, 'ok', NULL)",
			)
			assertEquals(1, number(connection, "SELECT COUNT(*) FROM maintenance_runs"))
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

	private fun number(connection: SQLiteConnection, sql: String): Long {
		val statement = connection.prepare(sql)
		try {
			return if (statement.step()) statement.getLong(0) else -1
		} finally {
			statement.close()
		}
	}
}
