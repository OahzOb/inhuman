package com.inhuman.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random

class RetryPolicyTest {

	private val policy = RetryPolicy(
		initialDelayMillis = 1_000,
		maxDelayMillis = 15_000,
		jitterMillis = 0,
	)

	@Test
	fun `server errors and rate limits are retryable`() {
		assertTrue(policy.shouldRetry("HTTP 500 Internal Server Error", statusCode = 500))
		assertTrue(policy.shouldRetry("HTTP 502 Bad Gateway", statusCode = 502))
		assertTrue(policy.shouldRetry("HTTP 503 Service Unavailable", statusCode = 503))
		assertTrue(policy.shouldRetry("HTTP 504 Gateway Timeout", statusCode = 504))
		assertTrue(policy.shouldRetry("HTTP 408 Request Timeout", statusCode = 408))
		assertTrue(policy.shouldRetry("HTTP 429 Too Many Requests", statusCode = 429))
	}

	@Test
	fun `a status in the message is read when no code is carried`() {
		assertTrue(policy.shouldRetry("HTTP 503 Service Unavailable: upstream overloaded"))
		assertFalse(policy.shouldRetry("HTTP 401 Unauthorized: invalid api key"))
	}

	@Test
	fun `authentication and request errors are not retried`() {
		assertFalse(policy.shouldRetry("HTTP 400 Bad Request", statusCode = 400))
		assertFalse(policy.shouldRetry("HTTP 401 Unauthorized", statusCode = 401))
		assertFalse(policy.shouldRetry("HTTP 403 Forbidden", statusCode = 403))
		assertFalse(policy.shouldRetry("HTTP 404 Not Found", statusCode = 404))
		assertFalse(policy.shouldRetry("HTTP 422 Unprocessable Entity", statusCode = 422))
	}

	@Test
	fun `context overflow is a request error, not a transient one`() {
		assertFalse(
			policy.shouldRetry(
				"HTTP 400 Bad Request: maximum context length is 65536 tokens",
				statusCode = 400,
			),
		)
	}

	@Test
	fun `a billing error is not retried even when the status is 429`() {
		assertFalse(
			policy.shouldRetry(
				"HTTP 429 Too Many Requests: insufficient balance",
				statusCode = 429,
			),
		)
		assertFalse(
			policy.shouldRetry(
				"HTTP 503 Service Unavailable: billing required",
				statusCode = 503,
			),
		)
	}

	@Test
	fun `an IOException without a status is retryable`() {
		assertTrue(
			policy.shouldRetry(
				"Failed to connect to /10.0.2.2:443",
				cause = IOException("connection refused"),
			),
		)
		assertTrue(
			policy.shouldRetry(
				"No response headers within 90s",
				cause = IOException("no response"),
			),
		)
	}

	@Test
	fun `a certificate failure is reported instead of retried`() {
		assertFalse(
			policy.shouldRetry(
				"handshake failed",
				cause = SSLHandshakeException("certificate verify failed"),
			),
		)
	}

	@Test
	fun `stream-level transient errors are retried`() {
		assertTrue(policy.shouldRetry("Overloaded"))
		assertTrue(policy.shouldRetry("rate_limit_exceeded"))
		assertTrue(policy.shouldRetry("server_error"))
	}

	@Test
	fun `an unknown failure is not guessed into a retry`() {
		assertFalse(policy.shouldRetry("transport exploded", cause = IllegalStateException("boom")))
		assertFalse(policy.shouldRetry(""))
	}

	@Test
	fun `backoff doubles and stops at the ceiling`() {
		assertEquals(1_000L, policy.delayForRetry(1))
		assertEquals(2_000L, policy.delayForRetry(2))
		assertEquals(4_000L, policy.delayForRetry(3))
		assertEquals(8_000L, policy.delayForRetry(4))
		assertEquals(15_000L, policy.delayForRetry(5))
		assertEquals(15_000L, policy.delayForRetry(6))
	}

	@Test
	fun `jitter stays inside its bound`() {
		val jittered = RetryPolicy(initialDelayMillis = 1_000, maxDelayMillis = 15_000, jitterMillis = 250)
		repeat(50) {
			val delay = jittered.delayForRetry(1, Random(it))
			assertTrue("delay was $delay", delay in 1_000L..1_250L)
		}
	}
}
