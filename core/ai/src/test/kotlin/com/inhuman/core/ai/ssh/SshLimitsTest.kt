package com.inhuman.core.ai.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user-ceiling model: the model may tighten, never widen.
 *
 * These are the values that decide whether `timeoutSeconds=900` becomes 900,
 * the user's 300, or the app default 120. The distinction matters: a missing
 * request must not silently select the ceiling.
 */
class SshLimitsTest {

	@Test
	fun `a missing request uses the default, not the ceiling`() {
		val limits = SshLimits(commandTimeoutCeilingMs = 300_000)
		assertEquals(120_000, limits.commandTimeoutMs(null))
		assertEquals(20_000, limits.outputBytes(null))
		assertEquals(500, limits.listEntries(null))
		assertEquals(65_536L, limits.readBytes(null))
	}

	@Test
	fun `a request below the ceiling is honoured`() {
		val limits = SshLimits(
			commandTimeoutCeilingMs = 300_000,
			outputCeilingBytes = 100_000,
			listCeilingEntries = 2_000,
			readCeilingBytes = 262_144,
		)
		assertEquals(30_000, limits.commandTimeoutMs(30))
		assertEquals(8_000, limits.outputBytes(8_000))
		assertEquals(50, limits.listEntries(50))
		assertEquals(1_024L, limits.readBytes(1_024))
	}

	@Test
	fun `a request above the ceiling is clamped`() {
		val limits = SshLimits(
			commandTimeoutCeilingMs = 300_000,
			outputCeilingBytes = 50_000,
			listCeilingEntries = 500,
			readCeilingBytes = 64L * 1024,
		)
		assertEquals(300_000, limits.commandTimeoutMs(900))
		assertEquals(50_000, limits.outputBytes(500_000))
		assertEquals(500, limits.listEntries(5_000))
		assertEquals(64L * 1024, limits.readBytes(1_000_000))
	}

	@Test
	fun `a zero request falls back to the floor, not zero`() {
		val limits = SshLimits()
		assertEquals(SshLimits.MIN_COMMAND_TIMEOUT_MS, limits.commandTimeoutMs(0))
		assertEquals(SshLimits.MIN_OUTPUT_BYTES, limits.outputBytes(0))
		assertEquals(1, limits.listEntries(0))
		assertEquals(1L, limits.readBytes(0))
	}

	@Test
	fun `a user ceiling below the default lowers the default`() {
		val limits = SshLimits(commandTimeoutCeilingMs = 60_000)
		assertEquals(60_000, limits.commandTimeoutMs(null))
		assertEquals(60_000, limits.commandTimeoutMs(300))
	}

	@Test
	fun `stored values are clamped and malformed values fall back`() {
		val limits = SshLimits.fromStored("900", "999999", "5", "1")
		assertEquals(600_000, limits.commandTimeoutCeilingMs)
		assertEquals(SshLimits.MAX_OUTPUT_BYTES, limits.outputCeilingBytes)
		assertEquals(SshLimits.MIN_LIST_ENTRIES, limits.listCeilingEntries)
		assertEquals(SshLimits.MIN_READ_BYTES, limits.readCeilingBytes)

		val fallback = SshLimits.fromStored("nonsense", null, "", "x")
		assertEquals(SshLimits(), fallback)
	}

	@Test
	fun `ceiling seconds round-trip to the UI value`() {
		assertEquals(120, SshLimits().commandTimeoutCeilingSeconds)
		assertEquals(300, SshLimits(commandTimeoutCeilingMs = 300_000).commandTimeoutCeilingSeconds)
	}
}
