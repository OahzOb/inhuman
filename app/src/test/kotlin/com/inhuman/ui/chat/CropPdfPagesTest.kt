package com.inhuman.ui.chat

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The local PDF crop behind `read_scanned`.
 *
 * The OCR endpoint has no page range and bills every uploaded page, so this
 * copy decides both cost and how much of the document leaves the device. It is
 * pure enough to test on the JVM, which is where the app's other PDF arithmetic
 * is tested too.
 */
class CropPdfPagesTest {

	@Test
	fun `cropping keeps only the requested pages`() {
		val source = blankPdf(3)
		assertEquals(3, pageCount(source))

		val cropped = cropPdfPages(source, 2..3)

		assertEquals(2, pageCount(cropped))
	}

	@Test
	fun `a single page range produces one page`() {
		val cropped = cropPdfPages(blankPdf(5), 4..4)
		assertEquals(1, pageCount(cropped))
	}

	@Test
	fun `a range starting at one keeps the beginning`() {
		val cropped = cropPdfPages(blankPdf(4), 1..2)
		assertEquals(2, pageCount(cropped))
	}

	private fun blankPdf(pages: Int): ByteArray {
		PDDocument().use { document ->
			repeat(pages) { document.addPage(PDPage()) }
			val out = ByteArrayOutputStream()
			document.save(out)
			return out.toByteArray()
		}
	}

	private fun pageCount(bytes: ByteArray): Int =
		PDDocument.load(bytes).use { it.numberOfPages }
}
