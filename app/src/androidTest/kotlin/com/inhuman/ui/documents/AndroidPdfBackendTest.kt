package com.inhuman.ui.documents

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.documents.DocumentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PDF is the one format whose reader/writer are Android libraries. A JVM test
 * cannot prove either the PDFBox port or `android.graphics.pdf` works, so this
 * runs on the device: write a page, read its text layer back, and check that a
 * broken file is a refusal rather than a crash.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPdfBackendTest {

	private val backend = AndroidPdfBackend(
		InstrumentationRegistry.getInstrumentation().targetContext,
	)

	@Test
	fun writesAndReadsAPdfTextLayer() {
		val bytes = backend.write(listOf("Hello PDF\nsecond line"))
		assertTrue(bytes.size > 100)

		val read = backend.read(bytes)
		assertEquals(1, read.pages.size)
		assertTrue(read.pages.single().text, read.pages.single().text.contains("Hello PDF"))
	}

	@Test
	fun pageRangeSelectsOnePage() {
		val bytes = backend.write(listOf("first page", "second page"))
		val read = backend.read(bytes, 2..2)

		assertEquals(1, read.pages.size)
		assertEquals(2, read.pages.single().number)
		assertTrue(read.pages.single().text.contains("second"))
	}

	@Test
	fun chineseTextSurvivesThePlatformWriter() {
		val bytes = backend.write(listOf("你好，世界\n第二行"))
		val text = backend.read(bytes).pages.joinToString(" ") { it.text }

		assertTrue(text, text.contains("你好"))
	}

	@Test(expected = DocumentException::class)
	fun aBrokenPdfIsRefused() {
		backend.read("not a pdf".toByteArray())
	}
}