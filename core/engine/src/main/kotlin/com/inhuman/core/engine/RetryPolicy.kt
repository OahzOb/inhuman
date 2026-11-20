package com.inhuman.core.engine

import java.io.IOException
import java.security.cert.CertificateException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.math.min
import kotlin.random.Random

/**
 * Whether one failed provider request may be sent again, and how long to wait.
 *
 * ## Why the engine owns this and the provider does not
 *
 * `ChatProvider` deliberately has no retry of its own (`ChatProvider.kt`). A
 * provider knows only the request it was handed; it cannot tell whether the
 * stream already put text on screen, whether a tool call in that stream was
 * executed, or whether the user is watching a blank bubble. The engine is the
 * first layer that knows all three, so it is the first layer that can decide
 * that re-sending is safe.
 *
 * ## What is safe to retry
 *
 * A retry is a second billable completion of the same prompt. The engine only
 * retries a failed **round**, before the assistant message for that round is
 * committed and before any tool in it runs, so a retry can never execute a
 * previous round's write twice. Text that did arrive from the failed attempt is
 * discarded by the host and replaced, not concatenated.
 *
 * Only transient failures are retried. Authentication, billing, context
 * overflow, a malformed request and a missing model are answers that will not
 * change, and five more requests only make the error arrive later.
 */
data class RetryPolicy(
	/** How many re-sends follow the first attempt. Five means six requests. */
	val maxRetries: Int = DEFAULT_MAX_RETRIES,
	/** Delay before the first retry, doubled for each later one. */
	val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
	/** Ceiling for the exponential part of the delay. */
	val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
	/** Random addition to each delay, so many clients do not retry together. */
	val jitterMillis: Long = DEFAULT_JITTER_MILLIS,
) {

	init {
		require(maxRetries >= 0) { "maxRetries must be >= 0" }
		require(initialDelayMillis >= 0) { "initialDelayMillis must be >= 0" }
		require(maxDelayMillis >= 0) { "maxDelayMillis must be >= 0" }
		require(jitterMillis >= 0) { "jitterMillis must be >= 0" }
	}

	/**
	 * Whether [message], [statusCode] or [cause] describes something that can
	 * change between two requests.
	 *
	 * The status is preferred over the text whenever one is known. Error bodies
	 * are prose written by the provider: a 400 whose body happens to contain
	 * "timeout" is still a request the server refused, and retrying it five
	 * times is five more refusals.
	 */
	fun shouldRetry(
		message: String,
		statusCode: Int? = null,
		cause: Throwable? = null,
	): Boolean {
		val text = message.lowercase(Locale.ROOT)
		val status = statusCode ?: statusFrom(message)

		if (status != null) {
			when {
				status in RETRYABLE_CLIENT_STATUSES ->
					return !text.containsAny(PERMANENT_MARKERS)

				status in 500..599 ->
					return status !in PERMANENT_SERVER_STATUSES &&
						!text.containsAny(PERMANENT_MARKERS)

				else -> return false
			}
		}

		if (text.containsAny(PERMANENT_MARKERS)) return false
		// A certificate that does not verify, or a hostname that does not
		// match, will still not verify on the sixth attempt. Reporting it now
		// is more useful than reporting it after half a minute of retries.
		if (cause != null && cause.isCertificateFailure()) return false
		if (cause is IOException) return true
		return text.containsAny(TRANSIENT_MARKERS)
	}

	/**
	 * The wait before re-send number [retry] (one-based), including jitter.
	 *
	 * Exponential rather than fixed: a connection that dropped because a
	 * network changed needs a moment, while a server returning 503 under load
	 * gets progressively more room. The cap keeps the fifth wait from being
	 * longer than the user is willing to watch a spinner.
	 */
	fun delayForRetry(retry: Int, random: Random = Random.Default): Long {
		require(retry >= 1) { "retry is one-based" }
		var delay = min(initialDelayMillis, maxDelayMillis)
		repeat((retry - 1).coerceAtMost(16)) {
			delay = if (delay >= maxDelayMillis / 2) maxDelayMillis else delay * 2
		}
		val jitter = if (jitterMillis > 0) random.nextLong(jitterMillis + 1) else 0L
		return delay + jitter
	}

	companion object {
		/**
		 * Five re-sends after the first attempt, as asked for: a transient
		 * disconnect gets about half a minute of patience before the manual
		 * Retry button is the remaining answer.
		 */
		const val DEFAULT_MAX_RETRIES = 5

		const val DEFAULT_INITIAL_DELAY_MILLIS = 1_000L
		const val DEFAULT_MAX_DELAY_MILLIS = 15_000L
		const val DEFAULT_JITTER_MILLIS = 250L

		/** Client errors that mean "ask again", not "fix the request first". */
		private val RETRYABLE_CLIENT_STATUSES = setOf(408, 425, 429)

		/** Server errors that describe a permanent implementation problem. */
		private val PERMANENT_SERVER_STATUSES = setOf(501, 505, 511)

		private val PERMANENT_MARKERS = listOf(
			"api key",
			"invalid key",
			"unauthorized",
			"forbidden",
			"insufficient balance",
			"insufficient_quota",
			"exceeded your current quota",
			"billing",
			"payment required",
			"context length",
			"context window",
			"maximum context",
			"max context",
			"too many tokens",
			"token limit",
			"request too large",
			"prompt is too long",
			"content filter",
			"content_filter",
			"model not found",
			"model does not exist",
			"unknown model",
			"invalid model",
			"not a chat model",
			"unsupported model",
			"cleartext",
			"certificate",
			"hostname",
		)

		private val TRANSIENT_MARKERS = listOf(
			"timeout",
			"timed out",
			"no response",
			"network",
			"connect",
			"connection",
			"stream",
			"socket",
			"unavailable",
			"overloaded",
			"reset",
			"broken pipe",
			"unexpected end",
			"eof",
			"closed",
			"unreachable",
			"temporary failure",
			"temporarily",
			"try again",
			"server error",
			"server_error",
			"internal error",
			"rate limit",
			"rate_limit",
			"bad gateway",
			"gateway timeout",
		)

		private val STATUS = Regex("""(?i)\bHTTP\s+(\d{3})\b""")

		private fun statusFrom(message: String): Int? =
			STATUS.find(message)?.groupValues?.get(1)?.toIntOrNull()

		private fun String.containsAny(markers: List<String>): Boolean =
			markers.any(::contains)

		private fun Throwable.isCertificateFailure(): Boolean =
			generateSequence(this) { it.cause }.any {
				it is CertificateException ||
					it is SSLHandshakeException ||
					it is SSLPeerUnverifiedException
			}
	}
}
