package com.inhuman.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share target's narrowness is the security property: one item, an explicit
 * type, and no catch-all. These are the pure decisions the intent filter and the
 * save path share.
 */
class SharedContentTest {

	@Test
	fun `text, images and files are accepted`() {
		assertTrue(isShareableMime("text/plain"))
		assertTrue(isShareableMime("image/jpeg"))
		assertTrue(isShareableMime("application/pdf"))
		// Parameters are legal in a MIME type and do not change the family.
		assertTrue(isShareableMime("text/plain; charset=utf-8"))
	}

	@Test
	fun `a catch-all, media and nothing are refused`() {
		assertFalse(isShareableMime("*/*"))
		assertFalse(isShareableMime("video/mp4"))
		assertFalse(isShareableMime("audio/mpeg"))
		assertFalse(isShareableMime(null))
		assertFalse(isShareableMime("  "))
	}

	@Test
	fun `a sender-provided name is kept and flattened to one segment`() {
		assertEquals("contract.pdf", sharedFileName("application/pdf", "contract.pdf", null))
		assertEquals("a_b.pdf", sharedFileName("application/pdf", "a/b.pdf", null))
	}

	@Test
	fun `shared text has a stable name`() {
		assertEquals("shared.txt", sharedFileName("text/plain", null, "hello"))
	}

	@Test
	fun `an unnamed image gets its type's extension`() {
		assertEquals("shared.jpg", sharedFileName("image/jpeg", null, null))
		assertEquals("shared.png", sharedFileName("image/png", null, null))
	}

	@Test
	fun `an unknown file type falls back to bin`() {
		assertEquals("shared.bin", sharedFileName("application/octet-stream", null, null))
	}
}