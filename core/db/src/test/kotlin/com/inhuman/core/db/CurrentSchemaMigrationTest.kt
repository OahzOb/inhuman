package com.inhuman.core.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.inhuman.core.data.db.InhumanDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A real v12 -> current migration on the JVM.
 *
 * `MigrationTestHelper` is Android-test oriented, so this builds the v12 schema
 * from Room's own exported JSON (`core/db/schemas/.../12.json`), opens it with
 * the current `InhumanDatabase` and the production migration list, and queries a
 * v13-only table. If the migration is missing or wrong, Room throws before the
 * query.
 */
class CurrentSchemaMigrationTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private val schemaFile = File("schemas/com.inhuman.core.data.db.InhumanDatabase/12.json")

	@Test
	fun `a v12 database migrates to the current schema`() = runBlocking {
		assertTrue("missing exported v12 schema: ${schemaFile.absolutePath}", schemaFile.isFile)
		val dbFile = tmp.newFile("v12.db")
		createV12Database(dbFile)

		val db = Room.databaseBuilder<InhumanDatabase>(dbFile.absolutePath)
			.setDriver(BundledSQLiteDriver())
			.addMigrations(*InhumanDatabase.MIGRATIONS)

			.build()
		try {
			// This table only exists at v13; reading it also makes Room open the
			// database and run the migration chain.
			assertTrue(db.contextSummaries().all().isEmpty())
			// v14 added `modelTags` to the provider row; reading it proves the
			// column exists and that an upgrade defaults it to empty.
			assertTrue(db.providers().all().all { it.modelTags.isEmpty() })
			// v15 added the approval audit; this both proves the table exists
			// and that an upgraded install starts with no rows.
			assertTrue(db.approvals().count("none") == 0)
			// v16 added the saved-prompt library; an upgraded install has none.
			assertTrue(db.savedPrompts().all().isEmpty())
			// v17 added messages.reasoningSignature; the column exists and old
			// rows are readable with null.
			assertTrue(db.messages().all("none").isEmpty())
			// v18 added messages.providerState; same additive shape.
			assertTrue(db.messages().all("none").isEmpty())
			// v23 added continuous-session storage: the upgraded conversation
			// keeps today's behaviour, and the cost ledger starts empty.
			val canary = db.conversations().byId("c1")
			assertTrue("the v12 canary conversation is missing", canary != null)
			assertEquals("classic", canary?.sessionMode)
			assertTrue(db.maintenanceRuns().all().isEmpty())
			// v24 added the per-conversation URL download acknowledgement; an
			// upgraded conversation starts unallowed, which is the safe default.
			assertEquals(false, canary?.downloadAllowed)
		} finally {
			db.close()
		}
	}

	private fun createV12Database(file: File) {
		val database = Json.parseToJsonElement(schemaFile.readText())
			.jsonObject.getValue("database").jsonObject
		val entities = database.getValue("entities").jsonArray
		val setupQueries = database.getValue("setupQueries").jsonArray
		val driver = BundledSQLiteDriver()
		val connection = driver.open(file.absolutePath)
		try {
			for (entity in entities) {
				val table = entity.jsonObject
				val name = table.getValue("tableName").jsonPrimitive.content
				val create = table.getValue("createSql").jsonPrimitive.content
					.replace("\${TABLE_NAME}", name)
				connection.execSQL(create)
				for (index in table["indices"]?.jsonArray.orEmpty()) {
					connection.execSQL(
						index.jsonObject.getValue("createSql").jsonPrimitive.content
							.replace("\${TABLE_NAME}", name),
					)
				}
			}
			for (query in setupQueries) {
				connection.execSQL(query.jsonPrimitive.content)
			}
			// One conversation, so the migration has an upgraded row to assert
			// the new defaults on. Only the NOT NULL columns without a default
			// are supplied; the rest arrive through the v11/v12 defaults.
			connection.execSQL(
				"INSERT INTO conversations " +
					"(id, title, workspaceId, thinking, createdAt, updatedAt, pinned) " +
					"VALUES ('c1', 'canary', 'ws-canary', 0, 1700000000000, 1700000000000, 0)",
			)
			connection.execSQL("PRAGMA user_version = 12")
		} finally {
			connection.close()
		}
	}
}
