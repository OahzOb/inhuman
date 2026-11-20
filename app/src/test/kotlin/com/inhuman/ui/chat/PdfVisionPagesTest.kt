package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page arithmetic behind `read_pdf_vision`.
 *
 * A model that forgets the argument must not transcribe a whole book, and a
 * range that names page 400 of a 12-page scan must be a refusal rather than an
 * empty result. The renderer and the vision request need a device; this part
 * does not.
 */
class PdfVisionPagesTest {

    @Test
    fun `the default is the first four pages`() {
        assertEquals(1..4, pdfVisionPages(null, 10).getOrThrow())
        assertEquals(1..3, pdfVisionPages("", 3).getOrThrow())
    }

    @Test
    fun `an explicit range is kept`() {
        assertEquals(2..4, pdfVisionPages("2-4", 10).getOrThrow())
        assertEquals(7..7, pdfVisionPages("7", 10).getOrThrow())
    }

    @Test
    fun `a range over the per-call ceiling is refused`() {
        val failure = pdfVisionPages("1-5", 10).exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("at most 4"))
    }

    @Test
    fun `a page beyond the document is refused`() {
        val failure = pdfVisionPages("8-9", 5).exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(
            failure?.message.orEmpty(),
            failure?.message.orEmpty().contains("beyond the 5-page PDF"),
        )
    }

    @Test
    fun `backwards and zero pages are refused`() {
        assertTrue(pdfVisionPages("4-2", 10).isFailure)
        assertTrue(pdfVisionPages("0-2", 10).isFailure)
        assertTrue(pdfVisionPages("abc", 10).isFailure)
    }
}
