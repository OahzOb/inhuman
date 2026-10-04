package com.inhuman.core.data

import android.content.Context
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.ConversationStore
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MessageEntity
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

	/**
	 * Create a conversation and its workspace together.
	 *
	 * The workspace is made eagerly rather than on first use. A conversation
	 * with no directory would look fine until the user asked the assistant to
	 * write something, and then fail at the least convenient moment.
	 */
	suspend fun create(title: String = "New conversation", model: String? = null): ConversationEntity {
		val now = System.currentTimeMillis()
		val conversation = ConversationEntity(
			id = UUID.randomUUID().toString(),
			title = title,
			// Opaque and stable. Never derived from the title: renaming would
			// then have to move the directory, and a failed move orphans files.
			workspaceId = UUID.randomUUID().toString(),
			model = model,
			createdAt = now,
			updatedAt = now,
		)
		Database.workspaceDir(context, conversation.workspaceId).mkdirs()
		db.conversations().upsert(conversation)
		return conversation
	}

	/** The workspace for a conversation, opened and ready for tools. */
	fun workspace(conversation: ConversationEntity) =
		SessionWorkspace.open(Database.workspaceDir(context, conversation.workspaceId))

	suspend fun rename(id: String, title: String) =
		db.conversations().rename(id, title, System.currentTimeMillis())

	suspend fun setPinned(id: String, pinned: Boolean) = db.conversations().setPinned(id, pinned)

	suspend fun setModel(id: String, model: String?) = db.conversations().setModel(id, model)

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
