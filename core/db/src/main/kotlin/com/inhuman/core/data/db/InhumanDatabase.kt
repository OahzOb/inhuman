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
import com.inhuman.core.common.diag.Diag
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

	/**
	 * Replace one profile's model tags.
	 *
	 * A targeted UPDATE rather than a read-modify-upsert: the picker writes this
	 * while a conversation may be running, and an upsert of a row read a moment
	 * earlier would put back a stale `updatedAt` and any other field that had
	 * changed in between.
	 */
	@Query("UPDATE provider_profiles SET modelTags = :tags, updatedAt = :now WHERE id = :id")
	suspend fun setModelTags(id: String, tags: String, now: Long)

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

	/** Title-only search, for a conversation whose contents do not match. */
	@Query(
		"SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' ESCAPE '\\' " +
			"ORDER BY pinned DESC, updatedAt DESC, id ASC LIMIT :limit",
	)
	suspend fun searchTitles(query: String, limit: Int): List<ConversationEntity>

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

	/**
	 * Replace a conversation's allowed-host selection.
	 *
	 * No `updatedAt` bump, for the same reason as [setTools]: which machines a
	 * conversation may reach is a capability edit, not new conversation
	 * activity.
	 */
	@Query("UPDATE conversations SET sshHostsJson = :json WHERE id = :id")
	suspend fun setSshHosts(id: String, json: String)

	@Query("SELECT sshHostsJson FROM conversations WHERE id = :id")
	suspend fun sshHosts(id: String): String?

	/**
	 * Replace a conversation's tool selection.
	 *
	 * No `updatedAt` bump: changing which tools exist is not new conversation
	 * activity, and moving the row to the top of the list would be a surprise.
	 */
	@Query("UPDATE conversations SET toolsJson = :toolsJson WHERE id = :id")
	suspend fun setTools(id: String, toolsJson: String)

	/**
	 * Replace a conversation's pinned workspace-instructions block.
	 *
	 * No `updatedAt` bump, for the same reason as [setTools]: pinning the
	 * instructions is not conversation activity, and moving the row to the top
	 * of the list would be a surprise.
	 */
	@Query("UPDATE conversations SET workspaceInstructions = :block WHERE id = :id")
	suspend fun setWorkspaceInstructions(id: String, block: String?)

	@Query("SELECT sshAllowed FROM conversations WHERE id = :id")
	suspend fun sshAllowed(id: String): Boolean?

	@Query("UPDATE conversations SET sshAllowed = 0")
	suspend fun clearAllSshAllowed()

	@Query("UPDATE conversations SET model = :model WHERE id = :id")
	suspend fun setModel(id: String, model: String?)

	/**
	 * Re-point a conversation at another provider profile.
	 *
	 * The model, effort and thinking flag are cleared in the same statement:
	 * they are provider/model-specific choices, and keeping a model id that the
	 * new provider does not offer would leave the conversation pointing at a
	 * model that does not exist. The user re-picks after the switch.
	 */
	@Query(
		"UPDATE conversations SET providerId = :providerId, model = NULL, " +
			"reasoningEffort = NULL, thinking = 0 WHERE id = :id",
	)
	suspend fun setProvider(id: String, providerId: String)

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

	/**
	 * Move a conversation's timestamp forward unless another row already did so
	 * recently.
	 *
	 * Every appended message used to write this row inside its own transaction,
	 * and the home screen observes `SELECT * FROM conversations`: a tool-heavy
	 * turn re-queried the whole list once per message, for a timestamp whose
	 * only job is to order that list. An UPDATE matching no rows fires no
	 * trigger, so a suppressed touch wakes nobody.
	 */
	@Query("UPDATE conversations SET updatedAt = :now WHERE id = :id AND updatedAt < :now - :windowMs")
	suspend fun touchIfOlder(id: String, now: Long, windowMs: Long)

	@Query("DELETE FROM conversations WHERE id = :id")
	suspend fun delete(id: String)
}

/**
 * One message hit for global search, joined to the conversation title.
 *
 * Kept as a projection rather than loading full [MessageEntity] rows: a hit
 * only needs the text around the match, and a long answer would otherwise load
 * several kilobytes per result into the UI process.
 */
data class MessageSearchRow(
	val messageId: String,
	val conversationId: String,
	val conversationTitle: String,
	val role: String,
	val content: String,
	val createdAt: Long,
)

/**
 * One stored fact the usage overview can count.
 *
 * A projection rather than the whole [MessageEntity]: `content` and `reasoning`
 * are the largest columns in the database, and adding a few integers must not
 * load a conversation's worth of prose. [resultPrefix] is the only piece of
 * `content` needed -- it tells a call that ran from the app's own
 * `[declined]`/`[skipped]`/`[refused]`/`[interrupted]` markers.
 *
 * A row carries tokens (an assistant message) or a tool name (a tool result),
 * never both in practice; the aggregator treats the two independently because
 * the projection cannot promise that and a future tool result with usage
 * should still count.
 */
data class UsageRow(
	val conversationId: String,
	val conversationTitle: String,
	val createdAt: Long,
	val promptTokens: Int? = null,
	val completionTokens: Int? = null,
	val cachedPromptTokens: Int? = null,
	val reasoningTokens: Int? = null,
	val toolName: String? = null,
	val resultPrefix: String = "",
)

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

	/**
	 * The columns the branch walk needs, for several conversations at once.
	 *
	 * Global search has to know which branch is displayed before it can keep a
	 * hit, and it used to read every matched conversation's full rows --
	 * `content`, `reasoning`, tool results and attachments -- to run a walk that
	 * looks at four columns. This projection is that walk's actual input.
	 */
	@Query(
		"""
		SELECT conversationId, id, parentId, selectedChildId, createdAt
		FROM messages WHERE conversationId IN (:conversationIds)
		""",
	)
	suspend fun branchRows(conversationIds: List<String>): List<BranchRow>

	/**
	 * One conversation's structural rows, for the turn metadata the chat screen
	 * recomputes after every turn.
	 *
	 * [MessageEntity] carries the prose; this carries the branch pointers, the
	 * tool calls and the two flags the rewind gate reads. Selecting them avoids
	 * loading a transcript of tool results just to decide whether a button is
	 * enabled.
	 */
	@Query(
		"""
		SELECT id, conversationId, parentId, selectedChildId, createdAt,
		       role, toolCallsJson, toolCallId, mutated
		FROM messages WHERE conversationId = :conversationId
		""",
	)
	suspend fun turnRows(conversationId: String): List<MessageTurnRow>

	/**
	 * The rows the Usage overview can count, without their prose.
	 *
	 * `substr` rather than `content`: the aggregator needs only enough of a
	 * tool result to recognise the app's unrun-call markers, and a search result
	 * or a generated file's metadata can be megabytes. The WHERE clause keeps
	 * the scan off user messages and empty assistant rows.
	 *
	 * No ORDER BY on purpose. The caller groups by local day and by
	 * conversation, so insertion order buys nothing and would sort every message
	 * on every open of the screen.
	 */
	@Query(
		"""
		SELECT m.conversationId AS conversationId, c.title AS conversationTitle,
		       m.createdAt AS createdAt,
		       m.promptTokens AS promptTokens, m.completionTokens AS completionTokens,
		       m.cachedPromptTokens AS cachedPromptTokens,
		       m.reasoningTokens AS reasoningTokens,
		       m.toolName AS toolName, substr(m.content, 1, 24) AS resultPrefix
		FROM messages m JOIN conversations c ON c.id = m.conversationId
		WHERE m.promptTokens IS NOT NULL OR m.completionTokens IS NOT NULL
		   OR m.cachedPromptTokens IS NOT NULL OR m.reasoningTokens IS NOT NULL
		   OR m.toolName IS NOT NULL
		""",
	)
	suspend fun usageRows(): List<UsageRow>

	@Query("SELECT * FROM messages WHERE id = :id")
	suspend fun byId(id: String): MessageEntity?

	/**
	 * Case-insensitive search over user and assistant text.
	 *
	 * `%` and `_` are escaped by the caller and the pattern uses `ESCAPE '\'`,
	 * so searching for a literal percent sign does not turn into a wildcard.
	 * Tool results are excluded: they are machinery, and searching them would
	 * bury a conversation in matches the user never wrote or read as prose.
	 */
	@Query(
		"""
		SELECT m.id AS messageId, m.conversationId AS conversationId,
		       c.title AS conversationTitle, m.role AS role, m.content AS content,
		       m.createdAt AS createdAt
		FROM messages m JOIN conversations c ON c.id = m.conversationId
		WHERE m.role IN ('user', 'assistant')
		  AND m.content LIKE '%' || :query || '%' ESCAPE '\'
		ORDER BY m.createdAt DESC, m.id ASC
		LIMIT :limit
		""",
	)
	suspend fun search(query: String, limit: Int): List<MessageSearchRow>


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

/**
 * The approval audit, newest first.
 *
 * The update is guarded by `decision = 'asked'`: a timeout and a late tap can
 * race, and the first terminal answer must win. Without the guard, a user who
 * comes back after the timeout could make the record say `allowed` for a call
 * that never ran.
 */
@Dao
interface ApprovalAuditDao {

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insert(row: ApprovalAuditEntity)

	@Query(
		"UPDATE approval_audit SET decision = :decision, decidedAt = :decidedAt " +
			"WHERE id = :id AND decision = 'asked'",
	)
	suspend fun decide(id: String, decision: String, decidedAt: Long)

	@Query(
		"SELECT * FROM approval_audit WHERE conversationId = :conversationId " +
			"ORDER BY askedAt DESC, id ASC",
	)
	suspend fun forConversation(conversationId: String): List<ApprovalAuditEntity>

	@Query("SELECT COUNT(*) FROM approval_audit WHERE conversationId = :conversationId")
	suspend fun count(conversationId: String): Int
}

/**
 * The saved-prompt library.
 *
 * Newest first, with `id` as the tie-break for two saves in the same
 * millisecond. The unique title index is what makes "save with this title
 * again" an update rather than a second row.
 */
@Dao
interface SavedPromptDao {

	@Query("SELECT * FROM saved_prompts ORDER BY updatedAt DESC, id ASC")
	fun observeAll(): Flow<List<SavedPromptEntity>>

	@Query("SELECT * FROM saved_prompts ORDER BY updatedAt DESC, id ASC")
	suspend fun all(): List<SavedPromptEntity>

	@Query("SELECT * FROM saved_prompts WHERE title = :title LIMIT 1")
	suspend fun byTitle(title: String): SavedPromptEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(row: SavedPromptEntity)

	@Query("DELETE FROM saved_prompts WHERE id = :id")
	suspend fun delete(id: String)
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
		ApprovalAuditEntity::class,
		SavedPromptEntity::class,
	],
	// Bumped when columns were added for tool calls, again for measured
	// latency, again for assistants, again for the approval rules, again for
	// the approval audit, again for the saved-prompt library, again for the
	// Anthropic thinking signature, and again for OpenAI Responses provider
	// state, again for the per-assistant follow-up suggestion settings, again
	// for the OpenCode reasoning-mode correction, again for the pinned
	// workspace instructions, and again for the per-conversation SSH host
	// selection. Room refuses to open a database whose schema hash does not
	// match rather than guessing, which is how each was caught: the app crashed
	// on launch with "Looks like you've changed schema but forgot to update the
	// version number" instead of quietly losing conversations.
	version = 22,
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
	abstract fun approvals(): ApprovalAuditDao
	abstract fun savedPrompts(): SavedPromptDao

	companion object {
		const val NAME = "inhuman.db"

		/** Mirrors the Room annotation; backups record it without reflection. */
		const val VERSION = 22

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
		 * 13 -> 14: per-model tags.
		 *
		 * Additive and empty. An untagged model keeps reading as "not tagged",
		 * which is what the inference in `ModelCapabilities` is for, so an upgrade
		 * changes nothing until someone labels a model in the picker.
		 */
		val MIGRATION_13_14 = object : Migration(13, 14) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"ALTER TABLE provider_profiles ADD COLUMN modelTags TEXT NOT NULL DEFAULT ''",
				)
			}
		}

		/**
		 * 14 -> 15: the approval audit.
		 *
		 * Additive: an install that never sees an approval prompt keeps an
		 * empty table. The foreign key is the conversation, not the message, so
		 * rewinding a turn does not erase the record that its call was asked
		 * about -- see `ApprovalAuditEntity` for why that is deliberate.
		 */
		val MIGRATION_14_15 = object : Migration(14, 15) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"""
					CREATE TABLE IF NOT EXISTS approval_audit (
						id TEXT NOT NULL,
						conversationId TEXT NOT NULL,
						messageId TEXT NOT NULL,
						callId TEXT NOT NULL,
						toolName TEXT NOT NULL,
						path TEXT,
						rule TEXT NOT NULL,
						decision TEXT NOT NULL,
						askedAt INTEGER NOT NULL,
						decidedAt INTEGER,
						PRIMARY KEY(id),
						FOREIGN KEY(conversationId) REFERENCES conversations(id)
							ON UPDATE NO ACTION ON DELETE CASCADE
					)
					""".trimIndent(),
				)
				connection.execSQL(
					"CREATE INDEX IF NOT EXISTS index_approval_audit_conversationId_askedAt " +
						"ON approval_audit (conversationId, askedAt)",
				)
			}
		}

		/**
		 * 15 -> 16: the saved-prompt library.
		 *
		 * Additive and empty. No existing row is interpreted, and an install
		 * that never saves a prompt keeps a table nobody reads.
		 */
		val MIGRATION_15_16 = object : Migration(15, 16) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"""
					CREATE TABLE IF NOT EXISTS saved_prompts (
						id TEXT NOT NULL,
						title TEXT NOT NULL,
						text TEXT NOT NULL,
						createdAt INTEGER NOT NULL,
						updatedAt INTEGER NOT NULL,
						PRIMARY KEY(id)
					)
					""".trimIndent(),
				)
				connection.execSQL(
					"CREATE UNIQUE INDEX IF NOT EXISTS index_saved_prompts_title " +
						"ON saved_prompts (title)",
				)
			}
		}

		/**
		 * 16 -> 17: the Anthropic thinking signature.
		 *
		 * Nullable and additive: old rows keep null, which the Anthropic adapter
		 * treats as "thinking cannot be enabled on a transcript containing this
		 * tool turn". Storing an empty string would look like a signature and
		 * produce a 400 instead.
		 */
		val MIGRATION_16_17 = object : Migration(16, 17) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL("ALTER TABLE messages ADD COLUMN reasoningSignature TEXT")
			}
		}

		/**
		 * 17 -> 18: opaque provider input state for OpenAI Responses.
		 *
		 * Nullable and additive. It is a JSON array of raw reasoning items; an
		 * old row reads back null, and the Responses adapter simply replays no
		 * state for it.
		 */
		val MIGRATION_17_18 = object : Migration(17, 18) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL("ALTER TABLE messages ADD COLUMN providerState TEXT")
			}
		}

		/**
		 * 18 -> 19: per-assistant follow-up suggestion settings.
		 *
		 * Additive and nullable except the format, which is `tool` for every
		 * existing assistant: the tool is opt-in through `toolsJson`, so a
		 * default row is inert until someone selects `suggest_replies`. The
		 * default matters only when it is selected, and `tool` is the shape the
		 * feature was built around.
		 */
		val MIGRATION_18_19 = object : Migration(18, 19) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL("ALTER TABLE agents ADD COLUMN suggestionProviderId TEXT")
				connection.execSQL("ALTER TABLE agents ADD COLUMN suggestionModel TEXT")
				connection.execSQL("ALTER TABLE agents ADD COLUMN suggestionPrompt TEXT")
				connection.execSQL(
					"ALTER TABLE agents ADD COLUMN suggestionFormat TEXT NOT NULL DEFAULT 'tool'",
				)
			}
		}

		/**
		 * 19 -> 20: OpenCode presets learn how their endpoint reasons.
		 *
		 * Existing OpenCode profiles were created with `none`, which was the
		 * catalog's default for every preset at the time. That mode drops
		 * `reasoning_effort` on the floor, so the effort chips a model
		 * advertises changed nothing on the wire. The mode is a fact about the
		 * endpoint rather than a user preference for these two preset URLs, and
		 * the preset editor hides the control, so an existing row has no
		 * deliberate "none" to preserve. A user who wants it back can customise
		 * the profile and choose None again.
		 */
		val MIGRATION_19_20 = object : Migration(19, 20) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"UPDATE provider_profiles SET reasoningMode = 'opencode' " +
						"WHERE reasoningMode = 'none' " +
						"AND baseUrl LIKE 'https://opencode.ai/zen/%'",
				)
			}
		}

		/**
		 * 20 -> 21: a conversation can pin its `AGENTS.md` block.
		 *
		 * Nullable and additive. An old conversation has no snapshot and pins
		 * the file on its next request; a new one pins it on the first request
		 * that uses it. The block is stored on the conversation, not re-read,
		 * so editing the workspace file cannot change an existing system prompt
		 * (or its prefix cache).
		 */
		val MIGRATION_20_21 = object : Migration(20, 21) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL("ALTER TABLE conversations ADD COLUMN workspaceInstructions TEXT")
			}
		}

		/**
		 * 21 -> 22: a conversation can be given a set of saved SSH hosts.
		 *
		 * Additive with an empty default, and the default is the safe answer:
		 * an old conversation has no host selected, so its SSH tools stay
		 * unregistered until the user picks one. Nothing is inferred from the
		 * one host an earlier release could save -- that connection was never a
		 * grant to a conversation.
		 */
		val MIGRATION_21_22 = object : Migration(21, 22) {
			override fun migrate(connection: SQLiteConnection) {
				connection.execSQL(
					"ALTER TABLE conversations ADD COLUMN sshHostsJson TEXT NOT NULL DEFAULT ''",
				)
			}
		}

		/**
		 * Every migration, in order.
		 *
		 * A builder must not copy this list. `:core:data` uses
		 * `Room.databaseBuilder(context, ...)`; a test or another builder that
		 * omitted one entry would turn a schema upgrade into a failure the
		 * moment an install is older than the code.
		 */
		val MIGRATIONS = arrayOf(
			MIGRATION_1_2,
			MIGRATION_2_3,
			MIGRATION_3_4,
			MIGRATION_4_5,
			MIGRATION_5_6,
			MIGRATION_6_7,
			MIGRATION_7_8,
			MIGRATION_8_9,
			MIGRATION_9_10,
			MIGRATION_10_11,
			MIGRATION_11_12,
			MIGRATION_12_13,
			MIGRATION_13_14,
			MIGRATION_14_15,
			MIGRATION_15_16,
			MIGRATION_16_17,
			MIGRATION_17_18,
			MIGRATION_18_19,
			MIGRATION_19_20,
			MIGRATION_20_21,
			MIGRATION_21_22,
		)

		/**
		 * What every builder installs, on create and on open.
		 *
		 * The seeding lives here rather than in an anonymous callback in
		 * `:core:data` because it must run for any builder, not only the
		 * Android one, and an install with no default assistant would create
		 * conversations with nothing to attach to.
		 */
		fun callback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
			override fun onCreate(connection: SQLiteConnection) {
				super.onCreate(connection)
				seedDefaultProvider(connection)
				seedDefaultAgent(connection)
				Diag.i("db", "created $NAME")
			}

			override fun onOpen(connection: SQLiteConnection) {
				super.onOpen(connection)
				Diag.i("db", "opened $NAME")
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
		fun seedDefaultProvider(connection: SQLiteConnection) {
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

		fun seedDefaultAgent(connection: SQLiteConnection) {
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
 * The columns a branch walk reads, plus the conversation they belong to.
 *
 * A projection rather than a [MessageEntity] because the caller is global
 * search, which would otherwise load every matched conversation's prose --
 * including tool results and attachments -- just to decide which branch is
 * displayed.
 */
data class BranchRow(
	val conversationId: String,
	val id: String,
	val parentId: String?,
	val selectedChildId: String?,
	val createdAt: Long,
)

/**
 * The structural and tool-call columns the rewind gate reads.
 *
 * [MessageEntity] is what the transcript renders; this is what a turn's
 * checkpoints, branches and irreversible calls are decided from, without
 * loading the text of every tool result.
 */
data class MessageTurnRow(
	val id: String,
	val conversationId: String,
	val parentId: String?,
	val selectedChildId: String?,
	val createdAt: Long,
	val role: String,
	val toolCallsJson: String?,
	val toolCallId: String?,
	val mutated: Boolean,
)

/**
 * One second: long enough that the rows of a single turn share one touch, short
 * enough that a user typing a second turn sees their conversation move at once.
 */
private const val TOUCH_WINDOW_MS = 1_000L

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
		 * Walk the active branch of any row type that exposes the tree edges.
		 *
		 * The tree is stored as parent links plus a `selectedChildId` on each
		 * node, so the visible transcript is the chain obtained by starting at
		 * the root and repeatedly following the selected child. Returning the
		 * whole table and filtering in Kotlin rather than doing this in SQL is
		 * deliberate: the query would be a recursive CTE, and the number of
		 * messages in one conversation is small enough that clarity wins.
		 *
		 * One implementation serves three row shapes -- full rows for the
		 * transcript, branch rows for search and turn rows for the rewind gate.
		 * They must not disagree about which branch is active, and the only way
		 * to guarantee that is to have one walk.
		 *
		 * The order is creation order with the id as a tie-break. That tie-break
		 * is what makes it a total order, and it has to match the
		 * `ORDER BY createdAt ASC, id ASC` in [MessageDao] -- two rules that
		 * disagree would put the transcript on screen in one order and let the
		 * branch walk choose by another.
		 */
		private fun <T> walkActive(
			nodes: List<T>,
			id: (T) -> String,
			parentId: (T) -> String?,
			selectedChildId: (T) -> String?,
			createdAt: (T) -> Long,
		): List<T> {
			if (nodes.isEmpty()) return emptyList()
			val order = compareBy<T> { createdAt(it) }.thenBy { id(it) }
			val byParent = nodes.groupBy(parentId)
			val roots = nodes.filter { parentId(it) == null }.sortedWith(order)
			if (roots.isEmpty()) return emptyList()

			// A conversation has one root in practice; if a branch ever produced
			// several, the earliest wins so the choice is at least
			// deterministic.
			val path = mutableListOf<T>()
			val seen = mutableSetOf<String>()
			var current: T? = roots.first()
			while (current != null) {
				// A row whose parent links form a cycle would otherwise loop
				// forever. Nothing in the app writes one, but a walk that trusts
				// its input to be a tree is one bad row away from a hang, and
				// the check is one set lookup.
				if (!seen.add(id(current))) break
				path += current
				val children = byParent[id(current)].orEmpty()
				val selected = selectedChildId(current)?.let { childId ->
					children.firstOrNull { id(it) == childId }
				}
				// Fall back to the newest child when nothing is marked selected:
				// that is what a freshly appended reply is, and it keeps a
				// transcript readable if a write was interrupted.
				//
				// "Newest" uses the same comparator the queries sort by, so the
				// walk and the row order cannot disagree about which child that
				// is.
				current = selected ?: children.maxWithOrNull(order)
			}
			return path
		}

		/**
		 * Walk the active branch of a conversation.
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
		fun activePathOf(messages: List<MessageEntity>): List<MessageEntity> =
			walkActive(
				messages,
				id = { it.id },
				parentId = { it.parentId },
				selectedChildId = { it.selectedChildId },
				createdAt = { it.createdAt },
			)

		/** The same walk over [BranchRow]s, for search's structural projection. */
		fun activePathOfBranches(rows: List<BranchRow>): List<BranchRow> =
			walkActive(
				rows,
				id = { it.id },
				parentId = { it.parentId },
				selectedChildId = { it.selectedChildId },
				createdAt = { it.createdAt },
			)

		/** The same walk over [MessageTurnRow]s, for the post-turn rewind gate. */
		fun activePathOfTurnRows(rows: List<MessageTurnRow>): List<MessageTurnRow> =
			walkActive(
				rows,
				id = { it.id },
				parentId = { it.parentId },
				selectedChildId = { it.selectedChildId },
				createdAt = { it.createdAt },
			)
	}

	@Transaction
	suspend fun append(message: MessageEntity, parentId: String?) {
		db.messages().upsert(message)
		if (parentId != null) {
			db.messages().setSelectedChild(parentId, message.id)
		}
		// Throttled: one turn appends many rows, and the list only needs the
		// conversation to be near the top, not to be rewritten per token.
		db.conversations().touchIfOlder(
			message.conversationId,
			message.createdAt,
			TOUCH_WINDOW_MS,
		)
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
