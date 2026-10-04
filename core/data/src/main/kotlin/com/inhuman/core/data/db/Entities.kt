package com.inhuman.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A configured assistant.
 *
 * ## Why this is separate from a conversation
 *
 * A conversation is one thread; an agent is the standing configuration behind
 * it -- instructions, model, and how hard the model should think. Keeping them
 * apart is what lets someone have a "code review" assistant and a "translate"
 * assistant without either one's instructions leaking into the other, and lets
 * the same assistant be reused across many conversations.
 *
 * The conversation stores a copy of the model and effort rather than reading
 * them through the agent on every turn. That copy is what makes editing an
 * agent safe: changing an agent's model must not silently rewrite the settings
 * of conversations already had with it, because a transcript is a record of
 * what was actually said.
 */
@Entity(tableName = "agents")
data class AgentEntity(
	@PrimaryKey val id: String,
	val name: String,
	/**
	 * The instructions sent as the system message.
	 *
	 * Stored and shown verbatim. An assistant whose instructions a user cannot
	 * read is one they cannot trust or fix, and hiding them behind a preset
	 * name is how a prompt ends up doing something nobody intended.
	 */
	val systemPrompt: String,
	/** Model id, or null to use whatever the provider defaults to. */
	val model: String? = null,
	/**
	 * Reasoning effort, as the provider spells it, or null for the default.
	 *
	 * Free text rather than an enum on purpose: the levels are per-model and
	 * come from `GET /models`, so an enum here would be a hardcoded guess about
	 * someone else's API -- and would need a release to add a level.
	 */
	val reasoningEffort: String? = null,
	/** Whether thinking is on at all for this assistant. */
	val thinking: Boolean = false,
	val createdAt: Long,
	val updatedAt: Long,
	/** The one seeded on first launch. Cannot be deleted from the UI. */
	val isDefault: Boolean = false,
)

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
	indices = [
		Index(value = ["workspaceId"], unique = true),
		Index("agentId"),
	],
)
data class ConversationEntity(
	@PrimaryKey val id: String,
	val title: String,
	/** Directory name under filesDir/conversations/. Opaque, never shown. */
	val workspaceId: String,
	/** The assistant this conversation was started with. */
	val agentId: String? = null,
	/**
	 * Model in use, copied from the agent at creation and editable per
	 * conversation.
	 *
	 * Copied rather than joined so that editing an agent does not rewrite the
	 * settings of conversations already held -- what was said is a record, and
	 * the settings that produced it are part of that record.
	 */
	val model: String? = null,
	/** Reasoning effort for this conversation. Null means the model default. */
	val reasoningEffort: String? = null,
	val thinking: Boolean = false,
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

	/**
	 * Measured latency, stored because a number that is only computed live
	 * disappears the moment the message is reloaded -- and a user comparing two
	 * models wants yesterday's figures as much as this turn's.
	 */
	val timeToFirstTokenMs: Long? = null,
	val generationMs: Long? = null,

	/** Which child branch is currently shown beneath this message. */
	val selectedChildId: String? = null,
	/** Set when the turn ended in an error, so the UI can mark it. */
	val error: String? = null,

	/**
	 * Tool calls this assistant turn requested, as a JSON array.
	 *
	 * Stored as text rather than a child table because it is only ever read and
	 * written whole, alongside its message. A join would add a table and a
	 * query for something that is never queried independently.
	 */
	val toolCallsJson: String? = null,

	/**
	 * For a tool-result message: the id of the call it answers.
	 *
	 * The wire format requires every call to be answered, and answering the
	 * wrong one is worse than not answering -- the model then sees a result
	 * attached to an operation that did not produce it.
	 */
	val toolCallId: String? = null,

	/** For a tool-result message: which tool produced it, for display. */
	val toolName: String? = null,
)
