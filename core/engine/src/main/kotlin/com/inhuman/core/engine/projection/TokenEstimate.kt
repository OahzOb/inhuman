package com.inhuman.core.engine.projection

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A cheap, deterministic token estimate for one projection.
 *
 * ## Why a heuristic exists at all
 *
 * The app has no tokenizer: tokenising is the provider's job, and calling one
 * to answer "will this fit" would be a network request in the request builder.
 * The estimate therefore only has to be good enough to fold long before the
 * provider rejects the request — the measured [calibrate] ratio removes the
 * systematic error once one usage report exists.
 *
 * ## The arithmetic
 *
 * One weighted character is worth one token for CJK and a quarter of a token
 * for ASCII. Weighted characters become tokens at `1.05` (a small safety
 * margin), the result is multiplied by the calibrated ratio, images are added
 * at their fixed estimate, and [overhead][estimateTokens] is added last because
 * it is already a token count (system prompt, tool schemas) and must not be
 * scaled by the text ratio.
 *
 * [calibrate] clamps the ratio to `0.5..2.0`: one unusual request must not be
 * able to shrink every later estimate.
 */

/** How many characters of ASCII cost one token. */
private const val ASCII_WEIGHT = 0.25

/** How many characters of non-ASCII text cost one token. */
private const val CJK_WEIGHT = 1.0

/** Safety margin between the weighted character count and tokens. */
private const val CHAR_TO_TOKEN = 1.05

private const val ASCII_MAX_CODE = 0x7F
private const val MIN_RATIO = 0.5
private const val MAX_RATIO = 2.0
private const val NEUTRAL_RATIO = 1.0

/**
 * Weighted characters for a piece of text, ready for [ProjectionMessage.chars].
 *
 * ASCII (code points below 0x80) counts a quarter; everything else counts one.
 * Treating every non-ASCII code point as CJK-width is deliberately simple:
 * the alternative is a script table whose errors are invisible, and the
 * conservative direction (over-counting) is the one that fails safely.
 *
 * Rounded once, like the per-message estimate, so two callers with the same
 * text cannot disagree because they rounded at different points.
 */
fun weightedChars(text: String): Int {
	if (text.isEmpty()) return 0
	var weighted = 0.0
	for (character in text) {
		weighted += if (character.code <= ASCII_MAX_CODE) ASCII_WEIGHT else CJK_WEIGHT
	}
	return weighted.roundToInt().coerceAtLeast(0)
}

/**
 * Estimated prompt tokens for [messages] plus [overheadTokens].
 *
 * Safe on an empty list and on a null overhead: both simply contribute nothing.
 * A negative [chars] is clamped to zero rather than shrinking the estimate.
 */
fun estimateTokens(
	messages: List<ProjectionMessage>,
	overheadTokens: Int?,
	ratio: Double,
	policy: ProjectionPolicy = ProjectionPolicy(),
): Int {
	val payload = payloadTokens(messages, 0, messages.size, ratio, policy)
	val overhead = (overheadTokens ?: 0).coerceAtLeast(0)
	return (payload.toLong() + overhead).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/**
 * The measured payload divided by the estimated payload, clamped.
 *
 * A caller that has just seen a real request reports its `promptTokens` and the
 * payload estimate it planned with; the returned ratio is what the next plan
 * multiplies by. When either side is missing or non-positive this returns the
 * previous ratio (or 1.0, neutral) instead of inventing a correction from
 * nothing.
 */
fun calibrate(
	actualPromptTokens: Int?,
	estimatedPayloadTokens: Int?,
	previousRatio: Double? = null,
): Double {
	val previous = previousRatio?.takeIf { it.isFinite() }?.coerceIn(MIN_RATIO, MAX_RATIO)
	val actual = actualPromptTokens?.takeIf { it > 0 } ?: return previous ?: NEUTRAL_RATIO
	val estimated = estimatedPayloadTokens?.takeIf { it > 0 } ?: return previous ?: NEUTRAL_RATIO
	return (actual.toDouble() / estimated.toDouble()).coerceIn(MIN_RATIO, MAX_RATIO)
}

/**
 * Sum of the per-message estimates in `[fromIndex, toIndex)`.
 *
 * Per-message rounding is what makes a range sum equal to the sum of its parts;
 * a total-level rounding would make the folded and kept halves add up to one
 * token more than the original.
 */
internal fun payloadTokens(
	messages: List<ProjectionMessage>,
	fromIndex: Int,
	toIndex: Int,
	ratio: Double,
	policy: ProjectionPolicy,
): Int {
	val effectiveRatio = sanitizedRatio(ratio, policy)
	val imageTokens = policy.imageTokens.coerceAtLeast(0).toLong()
	var total = 0L
	for (index in fromIndex until toIndex) {
		val message = messages[index]
		val weighted = message.chars.coerceAtLeast(0).toDouble()
		val raw = (weighted * CHAR_TO_TOKEN).roundToLong()
		val calibrated = (raw * effectiveRatio).roundToLong()
		val image = if (message.hasImages) imageTokens else 0L
		total += calibrated + image
	}
	return total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** The ratio actually used, clamped and with a usable fallback. */
internal fun sanitizedRatio(ratio: Double, policy: ProjectionPolicy): Double {
	val fallback = policy.defaultRatio.takeIf { it.isFinite() } ?: NEUTRAL_RATIO
	val chosen = ratio.takeIf { it.isFinite() } ?: fallback
	return chosen.coerceIn(MIN_RATIO, MAX_RATIO)
}
