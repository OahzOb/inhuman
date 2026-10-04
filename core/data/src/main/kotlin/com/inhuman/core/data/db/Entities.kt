package com.inhuman.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A conversation.
 *
 * [workspaceId] is the directory name on disk and is generated once at creation
 * from a UUID. It is deliberately **not** the primary key: the id is the
 * database's business, the workspace directory is the filesystem's, and tying
 * them together means a future change to either forces a migration of the
 * other.
 *
 * It is also stable and opaque rather than derived from the title. A directory
 * named after a title would have to be renamed whenever the user renamed the
 * conversation, and every rename is a chance to orphan the files inside it.
 */
@Entity(
	tableName = "conversations",
	indices = [Index(value = ["workspaceId"], unique = true)],
)
data class ConversationEntity(
	@PrimaryKey val id: String,
	val title: String,
	/** Directory name under filesDir/conversations/. Opaque, never shown. */
	val workspaceId: String,
	/** Model id in use, or null to follow the default. */
	val model: String? = null,
	val createdAt: Long,
	val updatedAt: Long,
	val pinned: Boolean = false,
)

/**
 * One message, as a node in a tree.
 *
 * ## Why a node rather than a row per message
 *
 * The flat shape -- `messages(conversation_id, role, content)` ordered by
 * timestamp -- cannot express the three things a chat client is expected to do:
 * regenerate a reply, edit a message and re-run from there, and switch between
 * the results. All three produce a *branch*: several assistant replies for one
 * user turn, of which one is currently shown.
 *
 * Modelling that as a list means either duplicating the prefix on every
 * regeneration or bolting on a side table later. Modelling it as a tree makes
 * it fall out: [parentId] links a message to the turn it answers, and
 * [ConversationEntity]'s active path is the chain from a leaf to the root.
 *
 * [selectedChildId] is what records which branch is currently shown. It lives
 * on the parent because "which of my children is active" is a property of the
 * parent, not of the children.
 *
 * Retrofitting this is painful, which is why it is here in the first schema
 * rather than after a simpler version has been shipped.
 */
@Entity(
	tableName = "messages",
	foreignKeys = [
		ForeignKey(
			entity = ConversationEntity::class,
			parentColumns = ["id"],
			childColumns = ["conversationId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index("conversationId"),
		Index("parentId"),
	],
)
data class MessageEntity(
	@PrimaryKey val id: String,
	val conversationId: String,
	/** The message this one answers, or null for the first turn. */
	val parentId: String?,
	/** Role as the wire format names it: user, assistant, system, tool. */
	val role: String,
	/** Visible answer text. */
	val content: String,
	/**
	 * Reasoning, kept because dropping it breaks replay.
	 *
	 * Measured against the live API: replaying a transcript that contains a
	 * thinking turn without its `reasoning_content` is rejected with HTTP 400,
	 * and the deciding factor is the previous turn's thinking state rather than
	 * whether tools were used. So this is not display state that can be
	 * discarded after rendering -- it is part of the request.
	 */
	val reasoning: String? = null,
	val model: String? = null,
	val finishReason: String? = null,
	val createdAt: Long,

	// Token accounting. Nullable because not every provider reports it, and a
	// zero would be indistinguishable from "not reported".
	val promptTokens: Int? = null,
	val completionTokens: Int? = null,
	val cachedPromptTokens: Int? = null,
	val reasoningTokens: Int? = null,

	/** Which child branch is currently shown beneath this message. */
	val selectedChildId: String? = null,
	/** Set when the turn ended in an error, so the UI can mark it. */
	val error: String? = null,
)
