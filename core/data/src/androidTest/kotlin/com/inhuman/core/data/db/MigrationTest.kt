package com.inhuman.core.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every migration, run against a real database.
 *
 * ## Why this file exists
 *
 * The tree had **seven handwritten migrations and no test for any of them** --
 * no `MigrationTestHelper`, no `room-testing`, no `androidTest` source set in
 * `:core:data`. The only check was a device script, so every migration was
 * verified by shipping it.
 *
 * ## Why the starting database is built from DDL, not from the exported schema
 *
 * `MigrationTestHelper.createDatabase(name, version)` is the usual way to get a
 * database at an old version, and it is wrong here: it builds from the exported
 * JSON, and **`1.json` is not a v1 schema**. It already declares
 * `toolCallsJson`, `toolCallId` and `toolName` -- the three columns
 * `MIGRATION_1_2` adds -- so `createDatabase(1)` produced a database in which
 * the first migration failed with `duplicate column name: toolCallsJson`.
 *
 * That is a real defect in the artefact, not in the test, and it is recorded in
 * `docs/07-gotchas.md` B8 rather than papered over. A test that cannot start is
 * worse than no test, because it looks like coverage.
 *
 * So the versions are built from the DDL the migrations themselves assume.
 * Those are the authoritative statement of what each version looks like: a
 * migration is what *produces* a version, so where the two disagree the
 * migration is right and the JSON is the thing that drifted.
 *
 * ## What each test checks
 *
 * 1. **The migrations run** without error.
 * 2. **The columns they claim to add are there.**
 * 3. **A row survives.** A schema check alone would pass if a migration dropped
 *    and recreated a table, which is the data loss this file is about.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

	@get:Rule
	val helper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		InhumanDatabase::class.java,
	)

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

	private val allMigrations = arrayOf(
		InhumanDatabase.MIGRATION_1_2,
		InhumanDatabase.MIGRATION_2_3,
		InhumanDatabase.MIGRATION_3_4,
		InhumanDatabase.MIGRATION_4_5,
		InhumanDatabase.MIGRATION_5_6,
		InhumanDatabase.MIGRATION_6_7,
		InhumanDatabase.MIGRATION_7_8,
	)

	// --- building a database at an old version -------------------------------

	/**
	 * The tables as of [version], transcribed from the migrations.
	 *
	 * `conversations` gains `agentId`, `reasoningEffort` and `thinking` in 3 -> 4
	 * and `toolsJson` in 6 -> 7; `messages` gains three tool columns in 1 -> 2,
	 * two latency columns in 2 -> 3, and `attachmentsJson` plus `mutated` in
	 * 4 -> 5; `agents` arrives in 3 -> 4; the three policy tables in 7 -> 8.
	 */
	private fun ddlFor(version: Int): List<String> {
		val sql = mutableListOf(
			"""
			CREATE TABLE IF NOT EXISTS conversations (
				id TEXT NOT NULL PRIMARY KEY,
				title TEXT NOT NULL,
				workspaceId TEXT NOT NULL,
				model TEXT,
				createdAt INTEGER NOT NULL,
				updatedAt INTEGER NOT NULL,
				pinned INTEGER NOT NULL
			)
			""".trimIndent(),
			"""
			CREATE TABLE IF NOT EXISTS messages (
				id TEXT NOT NULL PRIMARY KEY,
				conversationId TEXT NOT NULL,
				parentId TEXT,
				role TEXT NOT NULL,
				content TEXT NOT NULL,
				reasoning TEXT,
				model TEXT,
				finishReason TEXT,
				createdAt INTEGER NOT NULL,
				promptTokens INTEGER,
				completionTokens INTEGER,
				cachedPromptTokens INTEGER,
				reasoningTokens INTEGER,
				selectedChildId TEXT,
				error TEXT,
				FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE
			)
			""".trimIndent(),
			"CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_workspaceId ON conversations(workspaceId)",
			"CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages(conversationId)",
			"CREATE INDEX IF NOT EXISTS index_messages_parentId ON messages(parentId)",
		)

		if (version >= 2) {
			sql += "ALTER TABLE messages ADD COLUMN toolCallsJson TEXT"
			sql += "ALTER TABLE messages ADD COLUMN toolCallId TEXT"
			sql += "ALTER TABLE messages ADD COLUMN toolName TEXT"
		}
		if (version >= 3) {
			sql += "ALTER TABLE messages ADD COLUMN timeToFirstTokenMs INTEGER"
			sql += "ALTER TABLE messages ADD COLUMN generationMs INTEGER"
		}
		if (version >= 4) {
			sql += "ALTER TABLE conversations ADD COLUMN agentId TEXT"
			sql += "ALTER TABLE conversations ADD COLUMN reasoningEffort TEXT"
			sql += "ALTER TABLE conversations ADD COLUMN thinking INTEGER NOT NULL DEFAULT 0"
			sql += "CREATE INDEX IF NOT EXISTS index_conversations_agentId ON conversations(agentId)"
			sql += """
				CREATE TABLE IF NOT EXISTS agents (
					id TEXT NOT NULL PRIMARY KEY,
					name TEXT NOT NULL,
					systemPrompt TEXT NOT NULL,
					model TEXT,
					reasoningEffort TEXT,
					thinking INTEGER NOT NULL DEFAULT 0,
					createdAt INTEGER NOT NULL,
					updatedAt INTEGER NOT NULL,
					isDefault INTEGER NOT NULL DEFAULT 0
				)
			""".trimIndent()
			// The migration seeds it, so an upgraded install has a default.
			sql += "INSERT INTO agents (id,name,systemPrompt,thinking,createdAt,updatedAt,isDefault) " +
				"VALUES ('default-agent','Assistant','You are a helpful assistant.',0,0,0,1)"
		}
		if (version >= 5) {
			sql += "ALTER TABLE messages ADD COLUMN attachmentsJson TEXT"
		}
		// `mutated` is 5 -> 6, not 4 -> 5. Putting it one version early made
		// MIGRATION_5_6 fail with "duplicate column name: mutated", which is the
		// same class of mistake as hardcoding the v8 column list, one version
		// over.
		if (version >= 6) {
			sql += "ALTER TABLE messages ADD COLUMN mutated INTEGER NOT NULL DEFAULT 0"
		}
		if (version >= 7) {
			sql += "ALTER TABLE agents ADD COLUMN toolsJson TEXT NOT NULL DEFAULT ''"
			sql += "ALTER TABLE conversations ADD COLUMN toolsJson TEXT NOT NULL DEFAULT ''"
		}
		if (version >= 8) {
			sql += "CREATE TABLE IF NOT EXISTS approval_defaults (toolName TEXT NOT NULL PRIMARY KEY, policy TEXT NOT NULL)"
			sql += "CREATE TABLE IF NOT EXISTS agent_approval (agentId TEXT NOT NULL, toolName TEXT NOT NULL, policy TEXT NOT NULL, PRIMARY KEY(agentId, toolName))"
			sql += "CREATE TABLE IF NOT EXISTS path_policy (conversationId TEXT NOT NULL, relPath TEXT NOT NULL, policy TEXT NOT NULL, PRIMARY KEY(conversationId, relPath))"
		}
		return sql
	}

	/**
	 * A database at [version] holding one conversation and one message.
	 *
	 * Built with the framework's own `openOrCreateDatabase` rather than through
	 * Room, because Room is the thing being tested here and asking it to build
	 * the starting state would use the same schema metadata the migrations are
	 * supposed to be checked against.
	 */
	private fun seededDatabaseAt(version: Int): SupportSQLiteDatabase {
		val name = "seed-v$version.db"
		context.deleteDatabase(name)
		val db = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
			.create(
				androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
					.name(name)
					.callback(
						object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
							override fun onCreate(inner: SupportSQLiteDatabase) {
								ddlFor(version).forEach { inner.execSQL(it) }
							}

							override fun onUpgrade(inner: SupportSQLiteDatabase, old: Int, new: Int) = Unit
						},
					)
					.build(),
			)
			.writableDatabase

		db.execSQL(
			"INSERT INTO conversations (id,title,workspaceId,model,createdAt,updatedAt,pinned) " +
				"VALUES ('c1','canary','ws-canary',NULL,1700000000000,1700000000000,0)",
		)
		db.execSQL(
			"INSERT INTO messages (id,conversationId,parentId,role,content,createdAt) " +
				"VALUES ('m1','c1',NULL,'user','the needle that must survive',1700000000001)",
		)
		return db
	}

	/**
	 * Run [migrations] against a fresh database at [from], and hand the result
	 * to [block] as a plain connection.
	 *
	 * The starting file is built by [seededDatabaseAt] and then handed to the
	 * helper by name, so the migrations themselves are the real ones -- the
	 * objects the app installs.
	 */
	private fun migrate(from: Int, to: Int, vararg migrations: androidx.room.migration.Migration): SupportSQLiteDatabase {
		val name = "seed-v$from.db"
		seededDatabaseAt(from).close()
		// Copy the seeded file to the name the helper will open, then let Room
		// run the chain. `runMigrationsAndValidate` also checks the result
		// against the exported schema, which is the check that catches a
		// forgotten column.
		val seeded = context.getDatabasePath(name)
		val target = context.getDatabasePath(TEST_DB)
		context.deleteDatabase(TEST_DB)
		seeded.copyTo(target, overwrite = true)
		return helper.runMigrationsAndValidate(TEST_DB, to, true, *migrations)
	}

	// --- assertions ----------------------------------------------------------

	private fun SupportSQLiteDatabase.columnNames(table: String): Set<String> {
		val names = mutableSetOf<String>()
		query("PRAGMA table_info($table)").use { c ->
			while (c.moveToNext()) names += c.getString(1)
		}
		return names
	}

	private fun SupportSQLiteDatabase.countOf(table: String): Long =
		query("SELECT COUNT(*) FROM $table").use { c ->
			assertTrue("$table is not readable", c.moveToFirst())
			c.getLong(0)
		}

	private fun SupportSQLiteDatabase.assertCanarySurvived(version: Int) {
		query("SELECT content FROM messages WHERE id = 'm1'").use { c ->
			assertTrue("the seeded message is gone after migrating to v$version", c.moveToFirst())
			assertEquals(
				"the seeded message changed after migrating to v$version",
				"the needle that must survive",
				c.getString(0),
			)
		}
		query("SELECT title FROM conversations WHERE id = 'c1'").use { c ->
			assertTrue("the seeded conversation is gone after migrating to v$version", c.moveToFirst())
			assertEquals("canary", c.getString(0))
		}
	}

	// --- tests ---------------------------------------------------------------

	/**
	 * Whether `1.json` is now a real v1.
	 *
	 * This is the experiment, not just a test. `createDatabase(1)` builds from
	 * the exported schema, and the export used to declare the three columns
	 * `MIGRATION_1_2` adds -- so this could not even start, failing with
	 * `duplicate column name: toolCallsJson`. Those columns have been removed
	 * from `1.json`.
	 *
	 * What is *not* known is whether `MigrationTestHelper` also verifies the
	 * `identityHash` in that file. Room computes it and it cannot be hand-written
	 * reliably, so if this test passes, the file is usable and only the hash is
	 * stale; if it fails on a hash mismatch, the whole export needs regenerating
	 * from historical entity classes and this test records exactly that.
	 *
	 * It is deliberately separate from the tests below, which build their own
	 * DDL: those must keep working either way, and this one is allowed to be the
	 * thing that reports the answer.
	 */
	@Test
	fun `the exported v1 schema can build a database`() {
		val db = helper.createDatabase("schema-check.db", 1)
		val columns = db.columnNames("messages")
		for (absent in listOf("toolCallsJson", "toolCallId", "toolName")) {
			assertTrue(
				"$absent belongs to v2 but the v1 export declares it, so 1 -> 2 cannot run",
				absent !in columns,
			)
		}
		db.close()
	}

	@Test
	fun `1 to 2 adds the tool-call columns`() {
		migrate(1, 2, InhumanDatabase.MIGRATION_1_2).use { db ->
			val columns = db.columnNames("messages")
			for (name in listOf("toolCallsJson", "toolCallId", "toolName")) {
				assertTrue("$name is missing after 1 -> 2", name in columns)
			}
			db.assertCanarySurvived(2)
		}
	}

	@Test
	fun `2 to 3 adds the latency columns`() {
		migrate(2, 3, InhumanDatabase.MIGRATION_1_2, InhumanDatabase.MIGRATION_2_3).use { db ->
			val columns = db.columnNames("messages")
			for (name in listOf("timeToFirstTokenMs", "generationMs")) {
				assertTrue("$name is missing after 2 -> 3", name in columns)
			}
			db.assertCanarySurvived(3)
		}
	}

	@Test
	fun `3 to 4 creates the assistants table and seeds a default`() {
		migrate(
			3, 4,
			InhumanDatabase.MIGRATION_1_2,
			InhumanDatabase.MIGRATION_2_3,
			InhumanDatabase.MIGRATION_3_4,
		).use { db ->
			val columns = db.columnNames("conversations")
			for (name in listOf("agentId", "reasoningEffort", "thinking")) {
				assertTrue("$name is missing after 3 -> 4", name in columns)
			}
			// The seeding is the part of this migration that was found by
			// checking a real device after upgrading rather than by reading the
			// code: without it an existing install arrives with an empty
			// assistants table and a picker with nothing in it.
			assertEquals(
				"no default assistant was seeded by 3 -> 4",
				1L,
				db.countOf("agents WHERE isDefault = 1"),
			)
			db.assertCanarySurvived(4)
		}
	}

	@Test
	fun `4 to 5 adds the attachment column`() {
		migrate(
			4, 5,
			InhumanDatabase.MIGRATION_1_2,
			InhumanDatabase.MIGRATION_2_3,
			InhumanDatabase.MIGRATION_3_4,
			InhumanDatabase.MIGRATION_4_5,
		).use { db ->
			assertTrue(
				"attachmentsJson is missing after 4 -> 5",
				"attachmentsJson" in db.columnNames("messages"),
			)
			db.assertCanarySurvived(5)
		}
	}

	@Test
	fun `5 to 6 adds the mutation column`() {
		migrate(
			5, 6,
			InhumanDatabase.MIGRATION_1_2,
			InhumanDatabase.MIGRATION_2_3,
			InhumanDatabase.MIGRATION_3_4,
			InhumanDatabase.MIGRATION_4_5,
			InhumanDatabase.MIGRATION_5_6,
		).use { db ->
			assertTrue(
				"mutated is missing after 5 -> 6",
				"mutated" in db.columnNames("messages"),
			)
			// Defaulted to 0 on purpose: marking old replies as mutating would
			// disable regeneration on every one of them, including the
			// text-only ones. The opposite default removes a working feature
			// for everybody.
			db.query("SELECT mutated FROM messages WHERE id = 'm1'").use { c ->
				assertTrue(c.moveToFirst())
				assertEquals("an existing reply was marked as mutating", 0L, c.getLong(0))
			}
			db.assertCanarySurvived(6)
		}
	}

	@Test
	fun `6 to 7 adds the tool-selection columns and keeps the rows`() {
		migrate(
			6, 7,
			InhumanDatabase.MIGRATION_1_2,
			InhumanDatabase.MIGRATION_2_3,
			InhumanDatabase.MIGRATION_3_4,
			InhumanDatabase.MIGRATION_4_5,
			InhumanDatabase.MIGRATION_5_6,
			InhumanDatabase.MIGRATION_6_7,
		).use { db ->
			assertTrue("agents.toolsJson is missing", "toolsJson" in db.columnNames("agents"))
			assertTrue(
				"conversations.toolsJson is missing",
				"toolsJson" in db.columnNames("conversations"),
			)
			// `''` means "every tool", so an assistant that predates this column
			// keeps everything it had. Worth asserting rather than the column's
			// existence: the wrong default silently strips a user's tools.
			db.query("SELECT toolsJson FROM agents WHERE isDefault = 1").use { c ->
				assertTrue(c.moveToFirst())
				assertEquals("an upgraded assistant lost its tools", "", c.getString(0))
			}
			db.assertCanarySurvived(7)
		}
	}

	@Test
	fun `7 to 8 creates the three policy tables and touches nothing else`() {
		migrate(7, 8, *allMigrations).use { db ->
			for (table in listOf("approval_defaults", "agent_approval", "path_policy")) {
				// Created empty is both the correct migration and the correct
				// product default: absence of a rule means no restriction.
				assertEquals("$table should start empty", 0L, db.countOf(table))
			}
			db.assertCanarySurvived(8)
		}
	}

	@Test
	fun `a policy rule can be written and read back`() {
		seededDatabaseAt(8).use { db ->
			db.execSQL(
				"INSERT INTO approval_defaults (toolName, policy) " +
					"VALUES ('delete_file', 'Deny')",
			)
			db.query("SELECT policy FROM approval_defaults WHERE toolName = 'delete_file'")
				.use { c ->
					assertTrue(c.moveToFirst())
					assertEquals("Deny", c.getString(0))
				}
		}
	}

	private companion object {
		const val TEST_DB = "migration-test.db"
	}
}
