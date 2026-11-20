package com.inhuman.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.files.SessionWorkspace
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole `read_pdf_vision` path short of the network.
 *
 * The PDF is image-only, so this is the scanned case: a page rasterized by
 * PDFBox, encoded as JPEG, and handed to the provider as a real image part. A
 * fake provider records the request and returns transcription text. A live
 * provider call is the only thing left, and that is what the user's model and
 * key supply.
 */
@RunWith(AndroidJUnit4::class)
class PdfVisionToolTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun imageOnlyPdfIsRenderedAndSentToVision() {
        runBlocking {
            val root = File(context.cacheDir, "pdfvision-test").apply {
                deleteRecursively()
                mkdirs()
            }
            val pdf = File(root, "scan.pdf")
            writeImageOnlyPdf(pdf)
            val workspace = SessionWorkspace.open(root)
            val seen = AtomicReference<ChatRequest>()

            val provider = object : ChatProvider {
                override val id: String = "fake-vision"
                override suspend fun listModels(): List<RemoteModel> = emptyList()
                override fun stream(request: ChatRequest): Flow<StreamChunk> {
                    seen.set(request)
                    return flowOf(StreamChunk.TextDelta("Invoice total 42"))
                }
            }

            val result = PdfVisionTool(
                workspace = workspace,
                provider = provider,
                model = "fake-vision-model",
                userId = "test-conversation",
            ).execute(buildJsonObject {
                put("path", "scan.pdf")
                put("pages", "1")
            })

            assertFalse(result.content, result.isError)
            assertTrue(result.content, result.content.contains("Invoice total 42"))
            val images = seen.get().messages.flatMap { it.images }
            assertEquals("one page image on the wire", 1, images.size)
            assertEquals("image/jpeg", images.single().mimeType)
            assertTrue("the image has bytes", images.single().bytes.size > 1_000)
            root.deleteRecursively()
        }
    }

    /** A page made of pixels only, so no text layer exists. */
    private fun writeImageOnlyPdf(file: File) {
        val document = PdfDocument()
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(400, 600, 1).create(),
        )
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText(
            "scanned invoice",
            24f,
            80f,
            Paint().apply {
                color = Color.BLACK
                textSize = 28f
            },
        )
        page.canvas.drawBitmap(bitmap, 0f, 0f, null)
        document.finishPage(page)
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        bitmap.recycle()
    }
}
