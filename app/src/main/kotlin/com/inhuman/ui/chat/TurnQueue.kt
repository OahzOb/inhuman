package com.inhuman.ui.chat

/**
 * A message the user wrote while the agent was working, waiting to be delivered.
 *
 * The queue lives in memory, exactly like the composer draft: neither has ever
 * been persisted, so a process death loses an unsent message either way. Making
 * the queue durable would need its own table and a "was it already sent?"
 * reconciliation with the turn it belonged to -- complexity the feature does not
 * buy anything for.
 *
 * @property insert `true` when the user asked for it to be shown to the model
 *   before this turn's next round, `false` for "send it after this turn".
 *   An insertion that never found a later round is demoted to `false` at the
 *   end of the turn ([afterTurn]); the UI says "insert" only while it can still
 *   happen.
 */
data class QueuedMessage(
	val id: String,
	val text: String,
	val attachments: List<PendingAttachment> = emptyList(),
	val insert: Boolean = false,
) {
	/** Insertion is text-only: an image cannot be spliced into a tool-round. */
	val insertable: Boolean get() = text.isNotBlank() && attachments.isEmpty()

	/** A one-line preview for the queue strip; the full text stays in the row. */
	val preview: String
		get() = text.lineSequence()
			.map { it.trim() }
			.firstOrNull { it.isNotEmpty() }
			?.take(80)
			.orEmpty()
}

/** The most messages one turn may hold in the queue. */
internal const val MAX_QUEUED = 5

/**
 * The queue after a turn ended.
 *
 * A queued insertion that was never drained had no "next model call" to go into
 * -- the round did not ask for tools, or the answer was already streaming. It
 * becomes an ordinary queued message, so it is sent after the turn instead of
 * disappearing or being sent twice. A false-to-false entry is unchanged.
 */
internal fun afterTurn(queued: List<QueuedMessage>): List<QueuedMessage> =
	queued.map { if (it.insert) it.copy(insert = false) else it }

/**
 * Whether the composer may offer "insert now" for the current draft.
 *
 * Text only: the wire can carry a user message after a tool result, but an image
 * attached mid-round would need an image part inserted into a request the engine
 * has not built yet, and the round's cache prefix is already fixed. An image is
 * a reason to queue, not to splice.
 */
internal fun canInsertNow(draft: String, attachments: List<PendingAttachment>): Boolean =
	draft.isNotBlank() && attachments.isEmpty()
