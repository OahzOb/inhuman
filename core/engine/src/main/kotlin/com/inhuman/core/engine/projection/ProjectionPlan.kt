package com.inhuman.core.engine.projection

/**
 * What one request projection should look like, as a value.
 *
 * The plan is derived data: it says which messages the next request would keep
 * verbatim and which would be folded into the brief. It does not change the
 * transcript, and it is not persisted — the caller re-plans on every request
 * from the same inputs, which is what keeps the projection deterministic.
 *
 * ## Index arithmetic
 *
 * [foldFromIndex] is inclusive and [foldToIndex] is exclusive, matching
 * `List.subList` and Kotlin ranges. When nothing is folded the two are equal and
 * point at [firstSentIndex] (or at the current brief boundary's next message),
 * so a caller can always use them without a special case.
 *
 * ## [tooLarge] versus [exceedsHardLimit]
 *
 * [exceedsHardLimit] describes the **resulting** projection: after the planned
 * fold (if any), would it still exceed the hard limit? A fold that fixes an
 * over-limit transcript therefore reports `false` even though the transcript
 * *was* over the line — the useful question is whether the next request can be
 * sent.
 *
 * [tooLarge] means no legal fold can fix it: keeping the minimum number of
 * complete turns is still over the hard limit, or the transcript is over the
 * limit with no complete turn to fold. In that case the caller must explain the
 * limit to the user rather than send a request that cannot fit.
 */
data class ProjectionPlan(
	/** Index of the first message the next request would send verbatim. */
	val firstSentIndex: Int,
	/** The message the brief covers after this plan; null when there is none. */
	val throughMessageId: String?,
	/** How many messages this plan would fold into the brief. */
	val omittedMessages: Int,
	/** How many complete user turns this plan would fold into the brief. */
	val omittedTurns: Int,
	/** Estimated prompt size after the plan, including brief and overhead. */
	val estimatedTokens: Int,
	/** Estimated size after the plan, excluding the request overhead. */
	val estimatedPayloadTokens: Int,
	/** True when this plan folds at least one complete turn. */
	val foldNeeded: Boolean,
	/** True when the resulting projection is still over the hard limit. */
	val exceedsHardLimit: Boolean,
	/** True when no legal fold can bring the projection under the hard limit. */
	val tooLarge: Boolean,
	/** Estimated tokens of the messages kept verbatim (brief and overhead excluded). */
	val keepVerbatimTokens: Int,
	/** Inclusive start of the range folded into the brief. */
	val foldFromIndex: Int,
	/** Exclusive end of the range folded into the brief. */
	val foldToIndex: Int,
)
