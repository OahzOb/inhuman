package com.inhuman.ui.chat

import android.graphics.Bitmap
import com.inhuman.core.ai.ChatImage
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.documents.DocumentLimits
import com.inhuman.core.files.SessionWorkspace
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Read a scanned PDF by rendering pages and asking the current vision model.
 *
 * ## Why this is a tool and not OCR
 *
 * The document reader extracts the PDF text layer. A scanned page has no text
 * layer, and local OCR would be a second engine with language packs and a
 * different failure surface. The conversation already has a vision model for
 * attachments, so this tool reuses it: render one page to a bounded JPEG and
 * make one ordinary provider request with that image.
 *
 * ## Why it is opt-in and paid
 *
 * Each call costs one extra provider request **per page** on top of the turn
 * that asked for it. The catalogue marks it `paid` and `optIn`, so an existing
 * assistant that chose "everything" does not start paying because a new name
 * appeared (`docs/08-port-philosophy.md` §1.4).
 *
 * ## Why the images are not saved
 *
 * The page images are request payload, not documents. Writing them into the
 * workspace would leave a full copy of every page the model was shown, which
 * the user did not ask to keep. The transcription is the result; the PDF stays
 * the source.
 */
internal class PdfVisionTool(
    private val workspace: SessionWorkspace,
    private val provider: ChatProvider,
    private val model: String,
    private val userId: String,
) : Tool {

    override val name: String = ToolCatalog.PDF_VISION_TOOL

    override val description: String =
        "Read pages of a scanned PDF in the workspace with the current vision model. " +
            "Use it only after read_document reports that a page has no text layer; " +
            "it costs one extra provider request per page. It renders at most $MAX_PAGES " +
            "pages per call and returns their transcribed text. Images are not saved."

    override val parameters: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf("type" to "string", "description" to "Workspace-relative PDF path."),
            "pages" to mapOf(
                "type" to "string",
                "description" to "One-based page range, e.g. 2-4. Defaults to the first $MAX_PAGES pages; at most $MAX_PAGES pages per call.",
            ),
        ),
        "required" to listOf("path"),
    )

    override val mutates: Boolean = false

    override suspend fun execute(arguments: JsonObject): ToolResult = try {
        val path = arguments.string("path") ?: return ToolResult.error("path is required")
        val file = when (val resolved = workspace.resolve(path)) {
            is SessionWorkspace.Resolution.Ok -> resolved.file
            is SessionWorkspace.Resolution.Rejected -> return ToolResult.error(resolved.reason)
        }
        if (!file.isFile) return ToolResult.error("no such file: $path")
        if (!file.name.endsWith(".pdf", ignoreCase = true)) {
            return ToolResult.error("not a PDF: $path")
        }
        if (file.length() > DocumentLimits.MAX_BYTES) {
            return ToolResult.error(
                "$path is ${file.length()} bytes, over the ${DocumentLimits.MAX_BYTES}-byte document limit.",
            )
        }
        val bytes = file.readBytes()
        PDDocument.load(bytes).use { document ->
            val total = document.numberOfPages
            if (total <= 0) return ToolResult.error("the PDF has no pages")
            val range = pdfVisionPages(arguments.string("pages"), total).getOrElse { failure ->
                return ToolResult.error(failure.message ?: "invalid page range")
            }
            Diag.i(
                "pdfvision",
                "read path=${file.name} pages=${range.first}-${range.last} total=$total " +
                    "bytes=${bytes.size} model=${model.take(80)}",
            )
            val renderer = PDFRenderer(document)
            val result = StringBuilder()
            result.append("PDF: ${file.name}, pages ${range.first}-${range.last} of $total\n")
            result.append(
                "Transcribed by the current vision model; verify numbers and names " +
                    "against the page.\n\n",
            )
            for (page in range) {
                val image = renderPdfPage(renderer, page - 1)
                val text = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                    transcribe(page, total, image)
                } ?: return ToolResult.error("the vision request for page $page timed out")
                result.append("page $page:\n")
                result.append(text.ifBlank { "[no readable text]" })
                result.append("\n\n")
            }
            ToolResult.ok(result.toString().trim())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Diag.w("pdfvision", "render or vision request failed: ${failure.javaClass.simpleName}")
        ToolResult.error("could not read PDF pages: ${failure.message ?: "unknown error"}")
    }

    private suspend fun transcribe(page: Int, total: Int, jpeg: ByteArray): String {
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(Role.System, SYSTEM_PROMPT),
                ChatMessage(
                    role = Role.User,
                    content = "Page $page of $total. Transcribe the visible text.",
                    images = listOf(ChatImage("image/jpeg", jpeg)),
                ),
            ),
            tools = emptyList(),
            // This is transcription, not a reasoning task; thinking would bill
            // tokens for an answer that is already on the page.
            thinking = false,
            maxTokens = 4_000,
            userId = userId,
        )
        val text = StringBuilder()
        var failure: String? = null
        provider.stream(request).collect { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> text.append(chunk.text)
                is StreamChunk.Failure -> failure = chunk.message
                else -> Unit
            }
        }
        failure?.let { error("vision request failed: $it") }
        return text.toString().trim()
    }

    companion object {

        const val MAX_PAGES = 4

        /** A4 at 120 dpi is about 992×1403; the cap keeps a large page bounded. */
        internal const val RENDER_DPI = 120f

        const val MAX_DIMENSION = 1_568

        private const val PAGE_TIMEOUT_MS = 60_000L

        private const val SYSTEM_PROMPT =
            "You transcribe one page of a PDF supplied as an image. Return the visible " +
                "text in reading order, preserving headings, lists and table rows as " +
                "plain text. Do not summarize, translate, explain or describe the image. " +
                "If there is no legible text, reply exactly: [no readable text]."
    }
}

/**
 * Turn a page range argument into a bounded, one-based range.
 *
 * Kept out of the tool so the arithmetic is testable without a device. The
 * default is the first [PdfVisionTool.MAX_PAGES] pages, because a model that
 * forgets the argument should not accidentally transcribe a whole book.
 */
internal fun pdfVisionPages(text: String?, total: Int): Result<IntRange> {
    if (total <= 0) return Result.failure(IllegalArgumentException("the PDF has no pages"))
    if (text.isNullOrBlank()) {
        return Result.success(1..minOf(total, PdfVisionTool.MAX_PAGES))
    }
    val parts = text.trim().split('-').map { it.trim() }
    if (parts.size !in 1..2) {
        return Result.failure(IllegalArgumentException("pages must look like 2-4"))
    }
    val start = parts[0].toIntOrNull()
        ?: return Result.failure(IllegalArgumentException("pages must look like 2-4"))
    val end = if (parts.size == 1) start else parts[1].toIntOrNull()
        ?: return Result.failure(IllegalArgumentException("pages must look like 2-4"))
    if (start < 1 || end < start) {
        return Result.failure(IllegalArgumentException("page range must be one-based and ascending"))
    }
    if (end > total) {
        return Result.failure(IllegalArgumentException("page $end is beyond the $total-page PDF"))
    }
    if (end - start + 1 > PdfVisionTool.MAX_PAGES) {
        return Result.failure(
            IllegalArgumentException("at most ${PdfVisionTool.MAX_PAGES} pages per call; split the range"),
        )
    }
    return Result.success(start..end)
}

/** Render one page to a JPEG, downscaling only when the page exceeds the cap. */
internal fun renderPdfPage(renderer: PDFRenderer, index: Int): ByteArray {
    val original = renderer.renderImageWithDPI(index, PdfVisionTool.RENDER_DPI, ImageType.RGB)
    val longest = maxOf(original.width, original.height)
    val scaled = if (longest > PdfVisionTool.MAX_DIMENSION) {
        val factor = PdfVisionTool.MAX_DIMENSION.toFloat() / longest
        Bitmap.createScaledBitmap(
            original,
            maxOf(1, (original.width * factor).toInt()),
            maxOf(1, (original.height * factor).toInt()),
            true,
        )
    } else {
        original
    }
    try {
        val out = ByteArrayOutputStream()
        check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
            "could not encode the page image"
        }
        return out.toByteArray()
    } finally {
        if (scaled !== original) scaled.recycle()
        original.recycle()
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
