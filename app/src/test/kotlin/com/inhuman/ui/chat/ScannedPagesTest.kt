package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-range arithmetic behind `read_scanned`.
 *
 * This is the part a device cannot verify at a useful sampling rate, and the
 * part that decides how much of a scanned document is uploaded and paid for:
 * the default must be bounded and an explicit range must be checked before any
 * page is copied into the upload PDF.
 */
class ScannedPagesTest {

	@Test
	fun `the default is the first ten pages`() {
		assertEquals(1..10, scannedPages(null, 200).getOrThrow())
		assertEquals(1..10, scannedPages("", 200).getOrThrow())
	}

	@Test
	fun `a short pdf defaults to all its pages`() {
		assertEquals(1..3, scannedPages(null, 3).getOrThrow())
	}

	@Test
	fun `a single page and a range are honoured`() {
		assertEquals(5..5, scannedPages("5", 20).getOrThrow())
		assertEquals(4..8, scannedPages("4-8", 20).getOrThrow())
	}

	@Test
	fun `more than ten pages is refused rather than silently capped`() {
		val failure = scannedPages("1-11", 50).exceptionOrNull()
		assertTrue(failure?.message.orEmpty().contains("10"))
	}

	@Test
	fun `a page beyond the pdf is refused`() {
		val failure = scannedPages("18-21", 20).exceptionOrNull()
		assertTrue(failure?.message.orEmpty().contains("21"))
	}

	@Test
	fun `malformed ranges are refused`() {
		listOf("x", "0-2", "5-3", "1-2-3", "-1", "2-").forEach { argument ->
			assertTrue("must refuse: $argument", scannedPages(argument, 20).isFailure)
		}
	}

	@Test
	fun `a pdf with no pages is refused`() {
		assertTrue(scannedPages(null, 0).isFailure)
	}
}
