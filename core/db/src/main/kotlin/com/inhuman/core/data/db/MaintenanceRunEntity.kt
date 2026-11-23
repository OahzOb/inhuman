package com.inhuman.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One request the app spent maintaining a continuous session's projection.
 *
 * ## Why this is a table and not a message row
 *
 * Maintaining a session costs a provider request that is not the user's turn:
 * it produces a brief, not an answer. The transcript is what the model is sent
 * and what the user reads; folding a maintenance run into it would make the
 * cost invisible in the only place it is supposed to be visible. A side table
 * keeps the record auditable without putting it in the conversation, exactly
 * the shape `approval_audit` uses.
 *
 * ## Retention follows the conversation, not the turn
 *
 * The money was already spent, so rewinding a turn must not erase the fact that
 * a run happened. The foreign key is therefore the conversation, with cascade,
 * and only deleting the conversation removes its runs. This also matches the
 * brief: `context_summaries` is a derived projection that rewinding may
 * invalidate, while the cost record is a fact.
 *
 * [reason] is a short category ("timeout", "empty-output", ...) and never
 * conversation content: diagnostics and cost rows must not become a second copy
 * of the transcript.
 */
@Entity(
	tableName = "maintenance_runs",
	foreignKeys = [
		ForeignKey(
			entity = ConversationEntity::class,
			parentColumns = ["id"],
			childColumns = ["conversationId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index(value = ["conversationId", "at"])],
)
data class MaintenanceRunEntity(
	@PrimaryKey val id: String,
	val conversationId: String,
	/** When the run started, milliseconds. */
	val at: Long,
	/** A [MaintenanceRunKind] value. */
	val kind: String,
	/** The model that was asked, when one was. Null when the run never left the device. */
	val model: String? = null,
	/** Provider-reported tokens; null means "not reported", never 0. */
	val inputTokens: Int? = null,
	val outputTokens: Int? = null,
	val omittedMessages: Int = 0,
	val omittedTurns: Int = 0,
	/** A [MaintenanceRunOutcome] value. */
	val outcome: String,
	/** A short failure category; never conversation content. */
	val reason: String? = null,
)

/** What asked for a maintenance run. */
object MaintenanceRunKind {
	/** The app decided at a turn boundary that the projection was too large. */
	const val AUTO = "auto"

	/** The user pressed the manual control. */
	const val MANUAL = "manual"

	/** The user asked for the brief to be rebuilt from the record. */
	const val REBUILD = "rebuild"
}

/** How a maintenance run ended. */
object MaintenanceRunOutcome {
	const val OK = "ok"
	const val FAILED = "failed"
	/** A brief was written, but had to be cut at a section boundary. */
	const val TRUNCATED = "truncated"
}
