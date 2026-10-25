package com.inhuman.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The one size label the app uses.
 *
 * It replaces six copies that disagreed about two things: five of them divided
 * by 1024 and printed "MB", and two passed no locale to `String.format`, so the
 * decimal separator followed the device. Both are asserted here, because a
 * number that reads as `1,5 MB` on a German phone looks like a different
 * quantity, and a size label that is off by 5% is worse than no label.
 */
class BytesFormatTest {

	@Test
	fun `bytes are labelled with the unit they are divided by`() {
		assertEquals("0 B", formatBytes(0))
		assertEquals("1023 B", formatBytes(1023))
		assertEquals("1.0 KiB", formatBytes(1024))
		assertEquals("1.5 KiB", formatBytes(1536))
		assertEquals("1.0 MiB", formatBytes(1024L * 1024))
		assertEquals("256.0 MiB", formatBytes(256L * 1024 * 1024))
		assertEquals("1.0 GiB", formatBytes(1024L * 1024 * 1024))
		assertEquals("2.5 GiB", formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
	}

	@Test
	fun `the decimal separator does not follow the device`() {
		// The bug this pins: `String.format("%.1f MB", ...)` with no locale
		// rendered "1,5 MB" wherever the device's locale says so, next to
		// "1.5 MiB" from the one call site that passed a locale.
		val original = Locale.getDefault()
		try {
			Locale.setDefault(Locale.GERMANY)
			assertEquals("1.5 MiB", formatBytes(1024L * 1024 + 524288L))
			assertEquals("1.2 KiB", formatBytes(1229))
		} finally {
			Locale.setDefault(original)
		}
	}
}