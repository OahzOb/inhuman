package com.inhuman.core.ai.tools

/**
 * The request budget the app's own web tools share for one turn.
 *
 * `web_search_local` and `web_fetch_local` ask the search engine's and the
 * target site's public pages from the device's own address. They are free, but
 * a burst from one address is exactly what those sites' bot checks react to:
 * after a block, every call fails, and the failure reaches the model as "no
 * results" or "the page was empty" -- which is the wrong lesson to learn about
 * the tool.
 *
 * Therefore both tools share one budget per turn: ten requests, at least one
 * second apart, of which at most three may be rendered. The cap is per turn
 * and a turn is one user message, so the user asking again resets it. A
 * process-wide cooldown would fail an ordinary question for a reason the user
 * cannot see, which is worse than the burst it prevents.
 *
 * This guard only subtracts. It refuses a call, never grants one; it does not
 * touch the SSRF rules in [GuardedHttp]; and spacing is a wait, not a refusal,
 * because a model cannot see a clock. A request that arrives early is granted
 * with [Permit.Granted.waitMillis] set, and the tool waits that long before
 * making the request.
 */
class LocalWebBudget(
	val maxRequests: Int = MAX_PER_TURN,
	val maxRendered: Int = MAX_RENDERED_PER_TURN,
	private val spacingMillis: Long = MIN_SPACING_MILLIS,
	private val now: () -> Long = System::currentTimeMillis,
) {

	/** Requests already granted this turn, search and fetch together. */
	var used: Int = 0
		private set

	/** Rendered fetches already granted this turn. */
	var rendered: Int = 0
		private set

	val remaining: Int get() = (maxRequests - used).coerceAtLeast(0)

	private var lastGrantedAt: Long? = null

	/**
	 * Ask for one request and count it if granted.
	 *
	 * [rendered] must be true only for a fetch that loads the page in the host's
	 * WebView: that path is an order of magnitude slower and heavier than a
	 * static GET, so it has its own smaller cap.
	 */
	fun tryAcquire(rendered: Boolean = false): Permit {
		if (used >= maxRequests) {
			return Permit.Refused(
				"The local web request budget for this turn is used up " +
					"($maxRequests of $maxRequests, search and fetch together). " +
					"Do not retry: answer from the results you already have, or say " +
					"what is still missing so the user can ask again in a new turn.",
			)
		}
		if (rendered && this.rendered >= maxRendered) {
			return Permit.Refused(
				"The rendered-fetch limit for this turn is used up " +
					"($maxRendered of $maxRendered). Retry without render=true, or " +
					"answer from what you already have.",
			)
		}

		val wait = lastGrantedAt
			?.let { (it + spacingMillis - now()).coerceAtLeast(0) }
			?: 0
		used++
		if (rendered) this.rendered++
		// Count the spacing from when the request will actually run, not from
		// when it was asked for, or a string of early calls would each wait for
		// the previous one's delay and stagger further and further apart.
		lastGrantedAt = now() + wait
		return Permit.Granted(wait)
	}

	sealed interface Permit {

		/** The request may run after [waitMillis]; it has already been counted. */
		data class Granted(val waitMillis: Long) : Permit

		/** The request must not run; [message] is the result the model reads. */
		data class Refused(val message: String) : Permit
	}

	companion object {
		/** Per turn, search and fetch together. Generous for one question. */
		const val MAX_PER_TURN = 10

		/** Rendered fetches are heavy; three is about what one question needs. */
		const val MAX_RENDERED_PER_TURN = 3

		const val MIN_SPACING_MILLIS = 1_000L
	}
}
