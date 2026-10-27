package com.inhuman.core.data.db

import androidx.room.ColumnInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Stable ids and wire names for the provider profiles. */
object ProviderIds {
	/** The seeded DeepSeek profile. It uses the legacy chat-key slot. */
	const val DEEPSEEK = "deepseek"
}

object ProviderKinds {
	const val DEEPSEEK = "deepseek"
	const val OPENAI_COMPATIBLE = "openai-compatible"
	/** Anthropic Messages wire API (`/v1/messages`, `x-api-key`). */
	const val ANTHROPIC_MESSAGES = "anthropic-messages"
	/** Google Generative AI wire API (`generateContent` / `streamGenerateContent`). */
	const val GOOGLE_GENERATIVE_AI = "google-generative-ai"
}

object ProviderReasoning {
	const val NONE = "none"
	const val EFFORT = "effort"
	const val DEEPSEEK = "deepseek"
}

/**
 * A chat backend the user configured.
 *
 * Profiles are not secrets: the key is in `SecureStore` under a slot derived
 * from [id]. The built-in DeepSeek profile uses the legacy
 * `deepseek.api_key` slot so an existing install keeps working; every custom
 * profile gets its own slot.
 *
 * `kind` selects the seeded URL and reasoning defaults; the request is the same
 * OpenAI-compatible shape either way. `reasoningMode` exists because
 * `thinking` / `reasoning_effort` / `reasoning_content` are not universal:
 * sending a DeepSeek-specific field to a server that rejects unknown fields
 * makes the first message fail, so a custom profile starts with `none` and the
 * user can opt into the field its provider documents.
 */
@Entity(tableName = "provider_profiles")
data class ProviderProfileEntity(
	@PrimaryKey val id: String,
	val name: String,
	val kind: String = ProviderKinds.OPENAI_COMPATIBLE,
	val baseUrl: String,
	@ColumnInfo(defaultValue = ProviderReasoning.NONE)
	val reasoningMode: String = ProviderReasoning.NONE,
	@ColumnInfo(defaultValue = "1")
	val includeStreamUsage: Boolean = true,
	/** One model id per line, for endpoints without a usable `/models`. */
	@ColumnInfo(defaultValue = "")
	val manualModels: String = "",

	/**
	 * Per-model tags as JSON: `{"wan2.7-image":["image"]}`.
	 *
	 * A provider's model list is everything the account can call, not a list of
	 * chat models, and no field in `/models` says which is which. The app infers
	 * a guess from the id; this column is where the user corrects it.
	 * `ModelTagCodec` is the only reader and writer of the format.
	 */
	@ColumnInfo(defaultValue = "")
	val modelTags: String = "",
	/** The seeded DeepSeek profile cannot be deleted. */
	val isBuiltIn: Boolean = false,
	val createdAt: Long,
	val updatedAt: Long,
)

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
 * The conversation stores a copy of the provider, model and effort rather than
 * reading them through the agent on every turn. That copy is what makes editing
 * an agent safe: changing an agent's model must not silently rewrite the
 * settings of conversations already had with it, because a transcript is a
 * record of what was actually said.
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
	/** Which provider profile answers this assistant. */
	@ColumnInfo(defaultValue = ProviderIds.DEEPSEEK)
	val providerId: String = ProviderIds.DEEPSEEK,
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

	/**
	 * Which tools this assistant may use, as a JSON array of names.
	 *
	 * **Empty string means every tool; `"[]"` means none.** That is the same
	 * distinction a nullable list would carry, kept as a string because Room's
	 * converter lookup did not resolve for `List<String>` on this KSP.
	 * [ToolsColumn] owns the encoding so nothing else has to know it.
	 *
	 * ## Why an allow-list rather than a deny-list
	 *
	 * A deny-list would mean a tool added in a future release becomes available
	 * to every existing assistant the moment it ships. An allow-list means it
	 * becomes available only when someone chooses it -- so the default for a new
	 * capability is "not yet", which is the direction a security-relevant
	 * setting should fail in.
	 *
	 * ## Why names rather than ids
	 *
	 * The name is what the model calls and what the tool registry is keyed by. A
	 * name that no longer exists is ignored at load rather than treated as an
	 * error: a release that renames a tool should leave an assistant slightly
	 * less capable, not broken.
	 */
	@ColumnInfo(defaultValue = "")
	val toolsJson: String = "",

	val createdAt: Long,
	val updatedAt: Long,
	/** The one seeded on first launch. Cannot be deleted from the UI. */
	val isDefault: Boolean = false,
) {
	/** The selection, or null for every tool. */
	val tools: List<String>? get() = ToolsColumn.decode(toolsJson)
}

/**
 * One derived summary of the earlier part of a conversation.
 *
 * This is **not** a replacement for the transcript and not a message: every
 * original row stays in `messages`. The summary is only a request projection
 * marker, so a branch switch, a rewind or a bad summary can be undone without
 * losing what was said. [throughMessageId] must be on the active path; a
 * summary whose boundary is not found is ignored rather than applied to the
 * wrong prefix.
 */
@Entity(
	tableName = "context_summaries",
	foreignKeys = [
		ForeignKey(
			entity = ConversationEntity::class,
			parentColumns = ["id"],
			childColumns = ["conversationId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("conversationId")],
)
data class ContextSummaryEntity(
	@PrimaryKey val conversationId: String,
	/** Last active message covered by [summary]. */
	val throughMessageId: String,
	val summary: String,
	val createdAt: Long,
	val updatedAt: Long,
)

/**
 * A workspace, independent of any one conversation.
 *
 * A temporary workspace is created with its first conversation and is deleted
 * with it. A permanent workspace outlives every conversation that uses it and
 * may be empty. The directory name is [id]; [name] is only what the user sees,
 * so renaming never moves files.
 *
 * This row is the source of truth for lifetime. The files themselves are still
 * plain directories under [Database.workspacesRoot]; a directory is an orphan
 * only when no workspace row names it, never because no conversation does.
 */
@Entity(
	tableName = "workspaces",
	indices = [Index("isPermanent")],
)
data class WorkspaceEntity(
	@PrimaryKey val id: String,
	val name: String,
	/** True for a workspace created on its own or promoted from a temporary one. */
	@ColumnInfo(defaultValue = "0")
	val isPermanent: Boolean = false,
	/**
	 * The one conversation that owns a temporary workspace.
	 *
	 * Null for a permanent workspace. A temporary workspace deliberately holds
	 * exactly one conversation: otherwise deleting its owner would delete files
	 * another conversation is still using.
	 */
	val ownerConversationId: String? = null,
	val createdAt: Long,
	val updatedAt: Long,
)

/**
 * A conversation.
 *
 * [workspaceId] is the directory name on disk and points at a [WorkspaceEntity].
 * It is deliberately **not** the primary key: the id is the database's
 * business, the workspace directory is the filesystem's, and tying them
 * together means a future change to either forces a migration of the other.
 *
 * It is stable and opaque rather than derived from the title. A directory
 * named after a title would have to be renamed whenever the user renamed the
 * conversation, and every rename is a chance to orphan the files inside it.
 *
 * More than one conversation may name the same workspace: that is what a
 * permanent workspace is for. The index is therefore **not unique**; the old
 * unique index encoded the one-conversation-per-workspace model and had to be
 * replaced in the v11 migration.
 */
@Entity(
	tableName = "conversations",
	indices = [
		Index(value = ["workspaceId"]),
		Index("agentId"),
	],
)
data class ConversationEntity(
	@PrimaryKey val id: String,
	val title: String,
	/** The [WorkspaceEntity] this conversation uses. */
	val workspaceId: String,
	/** The assistant this conversation was started with. */
	val agentId: String? = null,
	/** The provider profile this conversation uses; copied at creation. */
	@ColumnInfo(defaultValue = ProviderIds.DEEPSEEK)
	val providerId: String = ProviderIds.DEEPSEEK,
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

	/**
	 * Tools available in this conversation, copied from the assistant at
	 * creation. Empty string means every tool.
	 *
	 * Copied for the same reason as the model, and more besides. Editing an
	 * assistant's tools must not silently change what a conversation already
	 * held can do -- and here that is not only record-keeping: a transcript
	 * contains tool calls that were permitted when they ran, and a later edit
	 * that removed the tool would leave the record describing something the app
	 * no longer allows.
	 */
	@ColumnInfo(defaultValue = "")
	val toolsJson: String = "",

	val createdAt: Long,
	val updatedAt: Long,
	val pinned: Boolean = false,
	/**
	 * Whether this conversation's first SSH call has been acknowledged.
	 *
	 * Per conversation, not per assistant: consenting in one thread does not
	 * consent to the same assistant in another. The app does not configure
	 * remote hosts, so this is the only persistent state SSH adds.
	 */
	@ColumnInfo(defaultValue = "0")
	val sshAllowed: Boolean = false,
) {
	/** The selection, or null for every tool. */
	val tools: List<String>? get() = ToolsColumn.decode(toolsJson)
}

/**
 * The encoding for a tool selection, in one place.
 *
 * The distinction it preserves is the point: **empty is "no selection" and
 * means every tool**, while **`[]` is a selection of nothing**. Flattening the
 * two would turn a deliberately text-only assistant into an all-tools one, or
 * strip an assistant of everything, depending on which way it was flattened.
 *
 * A malformed value decodes to null -- absent -- rather than to an empty list,
 * because absent is the reading that fails toward keeping the assistant usable.
 */
object ToolsColumn {

	/** Every tool. What an assistant created before the column existed gets. */
	const val ALL = ""

	/** No tools at all. */
	const val NONE = "[]"

	fun encode(tools: List<String>?): String =
		if (tools == null) ALL else JsonArray(tools.map { JsonPrimitive(it) }).toString()

	fun decode(raw: String): List<String>? {
		if (raw.isBlank()) return null
		return runCatching {
			(Json.parseToJsonElement(raw) as? JsonArray)
				.orEmpty()
				.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
		}.getOrNull()
	}
}

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
	/**
	 * Anthropic's opaque signature for [reasoning].
	 *
	 * Replaying a thinking block without its signature is rejected; the column
	 * is nullable because every other provider, and every row written before
	 * this field existed, has no signature.
	 */
	val reasoningSignature: String? = null,
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

	/**
	 * Paths this turn changed, as a JSON array of `{"path": ..., "kind": ...}`.
	 *
	 * Written on the final assistant row after the turn ends. Nullable because
	 * old rows predate the column and a read-only turn has nothing to report.
	 */
	val changedFilesJson: String? = null,

	/**
	 * Images attached to this message, as a JSON array of
	 * `{"path": ..., "mime": ...}`.
	 *
	 * Paths into the conversation's workspace rather than copies of the bytes: a
	 * screenshot inside a row would be duplicated state that can disagree with
	 * the file the user can see and delete, and a transcript is read on every
	 * screen open. The bytes are loaded when a request is built, which is the
	 * only time they are wanted.
	 */
	val attachmentsJson: String? = null,

	/**
	 * Whether this turn ran a tool that changed the workspace.
	 *
	 * ## Why this is stored rather than derived
	 *
	 * It could be recomputed by looking up each tool name from
	 * [toolCallsJson] and asking that tool whether it mutates -- but only while
	 * every tool still exists under the same name. A release that renames
	 * `write_file`, or drops the tool a stored call refers to, would silently
	 * reclassify history: an old reply that really did write a file would start
	 * being offered for regeneration, and the write would happen again.
	 *
	 * Recording the fact at the moment it happened cannot be invalidated by a
	 * later change to the tool list.
	 *
	 * ## What it guards
	 *
	 * Regenerating re-runs the whole turn, tools included. For a text answer
	 * that is free; for one that wrote or deleted files it means doing it twice.
	 */
	val mutated: Boolean = false,
)
