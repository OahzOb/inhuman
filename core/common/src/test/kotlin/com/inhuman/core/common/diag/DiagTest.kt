package com.inhuman.core.common.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DiagTest {

	@Test
	fun `format puts time level and tag before the message`() {
		val line = Diag.format(
			level = Diag.Level.Info,
			tag = "turn",
			message = "start model=deepseek-flash",
			at = Instant.parse("2026-10-17T12:34:56.789Z"),
			zone = ZoneId.of("UTC"),
		)

		assertEquals("2026-10-17 12:34:56.789 I/turn start model=deepseek-flash", line)
	}

	@Test
	fun `format keeps one event on one line`() {
		val line = Diag.format(
			level = Diag.Level.Warn,
			tag = "tool",
			message = "first\nsecond\r\nthird",
			at = Instant.EPOCH,
			zone = ZoneId.of("UTC"),
		)

		assertFalse(line.contains('\n'))
		assertTrue(line.endsWith("first second third"))
	}

	@Test
	fun `format appends an indented stack trace`() {
		val error = IllegalStateException("boom")
		val line = Diag.format(
			level = Diag.Level.Error,
			tag = "crash",
			message = "uncaught",
			error = error,
			at = Instant.EPOCH,
			zone = ZoneId.of("UTC"),
		)

		assertTrue(line.startsWith("1970-01-01 00:00:00.000 E/crash uncaught\n"))
		assertTrue(line.contains("    java.lang.IllegalStateException: boom"))
		assertTrue(line.lineSequence().drop(1).all { it.startsWith("    ") })
	}

	@Test
	fun `an api key never reaches the line`() {
		val line = Diag.redact("Authorization: Bearer sk-abcdef1234567890 and x-api-key: sk-abcdef1234567890")

		assertFalse(line.contains("sk-abcdef1234567890"))
		assertTrue(line.contains("sk-***"))
	}

	@Test
	fun `a bearer token is redacted even without an sk prefix`() {
		val line = Diag.redact("header=Bearer abcdefghijklmnop")

		assertFalse(line.contains("abcdefghijklmnop"))
		assertTrue(line.contains("Bearer ***"))
	}

	@Test
	fun `a named secret is redacted`() {
		val line = Diag.redact("api_key=supersecretvalue password: hunter2")

		assertFalse(line.contains("supersecretvalue"))
		assertFalse(line.contains("hunter2"))
		assertTrue(line.contains("api_key=***"))
		assertTrue(line.contains("password=***"))
	}

	@Test
	fun `short id keeps eight characters and handles null`() {
		assertEquals("12345678", Diag.shortId("1234567890"))
		assertEquals("-", Diag.shortId(null))
	}

	@Test
	fun `no sink is a no-op rather than a failure`() {
		Diag.install(null)
		Diag.i("test", "this goes nowhere")
	}

	@Test
	fun `an installed sink receives the event and the throwable`() {
		val received = mutableListOf<String>()
		var receivedError: Throwable? = null
		Diag.install { level, tag, message, error ->
			received += "${level.letter}/$tag $message"
			receivedError = error
		}
		try {
			val boom = RuntimeException("boom")
			Diag.w("tool", "failed", boom)
		} finally {
			Diag.install(null)
		}

		assertEquals(listOf("W/tool failed"), received)
		assertTrue(receivedError is RuntimeException)
	}

	@Test
	fun `a throwing sink does not escape the logger`() {
		Diag.install { _, _, _, _ -> error("sink is broken") }
		try {
			Diag.e("test", "still must not throw")
		} finally {
			Diag.install(null)
		}
	}
}
