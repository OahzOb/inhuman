package com.inhuman.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.ConversationStore
import com.inhuman.core.data.db.HostsColumn
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MaintenanceRunEntity
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.MessageTurnRow
import com.inhuman.core.data.db.SessionMode
import com.inhuman.core.data.db.ToolsColumn
import com.inhuman.core.data.db.UsageRow
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.core.files.SessionWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * A conversation joined with the messages currently visible in it.
 *
 * The active branch rather than every row: a conversation with three
 * regenerated replies has one transcript, and the caller should not have to
 * know about the branches that are not shown.
 */
data class Conversation(
	val entity: ConversationEntity,
	val messages: List<MessageEntity>,
)

/**
 * Where one message sits among its alternatives.
 *
 * @param index zero-based position of the reply currently shown.
 * @param total how many replies exist at this point.
 * @param previousId the reply one step back, or null at the start. Null is what
 *   disables the arrow rather than wrapping to the end: a switcher that cycles
 *   makes it impossible to tell whether the list has been around once.
 */
data class Branches(
	val index: Int,
	val total: Int,
	val previousId: String?,
	val nextId: String?,
) {
	/** "2 of 3", for a control that has room for one line. */
	val label: String get() = "${index + 1} of $total"
}

/**
 * One global-search result.
 *
 * [messageId] is null for a conversation-title match; the UI opens that
 * conversation at the bottom rather than trying to focus a message that does
 * not exist. A message hit carries enough context to render a snippet without a
 * second query.
 */
data class ConversationSearchHit(
	val conversationId: String,
	val conversationTitle: String,
	val messageId: String?,
	val role: String?,
	val snippet: String,
	val createdAt: Long,
)

/** What a bulk workspace delete actually removed. */
data class WorkspaceBulkDelete(
	val workspaces: Int,
	val conversations: Int,
)

/**
 * The application's view of stored conversations and workspaces.
 *
 * Owns three things that must stay consistent with each other:
 *
 *  1. the conversation rows,
 *  2. the workspace rows and the directory each one names,
 *  3. the ordering rules for a branch.
 *
 * A temporary workspace is created with its one conversation and deleted with
 * it; a permanent one is a first-class row that outlives its conversations and
 * may be shared. In both cases a conversation whose workspace row is missing
 * would fail on the first file tool, and a workspace directory with no row is
 * an orphan the sweep must remove. Only the row — not the conversation — decides
 * which of those is true.
 */
class ConversationRepository(
	private val db: InhumanDatabase,
	/**
	 * Where workspaces and rollback snapshots live.
	 *
	 * Injected rather than read from `context.filesDir` so this repository runs
	 * on a plain JVM. Android passes `DataPaths(context.filesDir)`; the
	 * directory layout is unchanged, so an existing install opens the same
	 * files.
	 */
	private val paths: DataPaths,
) {

	private val store = ConversationStore(db)

	fun observeAll(): Flow<List<ConversationEntity>> = db.conversations().observeAll()

	/** A one-shot list, for a host that has no flow collection at start-up. */
	suspend fun all(): List<ConversationEntity> = db.conversations().all()

	/**
	 * The token and tool rows the Usage overview counts.
	 *
	 * Read-only, and deliberately derived rather than a lifetime ledger. The
	 * overview answers "what is recorded on this device now"; a deleted or
	 * rewound turn, and a compaction summary, have no message row and therefore
	 * no entry. Calling it a bill would be a promise the data cannot keep.
	 */
	suspend fun usageRows(): List<UsageRow> = db.messages().usageRows()

	/**
	 * Search conversation titles and the user/assistant text inside them.
	 *
	 * Backed by `LIKE` rather than FTS on purpose: this is an app-private
	 * dataset of at most a few thousand messages, and an FTS table would add a
	 * schema version and a second copy of every message without changing the
	 * result a person sees. The query escapes `%`/`_` so punctuation is literal.
	 */
	suspend fun search(rawQuery: String, limit: Int = 60): List<ConversationSearchHit> =
		withContext(Dispatchers.IO) {
			val query = rawQuery.trim()
			if (query.isEmpty()) return@withContext emptyList()
			val pattern = likePattern(query)
			val messageHits = db.messages().search(pattern, limit)
			val titleHits = db.conversations().searchTitles(pattern, limit)
			// Search can match a message on an inactive branch. Opening that
			// conversation would show a different transcript and the jump would
			// silently fail, so keep only rows on the branch the app displays.
			//
			// One structural query for every matched conversation, not one full
			// transcript read per conversation: the walk needs four columns, and
			// `SELECT *` also brought back every tool result's payload.
			val activeIds = if (messageHits.isEmpty()) {
				emptyMap()
			} else {
				db.messages().branchRows(messageHits.map { it.conversationId }.distinct())
					.groupBy { it.conversationId }
					.mapValues { (_, rows) ->
						ConversationStore.activePathOfBranches(rows)
							.mapTo(mutableSetOf()) { it.id }
					}
			}
			val visibleHits = messageHits.filter { it.messageId in activeIds[it.conversationId].orEmpty() }
			val titleResults = titleHits.map { conversation ->
				ConversationSearchHit(
					conversationId = conversation.id,
					conversationTitle = conversation.title,
					messageId = null,
					role = null,
					snippet = "Conversation title",
					createdAt = conversation.updatedAt,
				)
			}
			val messageResults = visibleHits.map { hit ->
				ConversationSearchHit(
					conversationId = hit.conversationId,
					conversationTitle = hit.conversationTitle,
					messageId = hit.messageId,
					role = hit.role,
					snippet = snippet(hit.content, query),
					createdAt = hit.createdAt,
				)
			}
			// A title match is the strongest signal, so it comes first. Message
			// hits keep the newest-first order of the query.
			(titleResults + messageResults).take(limit)
		}

	private fun likePattern(query: String): String = query
		.replace("\\", "\\\\")
		.replace("%", "\\%")
		.replace("_", "\\_")

	private fun snippet(content: String, query: String): String {
		val clean = content.replace(Regex("\\s+"), " ").trim()
		val at = clean.indexOf(query, ignoreCase = true).coerceAtLeast(0)
		val from = (at - 48).coerceAtLeast(0)
		val to = (at + query.length + 96).coerceAtMost(clean.length)
		val prefix = if (from > 0) "…" else ""
		val suffix = if (to < clean.length) "…" else ""
		return prefix + clean.substring(from, to) + suffix
	}

	fun observeConversation(id: String): Flow<Conversation?> =
		db.messages().observeAll(id).map { all ->
			val entity = db.conversations().byId(id) ?: return@map null
			Conversation(entity, store.activePath(all))
		}

	suspend fun conversation(id: String): Conversation? {
		val entity = db.conversations().byId(id) ?: return null
		val all = db.messages().all(id)
		return Conversation(entity, store.activePath(all))
	}

	/**
	 * The conversation row alone.
	 *
	 * Callers that only need the title, the provider or the workspace id used
	 * [conversation], which also reads and walks the whole transcript -- a
	 * transcript of tool results included. This is the query those callers
	 * actually wanted.
	 */
	suspend fun conversationEntity(id: String): ConversationEntity? = db.conversations().byId(id)

	/**
	 * The active path's structural rows for one conversation.
	 *
	 * Feeds the post-turn rewind gate, which needs branch pointers, tool calls
	 * and the `mutated` flag -- not the text of every message.
	 */
	suspend fun turnRows(conversationId: String): List<MessageTurnRow> =
		ConversationStore.activePathOfTurnRows(db.messages().turnRows(conversationId))

	/** The derived request summary for a conversation, if one is active. */
	suspend fun contextSummary(conversationId: String): com.inhuman.core.data.db.ContextSummaryEntity? =
		db.contextSummaries().byConversation(conversationId)

	/**
	 * Replace the conversation's context projection.
	 *
	 * Only the summary row changes; no message is edited or deleted. A later
	 * [clearContextSummary] therefore restores the full-transcript request.
	 *
	 * The extra fields are optional so the classic manual compaction keeps
	 * working unchanged: it writes `kind = "manual"` with no coverage recorded.
	 * The continuous-session maintenance path fills them in.
	 */
	suspend fun saveContextSummary(
		conversationId: String,
		throughMessageId: String,
		summary: String,
		kind: String = "manual",
		coveredTurns: Int? = null,
		coveredMessages: Int? = null,
		summaryTokens: Int? = null,
		truncated: Boolean = false,
	) {
		val now = System.currentTimeMillis()
		db.contextSummaries().upsert(
			com.inhuman.core.data.db.ContextSummaryEntity(
				conversationId = conversationId,
				throughMessageId = throughMessageId,
				summary = summary,
				kind = kind,
				coveredTurns = coveredTurns,
				coveredMessages = coveredMessages,
				summaryTokens = summaryTokens,
				truncated = truncated,
				createdAt = now,
				updatedAt = now,
			),
		)
	}

	suspend fun clearContextSummary(conversationId: String) {
		db.contextSummaries().clear(conversationId)
	}

	/**
	 * Record one maintenance run.
	 *
	 * Append-only and separate from the projection: the row must survive a
	 * rewind because the request it paid for has already happened. It does
	 * **not** touch the conversation's `updatedAt`; a background run must not
	 * move the conversation to the top of the list.
	 */
	suspend fun recordMaintenanceRun(run: MaintenanceRunEntity) {
		db.maintenanceRuns().upsert(run)
	}

	/** The cost ledger for one conversation, newest first. */
	suspend fun maintenanceRuns(conversationId: String): List<MaintenanceRunEntity> =
		db.maintenanceRuns().byConversation(conversationId)

	/** Every recorded run, newest first, for the usage overview. */
	suspend fun allMaintenanceRuns(): List<MaintenanceRunEntity> = db.maintenanceRuns().all()

	/** The workspace for a conversation, opened and ready for tools. */
	fun workspace(conversation: ConversationEntity) = workspace(conversation.workspaceId)

	/** Open a workspace by id, for the Files screen when no conversation is open. */
	fun workspace(workspaceId: String) =
		SessionWorkspace.open(paths.workspaceDir(workspaceId))

	fun observeWorkspace(workspaceId: String): Flow<WorkspaceEntity?> =
		db.workspaces().observe(workspaceId)

	/** The workspace row, for lifetime and display decisions. */
	suspend fun workspaceRecord(workspaceId: String): WorkspaceEntity? =
		db.workspaces().byId(workspaceId)

	/** The absolute app-private directory shown to the user, never to the agent. */
	fun workspacePath(workspaceId: String): String =
		paths.workspaceDir(workspaceId).absolutePath

	fun observePermanentWorkspaces(): Flow<List<WorkspaceEntity>> =
		db.workspaces().observePermanent()

	fun observeWorkspaces(): Flow<List<WorkspaceEntity>> =
		db.workspaces().observeAll()

	/**
	 * Every workspace, for a picker that asks once.
	 *
	 * The observe variant is for screens that stay open; the cross-workspace
	 * import dialogue reads on open and is gone before a change could matter.
	 */
	suspend fun workspaces(): List<WorkspaceEntity> = db.workspaces().all()

	suspend fun workspaceConversations(workspaceId: String): List<ConversationEntity> =
		db.conversations().byWorkspace(workspaceId)

	fun observeWorkspaceConversations(workspaceId: String): Flow<List<ConversationEntity>> =
		db.conversations().observeByWorkspace(workspaceId)

	suspend fun workspaceConversationCount(workspaceId: String): Int =
		db.workspaces().conversationCount(workspaceId)

	/**
	 * Create a permanent workspace. It has no conversation yet and must survive
	 * the orphan sweep, so the row is written before the directory is created.
	 */
	suspend fun createWorkspace(name: String): WorkspaceEntity {
		val now = System.currentTimeMillis()
		val workspace = WorkspaceEntity(
			id = UUID.randomUUID().toString(),
			name = name.trim().ifBlank { "Untitled workspace" },
			isPermanent = true,
			ownerConversationId = null,
			createdAt = now,
			updatedAt = now,
		)
		db.workspaces().upsert(workspace)
		paths.workspaceDir(workspace.id).mkdirs()
		return workspace
	}

	/**
	 * Turn a conversation's temporary workspace into a permanent one.
	 *
	 * Only a row changes. The directory name is the workspace id and stays where
	 * it is, so a promoted workspace is immediately the same thing as one
	 * created from the Workspaces screen. The action is idempotent.
	 */
	suspend fun promoteWorkspace(conversation: ConversationEntity, name: String): WorkspaceEntity? {
		val workspace = db.workspaces().byId(conversation.workspaceId) ?: return null
		if (workspace.isPermanent) return workspace
		val now = System.currentTimeMillis()
		paths.workspaceDir(workspace.id).mkdirs()
		db.workspaces().promote(
			id = workspace.id,
			name = name.trim().ifBlank { conversation.title.ifBlank { "Workspace" } },
			now = now,
		)
		return db.workspaces().byId(workspace.id)
	}

	suspend fun renameWorkspace(workspace: WorkspaceEntity, name: String) {
		val clean = name.trim()
		if (clean.isBlank()) return
		db.workspaces().rename(workspace.id, clean, System.currentTimeMillis())
	}

	/**
	 * Delete an empty permanent workspace.
	 *
	 * Refuses while any conversation still uses it. The alternative -- a cascade
	 * -- would turn one tap into the deletion of every transcript in the
	 * workspace along with files the user may have spent hours producing; that is
	 * a separate, explicit action if it is ever added.
	 */
	suspend fun deleteWorkspace(workspace: WorkspaceEntity): Boolean =
		withContext(Dispatchers.IO) {
			if (!workspace.isPermanent) return@withContext false
			if (db.workspaces().conversationCount(workspace.id) > 0) return@withContext false
			paths.workspaceDir(workspace.id).deleteRecursively()
			db.workspaces().delete(workspace.id)
			true
		}

	/**
	 * The approval rules.
	 *
	 * Reached through this repository rather than constructed by the view model
	 * so that both use the one `InhumanDatabase` the host passed in, rather than
	 * opening a second instance against the same file.
	 */
	fun policies(): PolicyRepository = policyRepository

	private val policyRepository by lazy { PolicyRepository(db) }

	/**
	 * The approval audit.
	 *
	 * Reached through this repository for the same reason as [policies]: both
	 * share the one `InhumanDatabase` the host opened.
	 */
	fun approvals(): ApprovalAuditRepository = approvalRepository

	private val approvalRepository by lazy { ApprovalAuditRepository(db) }

	/**
	 * The saved system prompts.
	 *
	 * Reached through this repository for the same reason as the others: one
	 * Room instance per process. The library is global -- a prompt is not tied
	 * to a conversation -- but the host still opens one database.
	 */
	fun savedPrompts(): SavedPromptRepository = savedPromptRepository

	private val savedPromptRepository by lazy { SavedPromptRepository(db) }

	suspend fun rename(id: String, title: String) {
		val now = System.currentTimeMillis()
		db.conversations().rename(id, title, now)
		// A temporary workspace is displayed under its conversation's title (the
		// documents provider uses that name), so keep the two in step. A
		// permanent workspace has its own name and must not follow the title.
		val conversation = db.conversations().byId(id) ?: return
		val workspace = db.workspaces().byId(conversation.workspaceId) ?: return
		if (!workspace.isPermanent) {
			db.workspaces().rename(workspace.id, title, now)
		}
	}

	suspend fun setPinned(id: String, pinned: Boolean) = db.conversations().setPinned(id, pinned)

	suspend fun setModel(id: String, model: String?) = db.conversations().setModel(id, model)

	suspend fun setProvider(id: String, providerId: String) =
		db.conversations().setProvider(id, providerId)

	suspend fun setReasoning(id: String, effort: String?, thinking: Boolean) =
		db.conversations().setReasoning(id, effort, thinking)

	/**
	 * Replace the conversation's tool selection.
	 *
	 * The list is the whole selection, not a delta: `null` means "all" at the
	 * column level, but an edited conversation stores an explicit list so a
	 * later release adding a tool does not silently hand it to this one.
	 */
	suspend fun setTools(id: String, tools: List<String>) =
		db.conversations().setTools(id, ToolsColumn.encode(tools))

	/**
	 * Store the pinned workspace-instructions block.
	 *
	 * Null clears the pin ("not pinned yet"); the empty string is the pinned
	 * "no file" state. Kept separate from [setTools] because a conversation's
	 * instructions and its tools are independent snapshots.
	 */
	suspend fun setWorkspaceInstructions(id: String, block: String?) =
		db.conversations().setWorkspaceInstructions(id, block)

	/**
	 * Switch how this conversation maintains its projection.
	 *
	 * Normalised through [SessionMode.parse] for the same reason as [create]:
	 * an unknown value must not turn automatic maintenance on.
	 */
	suspend fun setSessionMode(id: String, mode: String) =
		db.conversations().setSessionMode(id, SessionMode.parse(mode))

	suspend fun setSshAllowed(id: String, allowed: Boolean) =
		db.conversations().setSshAllowed(id, allowed)

	suspend fun sshAllowed(id: String): Boolean? = db.conversations().sshAllowed(id)

	/**
	 * Persist this conversation's URL-download acknowledgement.
	 *
	 * A capability grant, not a per-call approval: after it, later `download_url`
	 * calls in this conversation follow the ordinary tool policy. Revoked by the
	 * chat menu, and cleared for every conversation when the global switch goes
	 * off -- the same rule as SSH, for the same reason ("consent that survives
	 * the capability being switched off is consent the user did not give").
	 */
	suspend fun setDownloadAllowed(id: String, allowed: Boolean) =
		db.conversations().setDownloadAllowed(id, allowed)

	suspend fun downloadAllowed(id: String): Boolean? =
		db.conversations().downloadAllowed(id)

	/** Turning the global download switch off revokes every conversation's consent. */
	suspend fun clearAllDownloadAllowed() = db.conversations().clearAllDownloadAllowed()

	/**
	 * Replace the set of saved hosts this conversation may use.
	 *
	 * The whole set, not a delta, and empty means none: a conversation that has
	 * not been given a host gets no SSH tool at all. Only the chat's host sheet
	 * writes this; no tool and no model output can widen it.
	 */
	suspend fun setSshHosts(id: String, hosts: List<String>) =
		db.conversations().setSshHosts(id, HostsColumn.encode(hosts))

	suspend fun sshHosts(id: String): List<String> =
		db.conversations().sshHosts(id)?.let(HostsColumn::decode).orEmpty()

	/** Turning the global SSH switch off revokes every conversation's consent. */
	suspend fun clearAllSshAllowed() = db.conversations().clearAllSshAllowed()

	/**
	 * Create a conversation bound to an assistant.
	 *
	 * The assistant's model and reasoning settings are copied onto the
	 * conversation rather than read through the agent later. That copy is what
	 * makes editing an assistant safe: a model change must not silently rewrite
	 * conversations already held with it, because a transcript is a record of
	 * what was actually said and the settings that produced it are part of it.
	 *
	 * [sessionMode] defaults to [SessionMode.CLASSIC] and is normalised through
	 * [SessionMode.parse]. The safe direction matters: a caller that forgets to
	 * pass the user's choice gets a conversation that does no automatic
	 * maintenance, rather than one that starts spending on its own.
	 */
	suspend fun create(
		title: String = "New conversation",
		agent: com.inhuman.core.data.db.AgentEntity? = null,
		workspaceId: String? = null,
		sessionMode: String = SessionMode.CLASSIC,
	): ConversationEntity {
		val now = System.currentTimeMillis()
		val conversationId = UUID.randomUUID().toString()
		val resolvedWorkspace = workspaceId ?: UUID.randomUUID().toString()

		if (workspaceId == null) {
			val workspace = WorkspaceEntity(
				id = resolvedWorkspace,
				name = title,
				isPermanent = false,
				ownerConversationId = conversationId,
				createdAt = now,
				updatedAt = now,
			)
			db.workspaces().upsert(workspace)
		} else {
			// A caller inside a workspace may not invent an id. Reusing a
			// temporary workspace is allowed only for its owner, and a permanent
			// one is shared deliberately.
			val workspace = db.workspaces().byId(resolvedWorkspace)
				?: error("Workspace $resolvedWorkspace does not exist.")
			if (!workspace.isPermanent && workspace.ownerConversationId != conversationId) {
				error("A temporary workspace can only be used by the conversation that owns it.")
			}
		}

		val conversation = ConversationEntity(
			id = conversationId,
			title = title,
			// Opaque and stable. Never derived from the title: renaming would
			// then have to move the directory, and a failed move orphans files.
			workspaceId = resolvedWorkspace,
			agentId = agent?.id,
			providerId = agent?.providerId ?: com.inhuman.core.data.db.ProviderIds.DEEPSEEK,
			model = agent?.model,
			reasoningEffort = agent?.reasoningEffort,
			thinking = agent?.thinking ?: false,
			// Copied, not read through the assistant later: editing an
			// assistant's tools must not change what a conversation already
			// held is able to do.
			toolsJson = ToolsColumn.encode(agent?.tools),
			sessionMode = SessionMode.parse(sessionMode),
			createdAt = now,
			updatedAt = now,
		)
		paths.workspaceDir(resolvedWorkspace).mkdirs()
		db.conversations().upsert(conversation)
		return conversation
	}

	suspend fun appendMessage(message: MessageEntity, parentId: String?) =
		store.append(message, parentId)

	/**
	 * Delete a conversation, its messages and, when temporary, its workspace.
	 *
	 * A permanent workspace is deliberately left alone: its files belong to the
	 * workspace, not to this transcript, and another conversation may still be
	 * using it. A temporary workspace is owned by this conversation and goes
	 * with it.
	 *
	 * Files are removed before the rows for a temporary workspace. If the
	 * process dies in between, the result is an orphaned directory that
	 * [sweepOrphanedWorkspaces] finds; the other order would leave rows pointing
	 * at a directory that is already gone, and a file tool would then fail on a
	 * conversation the user can still open.
	 */
	suspend fun delete(conversation: ConversationEntity) = withContext(Dispatchers.IO) {
		// The filesystem walk is the expensive part: a workspace may hold
		// thousands of files. This used to run on the caller's dispatcher, which
		// for the view models is Main, so deleting a large conversation could
		// block the frame -- or trip an ANR -- before the row disappeared.
		val workspace = db.workspaces().byId(conversation.workspaceId)
		val temporary = workspace == null || !workspace.isPermanent
		if (temporary) {
			paths.workspaceDir(conversation.workspaceId).deleteRecursively()
		}
		rollbackDir(conversation.id).deleteRecursively()
		store.deleteConversation(conversation.id)
		// The row goes last: while it exists the sweep knows this directory is
		// not an orphan, and a retry can finish the delete.
		if (temporary) db.workspaces().delete(conversation.workspaceId)
	}

	/** Delete several conversations, keeping the permanent-workspace rules. */
	suspend fun deleteConversations(conversations: List<ConversationEntity>): Int {
		var deleted = 0
		conversations.distinctBy { it.id }.forEach { conversation ->
			runCatching { delete(conversation) }
				.onSuccess { deleted++ }
				.onFailure { Diag.w("conv", "bulk delete failed id=${conversation.id}", it) }
		}
		return deleted
	}

	/**
	 * Delete permanent workspaces and everything inside them.
	 *
	 * This is the explicit bulk action that the single-workspace delete
	 * deliberately refuses while conversations remain. The caller must name the
	 * conversation count in its confirmation; this method then removes those
	 * conversations (their rollback snapshots included), the workspace
	 * directories, and finally the workspace rows.
	 */
	suspend fun deleteWorkspaces(workspaces: List<WorkspaceEntity>): WorkspaceBulkDelete =
		withContext(Dispatchers.IO) {
			val targets = workspaces.filter { it.isPermanent }.distinctBy { it.id }
			val ids = targets.mapTo(mutableSetOf()) { it.id }
			if (ids.isEmpty()) return@withContext WorkspaceBulkDelete(0, 0)
			val conversations = db.conversations().all().filter { it.workspaceId in ids }
			var deletedWorkspaces = 0
			var deletedConversations = 0
			targets.forEach { workspace ->
				var clean = true
				conversations.filter { it.workspaceId == workspace.id }.forEach { conversation ->
					runCatching { delete(conversation) }
						.onSuccess { deletedConversations++ }
						.onFailure {
							clean = false
							Diag.w("workspace", "bulk delete conversation failed id=${conversation.id}", it)
						}
				}
				if (!clean) return@forEach
				runCatching {
					paths.workspaceDir(workspace.id).deleteRecursively()
					db.workspaces().delete(workspace.id)
				}.onSuccess { deletedWorkspaces++ }
					.onFailure {
						Diag.w("workspace", "bulk delete failed id=${workspace.id}", it)
					}
			}
			WorkspaceBulkDelete(deletedWorkspaces, deletedConversations)
		}


	/**
	 * Remove workspace directories that no workspace row refers to.
	 *
	 * ## Why this is needed rather than merely tidy
	 *
	 * Deleting a conversation removes its directory first and its rows second,
	 * deliberately -- see [delete]. If the process is killed in the window
	 * between the two, the rows survive and the directory does not, which is
	 * recoverable. But a delete can also be interrupted the other way by a
	 * crash *after* the rows go and before the directory does, and the
	 * workspace of a conversation that was abandoned mid-creation leaves one
	 * too.
	 *
	 * ## Permanent workspaces are not orphans
	 *
	 * The directory is kept if **any workspace row** names its id, not if a
	 * conversation does. A permanent workspace with no conversations is a
	 * valid state -- the user created it and has not started a session yet --
	 * and deleting it at startup would be the bug this method exists to avoid.
	 *
	 * A temporary workspace whose owner conversation is gone is the one case
	 * that is genuinely stale. Its row and directory are both removed here, so
	 * a crash during delete converges on the next launch.
	 *
	 * @return the ids of the directories it removed, for a caller that wants to
	 *   say so. Empty on a healthy install, which is the normal case.
	 */
	suspend fun sweepOrphanedWorkspaces(): List<String> {
		val conversations = db.conversations().all()
		val conversationIds = conversations.mapTo(mutableSetOf()) { it.id }
		val workspaces = db.workspaces().all().toMutableList()

		val staleTemp = workspaces.filter {
			!it.isPermanent && (it.ownerConversationId == null || it.ownerConversationId !in conversationIds)
		}
		staleTemp.forEach { workspace ->
			runCatching {
				paths.workspaceDir(workspace.id).deleteRecursively()
			}
			db.workspaces().delete(workspace.id)
		}
		val staleIds = staleTemp.mapTo(mutableSetOf()) { it.id }
		workspaces.removeAll { it.id in staleIds }

		val known = workspaces.mapTo(mutableSetOf()) { it.id }
		val doomed = orphanedWorkspaces(paths.workspacesRoot, known)

		doomed.forEach { dir ->
			runCatching { dir.deleteRecursively() }
		}
		return (doomed.map { it.name } + staleIds).distinct()
	}

	companion object {
		/**
		 * Which directories have no conversation behind them.
		 *
		 * Split out from [sweepOrphanedWorkspaces] because this is the part that
		 * decides what to delete, and it is the part that is wrong if the rule
		 * about UUIDs ever stops holding. It touches no database and no Android
		 * API, so it is unit-tested; the deleting is a one-liner on top.
		 */
		fun orphanedWorkspaces(root: File, knownWorkspaceIds: Set<String>): List<File> =
			root.listFiles().orEmpty()
				.filter { it.isDirectory && it.name !in knownWorkspaceIds }
	}

	/**
	 * Choose which reply to show under a message.
	 *
	 * The tree stores parent links plus a `selectedChildId` on each node, so
	 * switching a branch is one column on one row -- nothing is copied and
	 * nothing is deleted. The reply that was showing stays exactly where it was
	 * and comes back if this is switched again.
	 *
	 * Switching the selection changes the active path below it, so a branch
	 * chosen three replies up changes the whole tail of the conversation. That
	 * is the intended meaning: the alternatives are different continuations of
	 * the same point, not different annotations on one message.
	 */
	suspend fun selectBranch(parentId: String, childId: String) {
		db.messages().setSelectedChild(parentId, childId)
	}

	/**
	 * Delete one message and every descendant below it.
	 *
	 * A conversation rollback is a **tree** operation, not a row delete: a user
	 * message owns the assistant reply, its tool calls, the tool results and
	 * anything after them. Removing only the user row would leave an orphaned
	 * tail that no longer has a question, and the next request would send an
	 * assistant/tool sequence with no opening user message.
	 *
	 * The parent's selected child is cleared. `ConversationStore.activePath`
	 * then falls back to the newest surviving child, which is the branch the
	 * user was looking at before the rolled-back one; when there is no sibling,
	 * the parent becomes the end of the transcript.
	 *
	 * The workspace is deliberately untouched. A rollback rewinds the
	 * conversation so the user can re-edit the message; it is not a file-system
	 * undo, and the UI says so.
	 */
	suspend fun deleteSubtree(rootId: String, parentId: String?, conversationId: String) {
		val all = db.messages().all(conversationId)
		if (all.none { it.id == rootId }) return
		val doomed = descendantIds(all, rootId)
		transaction {
			for (id in doomed) db.messages().delete(id)
			if (parentId != null) db.messages().setSelectedChild(parentId, null)
			db.conversations().touch(conversationId, System.currentTimeMillis())
		}
	}

	/**
	 * Delete everything below [afterId] but keep the boundary row.
	 *
	 * Used by mid-turn regeneration: the selected tool result stays, the next
	 * assistant/tool subtree goes, and the parent's selected child is cleared
	 * so the new reply can occupy the same place.
	 */
	suspend fun deleteDescendants(afterId: String, conversationId: String) {
		val all = db.messages().all(conversationId)
		if (all.none { it.id == afterId }) return
		val doomed = descendantIds(all, afterId) - afterId
		transaction {
			for (id in doomed) db.messages().delete(id)
			db.messages().setSelectedChild(afterId, null)
			db.conversations().touch(conversationId, System.currentTimeMillis())
		}
	}

	/**
	 * Run [block] in one write transaction.
	 *
	 * `RoomDatabase.withTransaction` is an Android-only room-ktx extension, so it
	 * cannot live in this module. `useWriterConnection` plus an immediate
	 * transaction is the same operation through the KMP API: BEGIN IMMEDIATE
	 * avoids the upgrade deadlock a deferred transaction can hit when two
	 * writers both try to go from a read to a write lock.
	 */
	private suspend fun <R> transaction(block: suspend () -> R): R =
		db.useWriterConnection { transactor ->
			transactor.immediateTransaction { block() }
		}

	private fun descendantIds(all: List<MessageEntity>, rootId: String): Set<String> {
		val doomed = mutableSetOf(rootId)
		var changed = true
		while (changed) {
			changed = false
			for (message in all) {
				if (message.parentId in doomed && doomed.add(message.id)) {
					changed = true
				}
			}
		}
		return doomed
	}

	private fun rollbackDir(conversationId: String): File =
		paths.rollbackDir(conversationId)

	suspend fun hasStartCheckpoint(conversationId: String, token: String): Boolean =
		hasCheckpoint(conversationId, token, SnapshotStore.START)

	/**
	 * Store the workspace before the first mutating call of a turn.
	 *
	 * The caller refuses the mutation when this returns false. A mutating turn
	 * without a start checkpoint is exactly the case rollback must not silently
	 * delete.
	 */
	suspend fun captureStartCheckpoint(conversation: ConversationEntity, token: String): Boolean =
		withContext(Dispatchers.IO) {
			SnapshotStore.capture(
				workspaceRoot = workspace(conversation).rootPath(),
				turnDir = SnapshotStore.turnDir(rollbackDir(conversation.id), token),
				checkpointId = SnapshotStore.START,
			)
		}

	/** Store the workspace after a completed tool group. */
	suspend fun captureCheckpoint(
		conversation: ConversationEntity,
		token: String,
		checkpointId: String,
	): Boolean = withContext(Dispatchers.IO) {
		SnapshotStore.capture(
			workspaceRoot = workspace(conversation).rootPath(),
			turnDir = SnapshotStore.turnDir(rollbackDir(conversation.id), token),
			checkpointId = checkpointId,
		)
	}

	suspend fun hasCheckpoint(
		conversationId: String,
		token: String,
		checkpointId: String,
	): Boolean = withContext(Dispatchers.IO) {
		SnapshotStore.hasCheckpoint(
			SnapshotStore.turnDir(rollbackDir(conversationId), token),
			checkpointId,
		)
	}

	/** Put the workspace back to a checkpoint, or false and leave rows untouched. */
	suspend fun restoreCheckpoint(
		conversation: ConversationEntity,
		token: String,
		checkpointId: String,
	): Boolean = withContext(Dispatchers.IO) {
		SnapshotStore.restore(
			workspaceRoot = workspace(conversation).rootPath(),
			turnDir = SnapshotStore.turnDir(rollbackDir(conversation.id), token),
			checkpointId = checkpointId,
		)
	}

	/** The turn's changed paths, computed against its start checkpoint. */
	suspend fun workspaceChanges(
		conversation: ConversationEntity,
		token: String,
		checkpointId: String = SnapshotStore.START,
	): List<WorkspaceChange> = withContext(Dispatchers.IO) {
		SnapshotStore.diff(
			workspaceRoot = workspace(conversation).rootPath(),
			turnDir = SnapshotStore.turnDir(rollbackDir(conversation.id), token),
			checkpointId = checkpointId,
		)
	}

	/** The retained turns, newest first, for the snapshots dialog. */
	suspend fun snapshotTurns(conversationId: String): List<SnapshotTurn> =
		withContext(Dispatchers.IO) { SnapshotStore.turns(rollbackDir(conversationId)) }

	/** Keep only the newest [keepTurns] turns, including the current one. */
	suspend fun pruneSnapshots(conversationId: String, keepTurns: Int) =
		withContext(Dispatchers.IO) {
			SnapshotStore.prune(rollbackDir(conversationId), keepTurns)
		}

	suspend fun deleteTurnSnapshot(conversationId: String, token: String) =
		withContext(Dispatchers.IO) {
			SnapshotStore.deleteTurn(rollbackDir(conversationId), token)
		}

	/** Remove checkpoints the rewritten transcript no longer refers to. */
	suspend fun retainCheckpoints(
		conversationId: String,
		token: String,
		keep: Set<String>,
	) = withContext(Dispatchers.IO) {
		SnapshotStore.retainCheckpoints(
			SnapshotStore.turnDir(rollbackDir(conversationId), token),
			keep,
		)
	}

	suspend fun setChangedFiles(messageId: String, json: String?) =
		db.messages().setChangedFiles(messageId, json)

	/**
	 * Record that a reply changed the workspace.
	 *
	 * Called after its tools have run, which is after the row was written --
	 * the reply is stored when the model stops streaming, and the tools it asked
	 * for are executed once the stream is closed.
	 */
	suspend fun markMutated(messageId: String) = db.messages().markMutated(messageId)

	/**
	 * Where every branch point in a conversation stands, keyed by the parent.
	 *
	 * ## Why this replaced a per-message lookup
	 *
	 * The screen wants a [Branches] for each message that has alternatives --
	 * which is almost none of them. Asking [branches] once per message cost a
	 * query for the children of every message plus a second for the parent row
	 * whenever there happened to be more than one, so a two-hundred-message
	 * conversation paid ~400 round trips to be told "no" 200 times.
	 *
	 * One query finds the parents that actually branch, and the detailed
	 * question is asked only for those. The result is keyed by **parent** id
	 * rather than by message id, which is also the more honest key: the
	 * alternatives belong to the point in the conversation, not to any one of
	 * the replies, and a row looks its own up by `parentId` -- so the switcher
	 * lands under the message below the branch point, where it belongs.
	 */
	suspend fun branchesIn(conversationId: String): Map<String, Branches> =
		db.messages().branchParents(conversationId)
			.mapNotNull { parentId -> branches(parentId)?.let { parentId to it } }
			.toMap()

	/**
	 * The replies to a message, oldest first, with the selected one marked.
	 *
	 * @return null when [parentId] is null, which is the first message in a
	 *   conversation: it has no siblings by definition, and a switcher offering
	 *   to move between them would be offering nothing.
	 */
	suspend fun branches(parentId: String?): Branches? {
		if (parentId == null) return null
		val children = db.messages().childrenOf(parentId)
		if (children.size < 2) return null

		// Which child is showing. A parent with no selection set yet falls back
		// to the newest, matching ConversationStore.activePath -- if the two
		// disagreed, the switcher would highlight a reply that is not on screen.
		val parent = db.messages().byId(parentId)
		val selectedId = parent?.selectedChildId
			?.takeIf { id -> children.any { it.id == id } }
			?: children.last().id

		val index = children.indexOfFirst { it.id == selectedId }
		if (index < 0) return null

		return Branches(
			index = index,
			total = children.size,
			previousId = children.getOrNull(index - 1)?.id,
			nextId = children.getOrNull(index + 1)?.id,
		)
	}

	/**
	 * Derive a title from the first user message.
	 *
	 * Truncated rather than summarised: a summary would be another model call
	 * for a line of text, and the point is only that the list is scannable.
	 */
	suspend fun autoTitle(id: String, firstUserMessage: String) {
		val title = firstUserMessage
			.trim()
			.replace(Regex("\\s+"), " ")
			.take(48)
			.let { if (firstUserMessage.trim().length > 48) "$it…" else it }
		if (title.isNotBlank()) rename(id, title)
	}
}
