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
		InhumanDatabase.MIGRATION_8_9,
		InhumanDatabase.MIGRATION_9_10,
		InhumanDatabase.MIGRATION_10_11,
		InhumanDatabase.MIGRATION_11_12,
		InhumanDatabase.MIGRATION_12_13,
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
		if (version >= 9) {
			sql += "ALTER TABLE conversations ADD COLUMN sshAllowed INTEGER NOT NULL DEFAULT 0"
		}
		if (version >= 10) {
			sql += "ALTER TABLE messages ADD COLUMN changedFilesJson TEXT"
		}
		if (version >= 11) {
			sql += """
				CREATE TABLE IF NOT EXISTS workspaces (
					id TEXT NOT NULL PRIMARY KEY,
					name TEXT NOT NULL,
					isPermanent INTEGER NOT NULL DEFAULT 0,
					ownerConversationId TEXT,
					createdAt INTEGER NOT NULL,
					updatedAt INTEGER NOT NULL
				)
			""".trimIndent()
			sql += "CREATE INDEX IF NOT EXISTS index_workspaces_isPermanent ON workspaces(isPermanent)"
			sql += "DROP INDEX IF EXISTS index_conversations_workspaceId"
			sql += "CREATE INDEX IF NOT EXISTS index_conversations_workspaceId ON conversations(workspaceId)"
		}
		if (version >= 12) {
			// The 11 -> 12 test builds its source at 11 and lets the migration
			// create these; a source built directly at 12 needs them already.
			sql += """
				CREATE TABLE IF NOT EXISTS provider_profiles (
					id TEXT NOT NULL PRIMARY KEY,
					name TEXT NOT NULL,
					kind TEXT NOT NULL,
					baseUrl TEXT NOT NULL,
					reasoningMode TEXT NOT NULL DEFAULT 'none',
					includeStreamUsage INTEGER NOT NULL DEFAULT 1,
					manualModels TEXT NOT NULL DEFAULT '',
					isBuiltIn INTEGER NOT NULL,
					createdAt INTEGER NOT NULL,
					updatedAt INTEGER NOT NULL
				)
			""".trimIndent()
			sql += "ALTER TABLE agents ADD COLUMN providerId TEXT NOT NULL DEFAULT 'deepseek'"
			sql += "ALTER TABLE conversations ADD COLUMN providerId TEXT NOT NULL DEFAULT 'deepseek'"
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
		val sqlite = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
			.create(
				androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
					.name(name)
					.callback(
						object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
							override fun onCreate(db: SupportSQLiteDatabase) {
								ddlFor(version).forEach { db.execSQL(it) }
							}

							override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
						},
					)
					.build(),
			)
			.writableDatabase

		sqlite.execSQL(
			"INSERT INTO conversations (id,title,workspaceId,model,createdAt,updatedAt,pinned) " +
				"VALUES ('c1','canary','ws-canary',NULL,1700000000000,1700000000000,0)",
		)
		sqlite.execSQL(
			"INSERT INTO messages (id,conversationId,parentId,role,content,createdAt) " +
				"VALUES ('m1','c1',NULL,'user','the needle that must survive',1700000000001)",
		)
		return sqlite
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
		migrate(7, 8, *allMigrations.take(7).toTypedArray()).use { db ->
			for (table in listOf("approval_defaults", "agent_approval", "path_policy")) {
				// Created empty is both the correct migration and the correct
				// product default: absence of a rule means no restriction.
				assertEquals("$table should start empty", 0L, db.countOf(table))
			}
			db.assertCanarySurvived(8)
		}
	}

	@Test
	fun `8 to 9 adds ssh consent and defaults it off`() {
		migrate(8, 9, *allMigrations.take(8).toTypedArray()).use { db ->
			assertTrue(
				"sshAllowed is missing after 8 -> 9",
				"sshAllowed" in db.columnNames("conversations"),
			)
			db.query("SELECT sshAllowed FROM conversations WHERE id = 'c1'").use { c ->
				assertTrue(c.moveToFirst())
				// An upgrade must not hand an old conversation a remote shell.
				assertEquals("an existing conversation was granted SSH", 0L, c.getLong(0))
			}
			db.assertCanarySurvived(9)
		}
	}

	@Test
	fun `9 to 10 adds the changed-files column`() {
		migrate(9, 10, *allMigrations.take(9).toTypedArray()).use { db ->
			assertTrue(
				"changedFilesJson is missing after 9 -> 10",
				"changedFilesJson" in db.columnNames("messages"),
			)
			db.query("SELECT changedFilesJson FROM messages WHERE id = 'm1'").use { c ->
				assertTrue(c.moveToFirst())
				assertTrue("an old message must not claim a file list", c.isNull(0))
			}
			db.assertCanarySurvived(10)
		}
	}

	@Test
	fun `10 to 11 creates a temporary workspace and drops the unique index`() {
		migrate(10, 11, *allMigrations.take(10).toTypedArray()).use { db ->
			db.query(
				"SELECT id, name, isPermanent, ownerConversationId FROM workspaces WHERE id = 'ws-canary'",
			).use { c ->
				assertTrue("the existing conversation's workspace is missing", c.moveToFirst())
				assertEquals("ws-canary", c.getString(0))
				assertEquals("an existing workspace must keep the conversation title", "canary", c.getString(1))
				assertEquals("an existing workspace must start temporary", 0L, c.getLong(2))
				assertEquals("the conversation must own its temporary workspace", "c1", c.getString(3))
			}
			// The old unique index is what made a workspace one-conversation-only.
			// A permanent workspace is shared; if this index survives, the second
			// conversation's insert fails.
			db.query("PRAGMA index_list('conversations')").use { c ->
				var found = false
				while (c.moveToNext()) {
					if (c.getString(1) == "index_conversations_workspaceId") {
						found = true
						assertEquals("workspaceId is still unique", 0L, c.getLong(2))
					}
				}
				assertTrue("the workspaceId index is missing", found)
			}
			db.assertCanarySurvived(11)
		}
	}

	@Test
	fun `11 to 12 seeds DeepSeek and points existing rows at it`() {
		migrate(11, 12, *allMigrations.take(11).toTypedArray()).use { db ->
			db.query(
				"SELECT name, kind, baseUrl, reasoningMode, isBuiltIn " +
					"FROM provider_profiles WHERE id = 'deepseek'",
			).use { c ->
				assertTrue("the DeepSeek provider was not seeded", c.moveToFirst())
				assertEquals("DeepSeek", c.getString(0))
				assertEquals("deepseek", c.getString(1))
				assertEquals("https://api.deepseek.com", c.getString(2))
				assertEquals("deepseek", c.getString(3))
				assertEquals(1L, c.getLong(4))
			}
			assertTrue(
				"agents.providerId is missing",
				"providerId" in db.columnNames("agents"),
			)
			assertTrue(
				"conversations.providerId is missing",
				"providerId" in db.columnNames("conversations"),
			)
			db.query("SELECT providerId FROM conversations WHERE id = 'c1'").use { c ->
				assertTrue(c.moveToFirst())
				assertEquals(
					"an existing conversation must keep working on DeepSeek",
					"deepseek",
					c.getString(0),
				)
			}
			db.assertCanarySurvived(12)
		}
	}

	@Test
	fun `12 to 13 adds the derived context summary table`() {
		migrate(12, 13, *allMigrations.take(12).toTypedArray()).use { db ->
			db.query(
				"SELECT name FROM sqlite_master WHERE type = 'table' " +
					"AND name = 'context_summaries'",
			).use { c ->
				assertTrue("context_summaries was not created", c.moveToFirst())
			}
			val columns = db.columnNames("context_summaries")
			assertTrue("summary is missing", "summary" in columns)
			assertTrue("throughMessageId is missing", "throughMessageId" in columns)
			db.assertCanarySurvived(13)
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
