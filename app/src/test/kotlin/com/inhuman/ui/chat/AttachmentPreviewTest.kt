package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which attachments get a thumbnail, and at what sample size.
 *
 * The decode itself needs a device, but the two decisions that go wrong
 * silently are pure: a prose file misclassified as an image (the row would show
 * a broken preview instead of its name) and a sample size that allocates the
 * full photo while scrolling.
 */
class AttachmentPreviewTest {

	@Test
	fun `a reported image MIME is an image`() {
		assertTrue(isImageAttachment("image/jpeg", "IMG_0001.jpg"))
		assertTrue(isImageAttachment("image/png", "screenshot"))
	}

	@Test
	fun `an image extension is enough when the MIME is generic`() {
		assertTrue(isImageAttachment("application/octet-stream", "photo.HEIC"))
		assertTrue(isImageAttachment("", "diagram.webp"))
	}

	@Test
	fun `text and documents are not images`() {
		assertFalse(isImageAttachment("text/plain", "notes.txt"))
		assertFalse(isImageAttachment("application/pdf", "contract.pdf"))
		assertFalse(isImageAttachment("application/octet-stream", "no-extension"))
		assertFalse(isImageAttachment("", "archive.zip"))
	}

	@Test
	fun `a large photo is sampled down to the target`() {
		// 4000x3000 -> long side 4000; halving to >= 384 gives 512, sample 8.
		assertEquals(8, previewInSampleSize(4000, 3000, 384))
		assertEquals(1, previewInSampleSize(512, 512, 384))
		assertEquals(2, previewInSampleSize(768, 600, 384))
	}

	@Test
	fun `a sample size is never zero, even for unreadable dimensions`() {
		assertEquals(1, previewInSampleSize(0, 0))
		assertEquals(1, previewInSampleSize(-5, 100))
		assertEquals(1, previewInSampleSize(100, 100, 0))
	}

	@Test
	fun `a small image is not upscaled or re-sampled`() {
		assertEquals(1, previewInSampleSize(64, 64, 384))
	}
}
