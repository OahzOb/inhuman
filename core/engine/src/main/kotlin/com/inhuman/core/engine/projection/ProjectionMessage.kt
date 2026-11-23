package com.inhuman.core.engine.projection

/**
 * One message as the projection planner sees it.
 *
 * The planner never reads the prose itself: it needs the size, the role, and
 * whether the message carries an image. Everything here is copied from the
 * transcript at the moment a plan is built; the planner does not touch storage.
 *
 * ## [chars] is weighted, not a raw length
 *
 * A raw character count cannot be estimated honestly across languages: one
 * Chinese character is roughly one token while four ASCII characters are
 * roughly one. Carrying only one `Int` therefore means the two rates must be
 * collapsed *before* the plan: [weightedChars] implements the rule
 * (CJK 1.0 per character, ASCII 0.25) and its result is what belongs here.
 *
 * Passing `String.length` directly is the conservative shortcut for mixed
 * text: it is exact for pure CJK and over-counts ASCII by up to four times.
 * Under-counting is the failure that matters (a request that does not fit),
 * so the conservative direction is the safe default when a caller has not
 * measured the text yet.
 *
 * ## [hasImages] is one image, not a count
 *
 * The field is a boolean by design: the first version budgets one image per
 * message at [ProjectionPolicy.imageTokens]. A message with several
 * attachments needs a count field before images can be estimated exactly —
 * recorded here as a known gap rather than silently under-budgeted.
 */
data class ProjectionMessage(
	val id: String,
	val fromUser: Boolean,
	val isToolResult: Boolean,
	/** Size in weighted characters; see the class comment. */
	val chars: Int,
	/** True when this message carries at least one image. */
	val hasImages: Boolean = false,
)
