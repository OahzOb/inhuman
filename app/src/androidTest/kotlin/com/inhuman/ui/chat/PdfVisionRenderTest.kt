package com.inhuman.ui.chat

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.ui.documents.AndroidPdfBackend
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rasterizing half of `read_pdf_vision`, on a device.
 *
 * The range arithmetic and the provider request are tested elsewhere; what
 * cannot run on the JVM is PDFBox's renderer producing a bounded JPEG. That is
 * also the half a scanned PDF depends on, since there is no text to extract.
 */
@RunWith(AndroidJUnit4::class)
class PdfVisionRenderTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `a PDF page becomes a bounded JPEG`() {
        val bytes = AndroidPdfBackend(context).write(
            listOf("The quick brown fox\njumps over the lazy dog"),
        )
        PDDocument.load(bytes).use { document ->
            assertTrue("the fixture should have one page", document.numberOfPages == 1)
            val jpeg = renderPdfPage(PDFRenderer(document), 0)
            assertTrue("the JPEG should have bytes", jpeg.size > 1_000)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            assertTrue("page width", bounds.outWidth in 1..PdfVisionTool.MAX_DIMENSION)
            assertTrue("page height", bounds.outHeight in 1..PdfVisionTool.MAX_DIMENSION)
        }
    }
}
