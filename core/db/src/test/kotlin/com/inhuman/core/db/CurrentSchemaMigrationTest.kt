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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A real v12 -> v13 migration on the JVM.
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
	fun `v12 database migrates to v13 and exposes context summaries`() = runBlocking {
		assertTrue("missing exported v12 schema: ${schemaFile.absolutePath}", schemaFile.isFile)
		val dbFile = tmp.newFile("v12.db")
		createV12Database(dbFile)

		val db = Room.databaseBuilder<InhumanDatabase>(dbFile.absolutePath)
			.setDriver(BundledSQLiteDriver())
			.addMigrations(
				InhumanDatabase.MIGRATION_1_2,
				InhumanDatabase.MIGRATION_2_3,
				InhumanDatabase.MIGRATION_3_4,
				InhumanDatabase.MIGRATION_4_5,
				InhumanDatabase.MIGRATION_5_6,
				InhumanDatabase.MIGRATION_6_7,
				InhumanDatabase.MIGRATION_7_8,
				InhumanDatabase.MIGRATION_8_9,
				InhumanDatabase.MIGRATION_9_10,
				InhumanDatabase.MIGRATION_10_11,
				InhumanDatabase.MIGRATION_11_12,
				InhumanDatabase.MIGRATION_12_13,
			)
			.build()
		try {
			// This table only exists at v13; reading it also makes Room open the
			// database and run the migration chain.
			assertTrue(db.contextSummaries().all().isEmpty())
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
			connection.execSQL("PRAGMA user_version = 12")
		} finally {
			connection.close()
		}
	}
}
