package com.inhuman.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.inhuman.core.data.db.InhumanDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 29 -> 30 migration for the local page-fetch consent.
 *
 * The default is the contract: a conversation written before this release has
 * never been asked for this capability, and "not answered" must not become
 * "yes". A wrong default (1) would hand every existing conversation a free
 * outbound URL path the user never acknowledged.
 */
class LocalFetchConsentMigrationTest {

	@Test
	fun `a v29 database gains localFetchAllowed with a false default`() {
		val connection = BundledSQLiteDriver().open(":memory:")
		try {
			// Only the table the migration touches, and only the columns the
			// ALTER statement needs to coexist with.
			connection.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY)")
			connection.execSQL("INSERT INTO conversations (id) VALUES ('c1')")

			InhumanDatabase.MIGRATION_29_30.migrate(connection)

			assertEquals(
				"0",
				text(connection, "SELECT localFetchAllowed FROM conversations WHERE id = 'c1'"),
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
