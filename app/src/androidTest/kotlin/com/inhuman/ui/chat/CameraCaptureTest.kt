package com.inhuman.ui.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The FileProvider half of "take a photo", on a device.
 *
 * The launcher itself cannot be asserted here -- it is a system camera activity
 * -- but the contract that fails silently is the write grant: a mistake in
 * `capture_paths.xml` or the authority only shows up as an empty file after the
 * camera returns. This drives the same bytes-in/bytes-out path the camera uses.
 */
@RunWith(AndroidJUnit4::class)
class CameraCaptureTest {

	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun `the capture provider writes and reads the uri the camera receives`() {
		val file = CameraCapture.newFile(context)
		val uri = CameraCapture.uri(context, file)
		val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)

		assertEquals("content", uri.scheme)
		context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
			?: error("the provider offered no output stream")
		val read = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
		assertArrayEquals(bytes, read)
		assertTrue(file.delete())
	}

	@Test
	fun `a saved capture name resolves to the same file`() {
		val file = CameraCapture.newFile(context)
		val resolved = CameraCapture.file(context, file.name)
		assertEquals(file.absolutePath, resolved.absolutePath)
		assertTrue(file.delete())
	}

	@Test
	fun `an abandoned capture is swept by the next one`() {
		val directory = File(context.cacheDir, "camera").apply { mkdirs() }
		val stale = File(directory, "capture-abandoned.jpg").apply {
			assertTrue(createNewFile())
			assertTrue(setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000))
		}

		val fresh = CameraCapture.newFile(context)
		assertFalse("a capture older than the ceiling must not accumulate", stale.exists())
		assertTrue(fresh.delete())
	}
}
