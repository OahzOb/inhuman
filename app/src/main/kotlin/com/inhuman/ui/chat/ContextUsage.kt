package com.inhuman.ui.chat

/**
 * How full the model's context window is, and how to write it down.
 *
 * ## Why this is its own type rather than two fields on the screen state
 *
 * The arithmetic has three cases that all look alike and are not: no window
 * reported, a window but nothing measured yet, and a real number. A ring drawn
 * from `used / window` gets the first two wrong in a way nobody notices -- an
 * absent window divides by zero, and a fabricated window turns "unknown" into
 * "0% used", which reads as reassurance rather than as missing information.
 * Keeping the cases in one type makes the screen render a decision instead of
 * performing arithmetic.
 *
 * ## What the number means, exactly
 *
 * [usedTokens] is the `promptTokens` the provider reported for the most recent
 * request, not a count of the visible transcript. That is the honest figure and
 * also the useful one: the prompt is the system prompt, the tool schemas, the
 * conversation, and every tool result -- and the tool schemas alone are a few
 * thousand tokens that no amount of scrolling would reveal.
 *
 * It is a measurement of the **last** request, so it is one turn behind. What
 * the next one will cost is unknowable until its reply arrives, because
 * tokenising is the provider's job.
 *
 * @param usedTokens what the last request's prompt came to, or null when the
 *   provider has not reported usage yet -- the first turn of a conversation, or
 *   a provider that does not report it at all.
 * @param contextWindow the model's window, or null when it does not say. The
 *   demo provider reports one; a fetch that failed reports nothing.
 */
data class ContextUsage(
	val usedTokens: Long?,
	val contextWindow: Int?,
) {
	/** The window as a number, or null when there is nothing to divide by. */
	private val window: Long? get() = contextWindow?.takeIf { it > 0 }?.toLong()

	/**
	 * Whether there is anything to draw at all.
	 *
	 * False only when both halves are missing. A known window with no
	 * measurement is still worth showing: the ring is empty, the panel says
	 * "not measured yet", and the size of the window is itself the answer to
	 * "how much room do I have".
	 */
	val isKnown: Boolean get() = window != null || usedTokens != null

	/** Fraction of the window used, clamped to 0..1. Null when not computable. */
	val fraction: Float?
		get() {
			val w = window ?: return null
			val used = usedTokens ?: return null
			return (used.toDouble() / w.toDouble()).coerceIn(0.0, 1.0).toFloat()
		}

	/**
	 * Percent for display, or null when not computable.
	 *
	 * Rounded **down**, with two exceptions, and both are deliberate:
	 *
	 *  - A non-zero amount never shows as 0%. A conversation that has used a
	 *    fifth of a percent reading "0% used" looks like a broken gauge.
	 *  - Anything at or over the window shows 100% rather than 101%. Going over
	 *    is a state worth flagging, but "101% of the window" is a sentence that
	 *    invites an argument about rounding instead of the obvious conclusion.
	 */
	val percent: Int?
		get() {
			val w = window ?: return null
			val used = usedTokens ?: return null
			if (used <= 0) return 0
			if (used >= w) return 100
			return (used * 100.0 / w).toInt().coerceAtLeast(1)
		}

	/** Tokens still free, floored at zero. Null when not computable. */
	val remainingTokens: Long?
		get() {
			val w = window ?: return null
			val used = usedTokens ?: return null
			return (w - used).coerceAtLeast(0L)
		}

	/** "412K / 1M", for the one-line form beside the ring. */
	val summary: String?
		get() {
			val used = usedTokens ?: return null
			val w = window ?: return TokenFormat.compact(used)
			return "${TokenFormat.compact(used)} / ${TokenFormat.compact(w)}"
		}
}

/**
 * Token counts as a person reads them.
 *
 * Exact to the last digit in the detail panel, abbreviated on the single line
 * beside the ring: "412K / 1M" is scannable and "421,338 / 1,048,576" is not,
 * but the exact figure is what someone checking a bill wants, so both exist.
 */
object TokenFormat {

	/** "421,338". Grouped by hand rather than through a locale. */
	fun exact(tokens: Long): String {
		val digits = tokens.toString()
		if (digits.length <= 3) return digits
		return digits.reversed()
			.chunked(3)
			.joinToString(",")
			.reversed()
	}

	/**
	 * "998", "1.2K", "412K", "1.1M".
	 *
	 * One decimal below ten of a unit and none above it, because the difference
	 * between 1.2M and 1.3M is worth a character and the difference between 412K
	 * and 413K is not.
	 *
	 * Built by integer arithmetic rather than `String.format`: on a Turkish
	 * locale that would render 1.2 as "1,2", and a token count with a comma in
	 * it is a different number to anyone reading a list of them.
	 */
	fun compact(tokens: Long): String {
		val n = tokens.coerceAtLeast(0L)
		return when {
			n < 1_000 -> n.toString()
			n < 1_000_000 -> thousands(n, "K")
			else -> thousands(n, "M")
		}
	}

	private fun thousands(n: Long, suffix: String): String {
		val divisor = if (suffix == "K") 1_000L else 1_000_000L
		// Rounded to one decimal first, then read. The integer part comes from
		// the same rounded figure rather than from `n / divisor`, because those
		// two disagree: 9,960 is nine thousand by truncation and ten by
		// rounding, and printing "9K" beside a count that has already reached
		// ten is the kind of wrong that survives review.
		val tenths = (n * 10 + divisor / 2) / divisor
		return if (tenths < 100) {
			val fraction = tenths % 10
			if (fraction == 0L) "${tenths / 10}$suffix" else "${tenths / 10}.$fraction$suffix"
		} else {
			// Past ten of the unit the decimal is noise, and the integer has
			// already been rounded by the same step.
			"${tenths / 10}$suffix"
		}
	}
}
