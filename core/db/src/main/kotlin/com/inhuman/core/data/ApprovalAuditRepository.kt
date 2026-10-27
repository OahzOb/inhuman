package com.inhuman.core.data

import com.inhuman.core.ai.policy.PolicySource
import com.inhuman.core.data.db.ApprovalAuditEntity
import com.inhuman.core.data.db.ApprovalDecision
import com.inhuman.core.data.db.InhumanDatabase
import java.util.UUID

/**
 * One recorded approval question, as the history screen reads it.
 *
 * [rule] is the stored comma-joined source names; the UI localises them. It is
 * left raw here so this module stays free of Android string resources, and so a
 * source name written by a newer build is shown rather than dropped.
 */
data class ApprovalRecord(
	val id: String,
	val messageId: String,
	val callId: String,
	val toolName: String,
	val path: String?,
	val rule: String,
	val decision: ApprovalDecision,
	val askedAt: Long,
	val decidedAt: Long?,
)

/**
 * Writes and reads the approval audit.
 *
 * ## Why the write is not allowed to decide anything
 *
 * This repository is called from the gate, between "the policy says ask" and
 * "the user answered". A database that cannot write is not a reason to run the
 * call, nor a reason to refuse it: the caller wraps both writes in `runCatching`
 * and logs the failure, because the alternative -- an audit failure turning a
 * question into a different question -- is worse than a gap in the audit.
 *
 * ## One row per question
 *
 * [asked] inserts `asked`; [decided] moves the same row to its terminal answer.
 * The update is guarded by the `asked` state in SQL rather than read-modify-
 * write, so a timeout and a late tap cannot both claim the row.
 */
class ApprovalAuditRepository(private val db: InhumanDatabase) {

	/**
	 * Record that the user is being asked. Returns the row id to pass to
	 * [decided].
	 *
	 * [sources] are the policy rules that produced the question; [toolAsked] is
	 * the tool's own `needsApproval`, which is not a policy source and is stored
	 * as `tool`.
	 */
	suspend fun asked(
		conversationId: String,
		messageId: String,
		callId: String,
		toolName: String,
		path: String?,
		sources: Collection<PolicySource>,
		toolAsked: Boolean,
		at: Long = System.currentTimeMillis(),
	): String {
		val id = UUID.randomUUID().toString()
		val rule = buildList {
			addAll(sources.map { it.stored })
			if (toolAsked) add("tool")
		}.distinct().sorted().joinToString(",")
		db.approvals().insert(
			ApprovalAuditEntity(
				id = id,
				conversationId = conversationId,
				messageId = messageId,
				callId = callId,
				toolName = toolName,
				path = path,
				rule = rule,
				decision = ApprovalDecision.Asked.stored,
				askedAt = at,
			),
		)
		return id
	}

	/**
	 * Record how the question ended.
	 *
	 * A second call with a terminal decision is a no-op: the SQL guard is what
	 * makes "the first answer wins" true even if two paths race.
	 */
	suspend fun decided(
		id: String,
		decision: ApprovalDecision,
		at: Long = System.currentTimeMillis(),
	) {
		if (decision == ApprovalDecision.Asked) return
		db.approvals().decide(id, decision.stored, at)
	}

	/** Newest first. */
	suspend fun forConversation(conversationId: String): List<ApprovalRecord> =
		db.approvals().forConversation(conversationId).map { row ->
			ApprovalRecord(
				id = row.id,
				messageId = row.messageId,
				callId = row.callId,
				toolName = row.toolName,
				path = row.path,
				rule = row.rule,
				decision = ApprovalDecision.parse(row.decision),
				askedAt = row.askedAt,
				decidedAt = row.decidedAt,
			)
		}

	suspend fun count(conversationId: String): Int = db.approvals().count(conversationId)
}