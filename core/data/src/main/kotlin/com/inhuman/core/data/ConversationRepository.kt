package com.inhuman.core.data

import android.content.Context
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.ConversationStore
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.ToolsColumn
import com.inhuman.core.files.SessionWorkspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 * The application's view of stored conversations.
 *
 * Owns three things that must stay consistent with each other:
 *
 *  1. the rows,
 *  2. the workspace directory each conversation owns,
 *  3. the ordering rules for a branch.
 *
 * They are together because they are created together and deleted together. A
 * conversation whose directory outlived its row would leak files forever, and
 * one whose row outlived its directory would fail on the first file tool.
 */
class ConversationRepository(
	context: Context,
	private val db: InhumanDatabase = Database.open(context),
) {

	/**
	 * The application context, never the one that was passed in.
	 *
	 * A caller inside a composable hands over `LocalContext.current`, which is
	 * the **Activity**. This repository is held by a view model, which outlives
	 * a configuration change, so storing the Activity meant a rotated screen
	 * left the destroyed one -- and its whole view hierarchy -- reachable until
	 * the process ended. `SecureStore` and `Database` already take the
	 * application context; this makes the repositories do the same rather than
	 * leaving the rule to whoever happens to call them.
	 */
	private val appContext: Context = context.applicationContext

	private val store = ConversationStore(db)

	fun observeAll(): Flow<List<ConversationEntity>> = db.conversations().observeAll()

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

	/** The workspace for a conversation, opened and ready for tools. */
	fun workspace(conversation: ConversationEntity) =
		SessionWorkspace.open(Database.workspaceDir(appContext, conversation.workspaceId))

	/**
	 * The approval rules.
	 *
	 * Reached through this repository rather than constructed by the view model
	 * so that both share the one `InhumanDatabase` instance -- `Database.open`
	 * is a singleton, but a second `PolicyRepository(context)` would rely on
	 * that being true rather than being handed the same object.
	 */
	fun policies(): PolicyRepository = policyRepository

	private val policyRepository by lazy { PolicyRepository(db) }

	suspend fun rename(id: String, title: String) =
		db.conversations().rename(id, title, System.currentTimeMillis())

	suspend fun setPinned(id: String, pinned: Boolean) = db.conversations().setPinned(id, pinned)

	suspend fun setModel(id: String, model: String?) = db.conversations().setModel(id, model)

	suspend fun setReasoning(id: String, effort: String?, thinking: Boolean) =
		db.conversations().setReasoning(id, effort, thinking)

	suspend fun setSshAllowed(id: String, allowed: Boolean) =
		db.conversations().setSshAllowed(id, allowed)

	suspend fun sshAllowed(id: String): Boolean? = db.conversations().sshAllowed(id)

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
	 */
	suspend fun create(
		title: String = "New conversation",
		agent: com.inhuman.core.data.db.AgentEntity? = null,
	): ConversationEntity {
		val now = System.currentTimeMillis()
		val conversation = ConversationEntity(
			id = UUID.randomUUID().toString(),
			title = title,
			// Opaque and stable. Never derived from the title: renaming would
			// then have to move the directory, and a failed move orphans files.
			workspaceId = UUID.randomUUID().toString(),
			agentId = agent?.id,
			model = agent?.model,
			reasoningEffort = agent?.reasoningEffort,
			thinking = agent?.thinking ?: false,
			// Copied, not read through the assistant later: editing an
			// assistant's tools must not change what a conversation already
			// held is able to do.
			toolsJson = ToolsColumn.encode(agent?.tools),
			createdAt = now,
			updatedAt = now,
		)
		Database.workspaceDir(appContext, conversation.workspaceId).mkdirs()
		db.conversations().upsert(conversation)
		return conversation
	}

	suspend fun appendMessage(message: MessageEntity, parentId: String?) =
		store.append(message, parentId)

	/**
	 * Delete a conversation, its messages and its files.
	 *
	 * Files are removed before the rows. If the process dies in between, the
	 * result is an orphaned directory that [sweepOrphanedWorkspaces] finds; the
	 * other order would leave rows pointing at a directory that is already
	 * gone, and a file tool would then fail on a conversation the user can
	 * still open.
	 */
	suspend fun delete(conversation: ConversationEntity) {
		Database.workspaceDir(appContext, conversation.workspaceId).deleteRecursively()
		store.deleteConversation(conversation.id)
	}

	/**
	 * Remove workspace directories that no conversation refers to.
	 *
	 * ## Why this is needed rather than merely tidy
	 *
	 * Deleting a conversation removes its directory first and its rows second,
	 * deliberately -- see [delete]. If the process is killed in the window
	 * between the two, the rows survive and the directory does not, which is
	 * recoverable. But a delete can also be interrupted the other way by a
	 * crash *after* the rows go and before the directory does, and the
	 * workspace of a conversation that was abandoned mid-creation leaves one
	 * too. Each of those is megabytes of the user's files sitting in
	 * `filesDir/conversations` with nothing in the app that can see or delete
	 * them: the UI lists conversations, and there is no conversation.
	 *
	 * ## Why it is safe to run at startup
	 *
	 * A directory is kept if **any** conversation row names its `workspaceId`,
	 * and ids are UUIDs that are never reused. The only way to be wrong is to
	 * run this while a conversation exists in memory but not yet in the
	 * database -- which is why [ConversationRepository.create] writes the row
	 * before returning, and why this does not run until the first screen has
	 * loaded.
	 *
	 * @return the ids of the directories it removed, for a caller that wants to
	 *   say so. Empty on a healthy install, which is the normal case.
	 */
	suspend fun sweepOrphanedWorkspaces(): List<String> {
		val known = db.conversations().all().mapTo(mutableSetOf()) { it.workspaceId }
		val doomed = orphanedWorkspaces(Database.workspacesRoot(appContext), known)

		doomed.forEach { dir ->
			runCatching { dir.deleteRecursively() }
		}
		return doomed.map { it.name }
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
