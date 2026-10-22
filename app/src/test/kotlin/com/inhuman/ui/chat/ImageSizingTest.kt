package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind image preparation.
 *
 * `Bitmap` does not exist on the JVM, so the decode and encode are exercised by
 * the instrumentation test; these are the two rules that decide how much memory
 * that decode allocates and whether a small image gets enlarged.
 */
class ImageSizingTest {

	@Test
	fun `a photo is fitted inside the long edge`() {
		assertEquals(PixelSize(1568, 1176), ImageSizing.fitWithin(4032, 3024, 1568))
		assertEquals(PixelSize(1568, 157), ImageSizing.fitWithin(10000, 1000, 1568))
	}

	@Test
	fun `an image already inside the limit is not enlarged`() {
		assertEquals(PixelSize(800, 600), ImageSizing.fitWithin(800, 600, 1568))
		assertEquals(PixelSize(1229, 1568), ImageSizing.fitWithin(1568, 2000, 1568))
		assertEquals(PixelSize(1568, 1000), ImageSizing.fitWithin(1568, 1000, 1568))
	}

	@Test
	fun `sample size is a power of two near the target`() {
		assertEquals(1, ImageSizing.sampleSize(1000, 800, 1568))
		assertEquals(2, ImageSizing.sampleSize(4032, 3024, 1568))
		assertEquals(4, ImageSizing.sampleSize(8000, 6000, 1568))
		assertEquals(1, ImageSizing.sampleSize(3136, 2000, 1568))
		assertEquals(2, ImageSizing.sampleSize(3137, 2000, 1568))
	}

	@Test
	fun `the prepared file name follows the encoded format`() {
		val jpeg = PreparedImage(ByteArray(0), "image/jpeg", 1, 1, 85)
		val webp = PreparedImage(ByteArray(0), "image/webp", 1, 1, 90)
		assertEquals("IMG_1234.jpg", jpeg.fileName("IMG_1234.png"))
		assertEquals("screenshot.webp", webp.fileName("screenshot.png"))
		assertEquals("image.jpg", jpeg.fileName("folder/"))
	}

	@Test
	fun `invalid dimensions are refused rather than divided by zero`() {
		listOf(0, -1).forEach { bad ->
			assertTrue(
				runCatching { ImageSizing.fitWithin(bad, 10, 100) }.isFailure,
			)
			assertTrue(
				runCatching { ImageSizing.sampleSize(10, bad, 100) }.isFailure,
			)
		}
	}
}
