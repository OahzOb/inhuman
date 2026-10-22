package com.inhuman.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The real decode/scale/encode path, on a device.
 *
 * `ImageSizingTest` covers the arithmetic; this covers the parts the JVM cannot
 * run: `BitmapFactory`, EXIF orientation, `Bitmap.compress`, and the actual
 * byte ceiling. The fixture is generated rather than checked in, so the test
 * does not depend on a binary asset.
 */
@RunWith(AndroidJUnit4::class)
class ImagePreprocessorTest {

	private fun jpegOf(width: Int, height: Int): ByteArray {
		val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		canvas.drawColor(Color.rgb(30, 60, 90))
		val paint = Paint().apply { color = Color.WHITE }
		for (i in 0 until 8) {
			canvas.drawRect(
				(i * width / 8f),
				0f,
				(i * width / 8f) + width / 16f,
				height.toFloat(),
				paint,
			)
		}
		val out = ByteArrayOutputStream()
		check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
		bitmap.recycle()
		return out.toByteArray()
	}

	@Test
	fun `a large photo is bounded by the long edge and the byte limit`() {
		val prepared = ImagePreprocessor.prepare(
			open = { ByteArrayInputStream(jpegOf(4000, 3000)) },
			sourceMime = "image/jpeg",
			maxBytes = 1L shl 20,
		)

		assertEquals(1568, prepared.width)
		assertEquals(1176, prepared.height)
		assertEquals("image/jpeg", prepared.mimeType)
		assertTrue("prepared image is ${prepared.bytes.size} bytes", prepared.bytes.size <= 1L shl 20)
	}

	@Test
	fun `an opaque image inside the limit is not enlarged`() {
		val prepared = ImagePreprocessor.prepare(
			open = { ByteArrayInputStream(jpegOf(800, 600)) },
			sourceMime = "image/jpeg",
			maxBytes = 1L shl 20,
		)
		assertEquals(800, prepared.width)
		assertEquals(600, prepared.height)
	}

	@Test
	fun `a png with transparency becomes webp`() {
		val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
		bitmap.eraseColor(Color.TRANSPARENT)
		val canvas = Canvas(bitmap)
		canvas.drawCircle(450f, 300f, 200f, Paint().apply { color = Color.RED })
		val out = ByteArrayOutputStream()
		check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
		bitmap.recycle()

		val prepared = ImagePreprocessor.prepare(
			open = { ByteArrayInputStream(out.toByteArray()) },
			sourceMime = "image/png",
			maxBytes = 1L shl 20,
		)
		assertEquals("image/webp", prepared.mimeType)
	}
}
