package com.inhuman.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One question the app asked before running a tool, and the answer it got.
 *
 * ## Why this is a table and not a message row
 *
 * The transcript is what the model is sent and what the user reads as the
 * conversation. An approval is neither: putting it in `messages` would send it
 * to the provider on every later turn, make it searchable as conversation
 * content, and give it a branch position it does not have. A side table keeps
 * the audit out of the request while still making it survive a reload -- which
 * is the whole point of recording it.
 *
 * ## One row per question, updated once
 *
 * `asked` is written before the dialogue appears; the same row is updated to
 * `allowed`, `declined` or `timed_out`. A row left at `asked` is therefore
 * evidence of a question that was never answered -- a process death, or a stop
 * while the dialogue was open -- not a failed write.
 *
 * ## Retention
 *
 * The foreign key is the conversation, not the message. Rewinding a turn
 * deletes its messages, but "this call was asked about and the answer was no"
 * is exactly the kind of fact a rollback should not quietly erase. Deleting
 * the conversation still removes every row with it.
 */
@Entity(
	tableName = "approval_audit",
	foreignKeys = [
		ForeignKey(
			entity = ConversationEntity::class,
			parentColumns = ["id"],
			childColumns = ["conversationId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index(value = ["conversationId", "askedAt"])],
)
data class ApprovalAuditEntity(
	@PrimaryKey val id: String,
	val conversationId: String,
	/** The assistant row whose turn asked; a plain id, see the class comment. */
	val messageId: String,
	/** The provider's tool-call id, so an audit row can be matched to its result. */
	val callId: String,
	val toolName: String,
	/** Workspace-relative and normalised, or null for a tool that names no path. */
	val path: String?,
	/**
	 * Which rules produced the question, comma-joined: `global`, `assistant`,
	 * `path`, `tool`. Names rather than ordinals, so reordering an enum cannot
	 * silently rewrite history.
	 */
	val rule: String,
	/** An [ApprovalDecision] name. */
	val decision: String,
	val askedAt: Long,
	val decidedAt: Long? = null,
)

/**
 * How an approval question ended.
 *
 * [Asked] is the only value a row can have while the dialogue is open; the
 * others are terminal. Parsing is tolerant for the usual reason: a value
 * written by a newer build must not make the history screen fail to open.
 */
enum class ApprovalDecision(val stored: String) {
	Asked("asked"),
	Allowed("allowed"),
	Declined("declined"),
	TimedOut("timed_out"),
	;

	companion object {
		fun parse(stored: String?): ApprovalDecision =
			entries.firstOrNull { it.stored.equals(stored?.trim(), ignoreCase = true) } ?: Asked
	}
}