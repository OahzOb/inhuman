package com.inhuman.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {

	/** Permanent workspaces, for the Workspaces screen. */
	@Query("SELECT * FROM workspaces WHERE isPermanent = 1 ORDER BY updatedAt DESC, id ASC")
	fun observePermanent(): Flow<List<WorkspaceEntity>>

	@Query("SELECT * FROM workspaces ORDER BY updatedAt DESC, id ASC")
	fun observeAll(): Flow<List<WorkspaceEntity>>

	@Query("SELECT * FROM workspaces WHERE id = :id")
	suspend fun byId(id: String): WorkspaceEntity?

	@Query("SELECT * FROM workspaces WHERE id = :id")
	fun observe(id: String): Flow<WorkspaceEntity?>

	/** Every row, for the orphan sweep and the documents provider. */
	@Query("SELECT * FROM workspaces ORDER BY updatedAt DESC, id ASC")
	suspend fun all(): List<WorkspaceEntity>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(workspace: WorkspaceEntity)

	@Query(
		"UPDATE workspaces SET name = :name, isPermanent = 1, " +
			"ownerConversationId = NULL, updatedAt = :now WHERE id = :id",
	)
	suspend fun promote(id: String, name: String, now: Long)

	@Query("UPDATE workspaces SET name = :name, updatedAt = :now WHERE id = :id")
	suspend fun rename(id: String, name: String, now: Long)

	@Query("UPDATE workspaces SET updatedAt = :now WHERE id = :id")
	suspend fun touch(id: String, now: Long)

	@Query("DELETE FROM workspaces WHERE id = :id")
	suspend fun delete(id: String)

	@Query("SELECT COUNT(*) FROM conversations WHERE workspaceId = :id")
	suspend fun conversationCount(id: String): Int
}

@Dao
interface ProviderDao {

	@Query("SELECT * FROM provider_profiles ORDER BY isBuiltIn DESC, name COLLATE NOCASE ASC, id ASC")
	fun observeAll(): Flow<List<ProviderProfileEntity>>

	@Query("SELECT * FROM provider_profiles ORDER BY isBuiltIn DESC, name COLLATE NOCASE ASC, id ASC")
	suspend fun all(): List<ProviderProfileEntity>

	@Query("SELECT * FROM provider_profiles WHERE id = :id")
	suspend fun byId(id: String): ProviderProfileEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(provider: ProviderProfileEntity)

	@Query("DELETE FROM provider_profiles WHERE id = :id")
	suspend fun delete(id: String)

	@Query("SELECT COUNT(*) FROM agents WHERE providerId = :id")
	suspend fun agentCount(id: String): Int

	@Query("SELECT COUNT(*) FROM conversations WHERE providerId = :id")
	suspend fun conversationCount(id: String): Int
}

@Dao
interface ConversationDao {

	// `id` last, as a tie-break rather than for its own sake: two rows saved in
	// the same millisecond -- a bulk import, a migration, a scripted test --
	// compare equal on `updatedAt`, and SQLite is free to return them in either
	// order. An emission that reorders equal keys makes the list appear to
	// shuffle itself with nothing having changed. `SAID_ORDER` next to the
	// message queries exists for the same reason.
	@Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC, id ASC")
	fun observeAll(): Flow<List<ConversationEntity>>

	@Query("SELECT * FROM conversations WHERE id = :id")
	suspend fun byId(id: String): ConversationEntity?

	@Query("SELECT * FROM conversations WHERE workspaceId = :workspaceId ORDER BY pinned DESC, updatedAt DESC, id ASC")
	fun observeByWorkspace(workspaceId: String): Flow<List<ConversationEntity>>

	@Query("SELECT * FROM conversations WHERE workspaceId = :workspaceId ORDER BY pinned DESC, updatedAt DESC, id ASC")
	suspend fun byWorkspace(workspaceId: String): List<ConversationEntity>

	/**
	 * Look up by the id the documents provider publishes.
	 *
	 * A conversation row carries two UUIDs: `id`, the transcript's key, and
	 * `workspaceId`, the directory's name -- and the document id
	 * `WorkspaceDocumentsProvider` publishes for the folder. They are generated
	 * separately and are not interchangeable. The provider used [byId] for a
	 * document id, which matched nothing: a re-queried folder lost its title, and
	 * the delete/rename refusal never fired for the ids the provider itself
	 * issued.
	 *
	 * With a permanent workspace this lookup is not unique: several
	 * conversations can name the same workspace. The provider should resolve a
	 * document id through the workspaces table, not through the first
	 * conversation that happens to share it.
	 */
	@Query("SELECT * FROM conversations WHERE workspaceId = :workspaceId LIMIT 1")
	suspend fun byWorkspaceId(workspaceId: String): ConversationEntity?

	/**
	 * Every conversation, for the documents provider.
	 *
	 * A separate query from [observeAll] because that one is a `Flow` for the
	 * list screen; the provider runs on a Binder thread and needs a plain
	 * suspend read.
	 */
	@Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
	suspend fun all(): List<ConversationEntity>

	@Query("SELECT * FROM conversations WHERE id = :id")
	fun observe(id: String): Flow<ConversationEntity?>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(conversation: ConversationEntity)

	@Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
	suspend fun rename(id: String, title: String, now: Long)

	@Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
	suspend fun setPinned(id: String, pinned: Boolean)

	@Query("UPDATE conversations SET sshAllowed = :allowed WHERE id = :id")
	suspend fun setSshAllowed(id: String, allowed: Boolean)

	@Query("SELECT sshAllowed FROM conversations WHERE id = :id")
	suspend fun sshAllowed(id: String): Boolean?

	@Query("UPDATE conversations SET sshAllowed = 0")
	suspend fun clearAllSshAllowed()

	@Query("UPDATE conversations SET model = :model WHERE id = :id")
	suspend fun setModel(id: String, model: String?)

	/**
	 * Set the reasoning controls together.
	 *
	 * One statement rather than two because they are only meaningful as a pair:
	 * an effort level with thinking off is ignored by the provider, and storing
	 * it separately invites a row where the two disagree.
	 */
	@Query("UPDATE conversations SET reasoningEffort = :effort, thinking = :thinking WHERE id = :id")
	suspend fun setReasoning(id: String, effort: String?, thinking: Boolean)

	@Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
	suspend fun touch(id: String, now: Long)

	@Query("DELETE FROM conversations WHERE id = :id")
	suspend fun delete(id: String)
}

@Dao
interface MessageDao {

	/**
	 * Every message in a conversation, in the order it was said.
	 *
	 * `id` is the tie-break, and it is load-bearing rather than tidiness.
	 * `createdAt` is milliseconds, and a tool result is written immediately
	 * after the assistant turn that asked for it -- routinely inside the same
	 * millisecond. With `createdAt` alone the two rows come back in an order
	 * SQLite is free to choose, and `ConversationStore.activePath` picks the
	 * newest child by the same single key, so a transcript could render a tool
	 * result above the message that requested it, or pick the wrong branch.
	 * Ids are random UUIDs, so they decide nothing about meaning; they only
	 * make an arbitrary choice the *same* arbitrary choice every time.
	 */
	@Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
	fun observeAll(conversationId: String): Flow<List<MessageEntity>>

	@Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
	suspend fun all(conversationId: String): List<MessageEntity>

	@Query("SELECT * FROM messages WHERE id = :id")
	suspend fun byId(id: String): MessageEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(message: MessageEntity)

	@Update
	suspend fun update(message: MessageEntity)

	@Query("UPDATE messages SET selectedChildId = :childId WHERE id = :parentId")
	suspend fun setSelectedChild(parentId: String, childId: String?)

	@Query("UPDATE messages SET changedFilesJson = :json WHERE id = :id")
	suspend fun setChangedFiles(id: String, json: String?)

	/**
	 * Mark a message as having changed the workspace.
	 *
	 * Its own statement because the fact is only known *after* the row is
	 * written: a reply is persisted when the model finishes streaming, and its
	 * tools run afterwards.
	 */
	@Query("UPDATE messages SET mutated = 1 WHERE id = :id")
	suspend fun markMutated(id: String)

	/**
	 * Every reply to [parentId], oldest first.
	 *
	 * The siblings of one message, which is what a branch switcher counts and
	 * indexes. Not a recursive walk: a switcher only ever needs one level,
	 * because "1 of 3" is about the alternatives at this point in the
	 * conversation rather than about the whole tree beneath them.
	 */
	@Query("SELECT * FROM messages WHERE parentId = :parentId ORDER BY createdAt ASC, id ASC")
	suspend fun childrenOf(parentId: String): List<MessageEntity>

	/**
	 * The parents that actually have alternatives, in one conversation.
	 *
	 * ## Why this exists
	 *
	 * The screen needs a `Branches` for every message that has two or more
	 * replies under it, and almost no message does. Asking per message -- one
	 * query for the children and, when there are several, a second for the
	 * parent row -- meant roughly two queries per message on every transcript
	 * load: four hundred round trips for a two-hundred-message conversation, to
	 * answer "none" almost every time.
	 *
	 * This answers it for the whole conversation at once. The set is tiny (a
	 * conversation with a few regenerated replies has a handful of entries), so
	 * the caller then asks the detailed question only where there is something
	 * to describe.
	 */
	@Query(
		"""
		SELECT parentId FROM messages
		WHERE conversationId = :conversationId AND parentId IS NOT NULL
		GROUP BY parentId
		HAVING COUNT(*) > 1
		""",
	)
	suspend fun branchParents(conversationId: String): List<String>

	@Query("DELETE FROM messages WHERE id = :id")
	suspend fun delete(id: String)

	@Query("DELETE FROM messages WHERE conversationId = :conversationId")
	suspend fun deleteAllIn(conversationId: String)
}

@Dao
interface ContextSummaryDao {

	@Query("SELECT * FROM context_summaries WHERE conversationId = :conversationId")
	suspend fun byConversation(conversationId: String): ContextSummaryEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(summary: ContextSummaryEntity)

	@Query("DELETE FROM context_summaries WHERE conversationId = :conversationId")
	suspend fun clear(conversationId: String)

	@Query("SELECT * FROM context_summaries")
	suspend fun all(): List<ContextSummaryEntity>
}

@Dao
interface AgentDao {

	// `id` last, for the same reason as `ConversationDao.observeAll`: equal
	// `updatedAt` values have no defined order without it.
	@Query("SELECT * FROM agents ORDER BY isDefault DESC, updatedAt DESC, id ASC")
	fun observeAll(): Flow<List<AgentEntity>>

	@Query("SELECT * FROM agents ORDER BY isDefault DESC, updatedAt DESC, id ASC")
	suspend fun all(): List<AgentEntity>

	@Query("SELECT * FROM agents WHERE id = :id")
	suspend fun byId(id: String): AgentEntity?

	/**
	 * The seeded assistant.
	 *
	 * Named `defaultAgent`, not `default`: the latter is a Java keyword, and KSP
	 * rejects it with "not a valid name" before any Kotlin compiler sees it.
	 */
	@Query("SELECT * FROM agents WHERE isDefault = 1 LIMIT 1")
	suspend fun defaultAgent(): AgentEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(agent: AgentEntity)

	@Query("DELETE FROM agents WHERE id = :id AND isDefault = 0")
	suspend fun delete(id: String)
}

/**
 * Reading and writing the three approval-rule sources.
 *
 * No insert-with-replace for reads: [all] returns everything and the resolution
 * happens in `PolicySet`, because composing is a pure function with tests and
 * doing it in SQL would put the one rule that must not be got wrong somewhere
 * it cannot be tested without a database.
 */
@Dao
interface PolicyDao {

	@Query("SELECT * FROM approval_defaults")
	suspend fun defaults(): List<ApprovalDefaultEntity>

	@Query("SELECT * FROM agent_approval WHERE agentId = :agentId")
	suspend fun forAgent(agentId: String): List<AgentApprovalEntity>

	@Query("SELECT * FROM path_policy WHERE conversationId = :conversationId")
	suspend fun forConversation(conversationId: String): List<PathPolicyEntity>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun setDefault(row: ApprovalDefaultEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun setAgentRule(row: AgentApprovalEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun setPathRule(row: PathPolicyEntity)

	/**
	 * Remove a rule rather than storing `Free` for it.
	 *
	 * Deleting and "configured to Free" are different states and the difference
	 * is visible to the user: a rule that exists but says "no restriction" is
	 * still a rule the settings screen has to show and explain. Absence is the
	 * honest encoding of "not configured".
	 */
	@Query("DELETE FROM approval_defaults WHERE toolName = :toolName")
	suspend fun clearDefault(toolName: String)

	@Query("DELETE FROM agent_approval WHERE agentId = :agentId AND toolName = :toolName")
	suspend fun clearAgentRule(agentId: String, toolName: String)

	@Query("DELETE FROM path_policy WHERE conversationId = :conversationId AND relPath = :relPath")
	suspend fun clearPathRule(conversationId: String, relPath: String)

	/** Every rule of every kind, for screens that show what is configured. */
	@Query("SELECT COUNT(*) FROM approval_defaults")
	suspend fun defaultCount(): Int
}

@Database(
	entities = [
		ProviderProfileEntity::class,
		WorkspaceEntity::class,
		ConversationEntity::class,
		MessageEntity::class,
		ContextSummaryEntity::class,
		AgentEntity::class,
		ApprovalDefaultEntity::class,
		AgentApprovalEntity::class,
		PathPolicyEntity::class,
	],
	// Bumped when columns were added for tool calls, again for measured
	// latency, again for assistants, and again for the approval rules. Room
	// refuses to open a database whose schema hash does not match rather than
	// guessing, which is how each was caught: the app crashed on launch with
	// "Looks like you've changed schema but forgot to update the version number"
	// instead of quietly losing conversations.
	version = 13,
	exportSchema = true,
)
abstract class InhumanDatabase : RoomDatabase() {
	abstract fun providers(): ProviderDao
	abstract fun conversations(): ConversationDao
	abstract fun workspaces(): WorkspaceDao
	abstract fun messages(): MessageDao
	abstract fun contextSummaries(): ContextSummaryDao
	abstract fun agents(): AgentDao
	abstract fun policies(): PolicyDao

	companion object {
		const val NAME = "inhuman.db"

		/**
		 * 1 -> 2: tool-call columns.
		 *
		 * All three are nullable text, so the migration is additive and no
		 * existing row needs interpreting. Written out rather than using
		 * `fallbackToDestructiveMigration`, which would delete every
		 * conversation on upgrade -- acceptable while nothing has shipped, and
		 * a data-loss bug the moment something has.
		 */
		val MIGRATION_1_2 = object : Migration(1, 2) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL("ALTER TABLE messages ADD COLUMN toolCallsJson TEXT")
				connection.execSQL("ALTER TABLE messages ADD COLUMN toolCallId TEXT")
				connection.execSQL("ALTER TABLE messages ADD COLUMN toolName TEXT")
			}
		}

		/**
		 * 2 -> 3: measured latency.
		 *
		 * Also additive and nullable. Stored rather than recomputed because a
		 * figure that only exists in memory vanishes on reload, and comparing
		 * two models means wanting the older row as much as the newer one.
		 */
		val MIGRATION_2_3 = object : Migration(2, 3) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL("ALTER TABLE messages ADD COLUMN timeToFirstTokenMs INTEGER")
				connection.execSQL("ALTER TABLE messages ADD COLUMN generationMs INTEGER")
			}
		}

		/**
		 * 3 -> 4: assistants.
		 *
		 * Adds the table and the columns that link a conversation to one. The
		 * new conversation columns are nullable, so existing conversations keep
		 * working with no assistant attached -- which is the honest state, since
		 * they were held before assistants existed and inventing one for them
		 * would be putting words in their mouth.
		 *
		 * **The default assistant is seeded here as well as in `onCreate`**, and
		 * that is not redundancy. `onCreate` runs only when the database file is
		 * created, so an existing install migrating to this version would arrive
		 * with an empty assistants table, no default to fall back to, and a
		 * picker with nothing in it -- on exactly the upgrade path most users
		 * take. Found by checking a real device after the migration rather than
		 * by reading the code.
		 */
		val MIGRATION_3_4 = object : Migration(3, 4) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"""
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
					""".trimIndent(),
				)
				connection.execSQL("ALTER TABLE conversations ADD COLUMN agentId TEXT")
				connection.execSQL("ALTER TABLE conversations ADD COLUMN reasoningEffort TEXT")
				connection.execSQL("ALTER TABLE conversations ADD COLUMN thinking INTEGER NOT NULL DEFAULT 0")
				connection.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_agentId ON conversations(agentId)")
				seedDefaultAgent(connection)
			}
		}

		/**
		 * 4 -> 5: image attachments.
		 *
		 * Nullable text, so additive like the others. Stored as paths rather
		 * than bytes, so this column stays small and the file remains the single
		 * source of truth for what an image contains.
		 */
		val MIGRATION_4_5 = object : Migration(4, 5) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT")
			}
		}

		/**
		 * 5 -> 6: whether a turn changed the workspace.
		 *
		 * A not-null boolean with a default, so existing rows get `false`. That
		 * is a judgement rather than a convenience: replies held before this
		 * column existed cannot be classified either way, and treating them all
		 * as mutating would disable regeneration on every old reply including
		 * the text-only ones. The risk of `false` is bounded -- the files such a
		 * reply wrote are still in the workspace, in front of the user before
		 * they press regenerate -- whereas the other default would have removed
		 * a working feature for everyone.
		 */
		val MIGRATION_5_6 = object : Migration(5, 6) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"ALTER TABLE messages ADD COLUMN mutated INTEGER NOT NULL DEFAULT 0",
				)
			}
		}

		/**
		 * 6 -> 7: per-assistant tool selection.
		 *
		 * Nullable, and **null means every tool** rather than none. That is the
		 * safe default in the direction that matters: an assistant created
		 * before this column existed keeps working exactly as it did, and a
		 * mistake in the UI that fails to write a selection cannot silently
		 * strip capabilities from every existing assistant.
		 *
		 * The opposite default -- null meaning no tools -- would have turned
		 * every stored assistant into a text-only one on upgrade, with nothing
		 * to indicate why.
		 */
		val MIGRATION_6_7 = object : Migration(6, 7) {
			override fun migrate(connection: SQLiteConnection) {
				// NOT NULL DEFAULT '' -- the empty string is "no selection",
				// which decodes to null, which means every tool. So every
				// assistant that predates this column keeps everything it had.
				connection.execSQL(
					"ALTER TABLE agents ADD COLUMN toolsJson TEXT NOT NULL DEFAULT ''",
				)
				connection.execSQL(
					"ALTER TABLE conversations ADD COLUMN toolsJson TEXT NOT NULL DEFAULT ''",
				)
			}
		}

		/**
		 * 7 -> 8: the approval rules.
		 *
		 * Three new tables and no change to any existing one, so every
		 * conversation, message and assistant survives untouched. The tables are
		 * created empty, which is the correct migration *and* the correct
		 * product default: absence of a rule means no restriction, so an
		 * upgraded install behaves exactly as it did -- except that deletion no
		 * longer asks, which is a change in `DeleteFileTool`, not here.
		 *
		 * Written as `CREATE TABLE` statements matching what Room generates.
		 * Room verifies the resulting schema hash on open and will say so if
		 * these do not match the entities -- which is the check that makes
		 * writing them by hand safe rather than hopeful.
		 */
		val MIGRATION_7_8 = object : Migration(7, 8) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"CREATE TABLE IF NOT EXISTS `approval_defaults` " +
						"(`toolName` TEXT NOT NULL, `policy` TEXT NOT NULL, " +
						"PRIMARY KEY(`toolName`))",
				)
				connection.execSQL(
					"CREATE TABLE IF NOT EXISTS `agent_approval` " +
						"(`agentId` TEXT NOT NULL, `toolName` TEXT NOT NULL, " +
						"`policy` TEXT NOT NULL, PRIMARY KEY(`agentId`, `toolName`))",
				)
				connection.execSQL(
					"CREATE TABLE IF NOT EXISTS `path_policy` " +
						"(`conversationId` TEXT NOT NULL, `relPath` TEXT NOT NULL, " +
						"`policy` TEXT NOT NULL, PRIMARY KEY(`conversationId`, `relPath`))",
				)
			}
		}

		/**
		 * 8 -> 9: the per-conversation SSH acknowledgement.
		 *
		 * Additive and defaulted, so an upgrade starts with SSH unallowed --
		 * the only safe meaning for a consent flag.
		 */
		val MIGRATION_8_9 = object : Migration(8, 9) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"ALTER TABLE conversations ADD COLUMN sshAllowed INTEGER NOT NULL DEFAULT 0",
				)
			}
		}

		/**
		 * 9 -> 10: the per-turn changed-file list.
		 *
		 * Nullable, so every existing message reads as "not recorded" rather
		 * than "no changes". Those are different facts and old rows really do
		 * not know.
		 */
		val MIGRATION_9_10 = object : Migration(9, 10) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL("ALTER TABLE messages ADD COLUMN changedFilesJson TEXT")
			}
		}

		/**
		 * 10 -> 11: workspaces become first-class.
		 *
		 * Every conversation starts with a **temporary** workspace that names it
		 * as the owner, so existing installs keep their one-conversation
		 * behaviour and can later promote the workspace without moving files.
		 * The unique index on `conversations.workspaceId` has to go: a permanent
		 * workspace is shared by several conversations.
		 *
		 * No directory is moved or renamed. `workspaceId` is already the
		 * directory name, and keeping it makes the migration reversible in
		 * practice: a rollback would still find every file where it was.
		 */
		val MIGRATION_10_11 = object : Migration(10, 11) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"""
					CREATE TABLE IF NOT EXISTS workspaces (
						id TEXT NOT NULL PRIMARY KEY,
						name TEXT NOT NULL,
						isPermanent INTEGER NOT NULL DEFAULT 0,
						ownerConversationId TEXT,
						createdAt INTEGER NOT NULL,
						updatedAt INTEGER NOT NULL
					)
					""".trimIndent(),
				)
				connection.execSQL(
					"CREATE INDEX IF NOT EXISTS index_workspaces_isPermanent ON workspaces(isPermanent)",
				)
				connection.execSQL(
					"""
					INSERT OR IGNORE INTO workspaces
						(id, name, isPermanent, ownerConversationId, createdAt, updatedAt)
					SELECT workspaceId, title, 0, id, createdAt, updatedAt
					FROM conversations
					""".trimIndent(),
				)
				connection.execSQL("DROP INDEX IF EXISTS index_conversations_workspaceId")
				connection.execSQL(
					"CREATE INDEX IF NOT EXISTS index_conversations_workspaceId " +
						"ON conversations(workspaceId)",
				)
			}
		}

		/**
		 * 11 -> 12: configurable chat providers.
		 *
		 * The seeded DeepSeek profile keeps the legacy key slot, so existing
		 * installs continue to chat without re-entering the key. Every existing
		 * assistant and conversation points at it. Web-tool keys are separate
		 * and are deliberately not copied here.
		 */
		val MIGRATION_11_12 = object : Migration(11, 12) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"""
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
					""".trimIndent(),
				)
				connection.execSQL(
					"""
					INSERT OR IGNORE INTO provider_profiles
						(id, name, kind, baseUrl, reasoningMode, includeStreamUsage,
						 manualModels, isBuiltIn, createdAt, updatedAt)
					VALUES
						('${ProviderIds.DEEPSEEK}', 'DeepSeek', '${ProviderKinds.DEEPSEEK}',
						 'https://api.deepseek.com', '${ProviderReasoning.DEEPSEEK}', 1,
						 '', 1, 0, 0)
					""".trimIndent(),
				)
				connection.execSQL(
					"ALTER TABLE agents ADD COLUMN providerId TEXT NOT NULL " +
						"DEFAULT '${ProviderIds.DEEPSEEK}'",
				)
				connection.execSQL(
					"ALTER TABLE conversations ADD COLUMN providerId TEXT NOT NULL " +
						"DEFAULT '${ProviderIds.DEEPSEEK}'",
				)
			}
		}

		/**
		 * 12 -> 13: derived context summaries.
		 *
		 * Additive and empty. The table stores only a request projection; the
		 * message rows it references remain untouched, so an upgrade never
		 * loses a transcript and clearing the row restores the full request.
		 */
		val MIGRATION_12_13 = object : Migration(12, 13) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"""
					CREATE TABLE IF NOT EXISTS context_summaries (
						conversationId TEXT NOT NULL PRIMARY KEY,
						throughMessageId TEXT NOT NULL,
						summary TEXT NOT NULL,
						createdAt INTEGER NOT NULL,
						updatedAt INTEGER NOT NULL,
						FOREIGN KEY(conversationId) REFERENCES conversations(id)
							ON UPDATE NO ACTION ON DELETE CASCADE
					)
					""".trimIndent(),
				)
				connection.execSQL(
					"CREATE INDEX IF NOT EXISTS index_context_summaries_conversationId " +
						"ON context_summaries (conversationId)",
				)
			}
		}

		/**
		 * The assistant every install starts with.
		 *
		 * Its instructions are written out rather than left blank, because an
		 * empty system prompt gives a model nothing to work with and makes the
		 * first conversation a poor introduction. They say what the app is for
		 * -- the workspace -- which is the one thing a user would otherwise have
		 * to discover by accident.
		 *
		 * Lives here, next to both callers, rather than in `Database` where it
		 * started: `onCreate` and the 3 -> 4 migration are the two places a
		 * database comes into being, and a helper that only one of them could
		 * reach is how the upgrade path came to be missing it.
		 */
		internal fun seedDefaultProvider(connection: SQLiteConnection) {
			val now = System.currentTimeMillis()
			val statement = connection.prepare(
				"""
				INSERT OR IGNORE INTO provider_profiles
					(id, name, kind, baseUrl, reasoningMode, includeStreamUsage,
					 manualModels, isBuiltIn, createdAt, updatedAt)
				VALUES (?, ?, ?, ?, ?, 1, '', 1, ?, ?)
				""".trimIndent(),
			)
			try {
				statement.bindText(1, ProviderIds.DEEPSEEK)
				statement.bindText(2, "DeepSeek")
				statement.bindText(3, ProviderKinds.DEEPSEEK)
				statement.bindText(4, "https://api.deepseek.com")
				statement.bindText(5, ProviderReasoning.DEEPSEEK)
				statement.bindLong(6, now)
				statement.bindLong(7, now)
				statement.step()
			} finally {
				statement.close()
			}
		}

		internal fun seedDefaultAgent(connection: SQLiteConnection) {
			val now = System.currentTimeMillis()
			// Prepared and bound rather than interpolated: the instructions
			// contain apostrophes, and a string-built statement would either
			// break on them or need escaping that is easy to get wrong. Binding
			// also keeps the prompt text out of any query log.
			val statement = connection.prepare(
				"""
				INSERT OR IGNORE INTO agents
					(id, name, systemPrompt, model, reasoningEffort, thinking, createdAt, updatedAt, isDefault)
				VALUES (?, ?, ?, NULL, NULL, 0, ?, ?, 1)
				""".trimIndent(),
			)
			try {
				statement.bindText(1, DEFAULT_AGENT_ID)
				statement.bindText(2, "Assistant")
				statement.bindText(3, DEFAULT_SYSTEM_PROMPT)
				statement.bindLong(4, now)
				statement.bindLong(5, now)
				statement.step()
			} finally {
				statement.close()
			}
		}

		/** Stable id so a reinstall cannot produce two defaults. */
		const val DEFAULT_AGENT_ID = "default-agent"

		val DEFAULT_SYSTEM_PROMPT = """
			You are a helpful assistant.

			You have a workspace: a folder belonging to this conversation that you can
			read and write with the tools provided. It is the only place you can
			touch — every path you pass is relative to it, and you cannot reach
			anything outside it. Use it when a task involves files, and prefer
			writing results there over describing them, so the user can open them.

			Be direct. Answer the question that was asked, in as many words as it
			needs and no more.
		""".trimIndent()
	}
}

/**
 * Reads and writes that must not interleave.
 *
 * Kept out of the DAOs because they span both tables: appending a message and
 * marking the conversation as updated are one logical operation, and a reader
 * that observed the first without the second would see a message that appears
 * to belong to no recent conversation.
 */
class ConversationStore(private val db: InhumanDatabase) {

	/**
	 * Walk the active branch of a conversation.
	 *
	 * The tree is stored as parent links plus a `selectedChildId` on each node,
	 * so the visible transcript is the chain obtained by starting at the root
	 * and repeatedly following the selected child. Returning the whole table and
	 * filtering in Kotlin rather than doing this in SQL is deliberate: the query
	 * would be a recursive CTE, and the number of messages in one conversation
	 * is small enough that clarity wins.
	 */
	fun activePath(messages: List<MessageEntity>): List<MessageEntity> = activePathOf(messages)

	companion object {
		/**
		 * Creation order, with the id as a tie-break.
		 *
		 * The tie-break is what makes this a total order, and it has to match
		 * the `ORDER BY createdAt ASC, id ASC` in [MessageDao] -- two rules
		 * that disagree would put the transcript on screen in one order and let
		 * the branch walk choose by another.
		 */
		private val SAID_ORDER: Comparator<MessageEntity> =
			compareBy<MessageEntity> { it.createdAt }.thenBy { it.id }

		/**
		 * Walk the active branch of a conversation.
		 *
		 * The tree is stored as parent links plus a `selectedChildId` on each
		 * node, so the visible transcript is the chain obtained by starting at
		 * the root and repeatedly following the selected child. Returning the
		 * whole table and filtering in Kotlin rather than doing this in SQL is
		 * deliberate: the query would be a recursive CTE, and the number of
		 * messages in one conversation is small enough that clarity wins.
		 *
		 * ## Why it lives in the companion
		 *
		 * It touches no database and no Android API -- it is a pure function
		 * over rows -- so keeping it reachable without an open database is what
		 * lets the walk be unit-tested on the JVM. A tree walk that decides
		 * which messages are on screen is worth a test, and the alternative was
		 * an instrumented test against a real Room instance for a function that
		 * has no idea Room exists.
		 */
		fun activePathOf(messages: List<MessageEntity>): List<MessageEntity> {
			if (messages.isEmpty()) return emptyList()

			val byParent = messages.groupBy { it.parentId }
			val roots = messages.filter { it.parentId == null }.sortedWith(SAID_ORDER)
			if (roots.isEmpty()) return emptyList()

			// A conversation has one root in practice; if a branch ever produced
			// several, the earliest wins so the choice is at least
			// deterministic.
			val path = mutableListOf<MessageEntity>()
			val seen = mutableSetOf<String>()
			var current: MessageEntity? = roots.first()
			while (current != null) {
				// A row whose parent links form a cycle would otherwise loop
				// forever. Nothing in the app writes one, but a walk that trusts
				// its input to be a tree is one bad row away from a hang, and
				// the check is one set lookup.
				if (!seen.add(current.id)) break
				path += current
				val children = byParent[current.id].orEmpty()
				val selected = current.selectedChildId?.let { id -> children.firstOrNull { it.id == id } }
				// Fall back to the newest child when nothing is marked selected:
				// that is what a freshly appended reply is, and it keeps a
				// transcript readable if a write was interrupted.
				//
				// "Newest" uses the same comparator the queries sort by, so the
				// walk and the row order cannot disagree about which child that
				// is.
				current = selected ?: children.maxWithOrNull(SAID_ORDER)
			}
			return path
		}
	}

	@Transaction
	suspend fun append(message: MessageEntity, parentId: String?) {
		db.messages().upsert(message)
		if (parentId != null) {
			db.messages().setSelectedChild(parentId, message.id)
		}
		db.conversations().touch(message.conversationId, message.createdAt)
	}

	@Transaction
	suspend fun createConversation(conversation: ConversationEntity, firstMessage: MessageEntity?) {
		db.conversations().upsert(conversation)
		firstMessage?.let { db.messages().upsert(it) }
	}

	@Transaction
	suspend fun deleteConversation(id: String) {
		// The foreign key cascades, but deleting explicitly keeps the behaviour
		// obvious and survives anyone later switching the cascade off.
		db.messages().deleteAllIn(id)
		db.conversations().delete(id)
	}
}
