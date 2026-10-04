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
	private val context: Context,
	private val db: InhumanDatabase = Database.open(context),
) {

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
		SessionWorkspace.open(Database.workspaceDir(context, conversation.workspaceId))

	suspend fun rename(id: String, title: String) =
		db.conversations().rename(id, title, System.currentTimeMillis())

	suspend fun setPinned(id: String, pinned: Boolean) = db.conversations().setPinned(id, pinned)

	suspend fun setModel(id: String, model: String?) = db.conversations().setModel(id, model)

	suspend fun setReasoning(id: String, effort: String?, thinking: Boolean) =
		db.conversations().setReasoning(id, effort, thinking)

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
		Database.workspaceDir(context, conversation.workspaceId).mkdirs()
		db.conversations().upsert(conversation)
		return conversation
	}

	suspend fun appendMessage(message: MessageEntity, parentId: String?) =
		store.append(message, parentId)

	/**
	 * Delete a conversation, its messages and its files.
	 *
	 * Files are removed before the rows. If the process dies in between, the
	 * result is an orphaned directory that a later sweep can find; the other
	 * order would leave rows pointing at a directory that is already gone, and
	 * a file tool would then fail on a conversation the user can still open.
	 */
	suspend fun delete(conversation: ConversationEntity) {
		Database.workspaceDir(context, conversation.workspaceId).deleteRecursively()
		store.deleteConversation(conversation.id)
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
