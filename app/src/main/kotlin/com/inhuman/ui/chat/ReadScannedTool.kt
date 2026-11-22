package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.provider.deepseek.DeepSeekOcrApi
import com.inhuman.core.files.SessionWorkspace
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Read a scanned PDF or an image with DeepSeek's OCR service.
 *
 * ## Why this is separate from `read_document`
 *
 * The local reader handles PDFs with a text layer. A scanned page has none, and
 * reading it with the vision model (`read_pdf_vision`) costs one provider
 * request per page and tops out at four pages. This tool is the other path: one
 * paid OCR request that can cover up to ten pages (or one image), and it does
 * not require the chat model to accept images.
 *
 * ## The boundary this tool does not move
 *
 * The file is still a workspace file and the request is made by the app. The
 * model gets no URL, no host and no arbitrary upload; it can only name a file
 * inside the conversation's workspace. What is new is that the *bytes* leave the
 * device, so the catalogue marks the tool `paid` and `optIn`, and both the tool
 * description and the settings copy say so. It is not a hidden fallback: a
 * failed `read_document` still reports its own failure.
 *
 * ## Why a PDF is cropped before upload
 *
 * The OCR endpoint has no page-range parameter: it processes the whole file and
 * bills every page. Uploading a 200-page scan to read five pages would pay for
 * 200 and spend the network on them. The app already has PDFBox, so the selected
 * pages are copied into a small in-memory PDF and that is what is uploaded.
 */
internal class ReadScannedTool(
	private val api: DeepSeekOcrApi,
	private val workspace: SessionWorkspace,
) : Tool {

	override val name = "read_scanned"

	override val mutates = false

	override val description = """
		Read a scanned PDF or an image with DeepSeek OCR.

		This uploads the file (for a PDF, only the selected pages) to DeepSeek's
		OCR service and the call is billed per page. Use it when read_document
		reports that a page has no text layer, or for an image or screenshot;
		prefer read_document for a PDF that has a text layer. It returns page
		text only and saves nothing.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"path" to mapOf(
				"type" to "string",
				"description" to "Workspace-relative PDF or image path.",
			),
			"pages" to mapOf(
				"type" to "string",
				"description" to "For a PDF, a one-based page range such as 2-6. " +
					"Defaults to the first $MAX_PAGES pages; at most $MAX_PAGES pages per call.",
			),
		),
		"required" to listOf("path"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult = try {
		val path = arguments.string("path") ?: return ToolResult.error("path is required")
		val file = when (val resolved = workspace.resolve(path)) {
			is SessionWorkspace.Resolution.Ok -> resolved.file
			is SessionWorkspace.Resolution.Rejected -> return ToolResult.error(resolved.reason)
		}
		if (!file.isFile) return ToolResult.error("no such file: $path")
		if (file.length() > MAX_FILE_BYTES) {
			return ToolResult.error(
				"$path is ${file.length() / 1024 / 1024} MiB; read_scanned uploads at most " +
					"${MAX_FILE_BYTES / 1024 / 1024} MiB per call",
			)
		}
		when (file.extension.lowercase()) {
			"pdf" -> readPdf(file.readBytes(), file.name, arguments.string("pages"))
			"png", "jpg", "jpeg", "webp" ->
				readImage(file.readBytes(), file.name, arguments.string("pages"))
			else -> ToolResult.error(
				"read_scanned takes a PDF or an image (png, jpg, webp), not .${file.extension}",
			)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: Exception) {
		ToolResult.error("could not read scanned file: ${failure.message ?: failure.javaClass.simpleName}")
	}

	private suspend fun readPdf(
		bytes: ByteArray,
		name: String,
		pagesArgument: String?,
	): ToolResult {
		val source = try {
			PDDocument.load(bytes)
		} catch (passwordProtected: InvalidPasswordException) {
			return ToolResult.error("the PDF is password-protected; remove the password first")
		}
		return source.use { document ->
			val total = document.numberOfPages
			if (total <= 0) return ToolResult.error("the PDF has no pages")
			val range = scannedPages(pagesArgument, total).getOrElse { failure ->
				return ToolResult.error(failure.message ?: "invalid page range")
			}
			val cropped = cropPdfPages(bytes, range)
			if (cropped.size > MAX_FILE_BYTES) {
				return ToolResult.error(
					"pages ${range.first}-${range.last} are ${cropped.size / 1024 / 1024} MiB; " +
						"read the file in smaller page ranges",
				)
			}
			val result = api.recognize(cropped, "pages-${range.first}-${range.last}.pdf")
			format(result, name, range, total)
		}
	}

	private suspend fun readImage(
		bytes: ByteArray,
		name: String,
		pagesArgument: String?,
	): ToolResult {
		if (!pagesArgument.isNullOrBlank() && pagesArgument.trim() != "1") {
			return ToolResult.error("an image has only page 1")
		}
		return format(api.recognize(bytes, name), name, 1..1, 1)
	}

	private fun format(
		result: DeepSeekOcrApi.DeepSeekOcrResult,
		name: String,
		range: IntRange,
		totalPages: Int,
	): ToolResult {
		val pages = result.pages.sortedBy { it.index }
		if (pages.isEmpty()) return ToolResult.error("OCR returned no page text")
		val out = StringBuilder()
		out.append("OCR: ").append(name)
		out.append(", pages ").append(range.first).append('-').append(range.last)
		out.append(" of ").append(totalPages).append(".\n")
		out.append("The file was sent to DeepSeek OCR and the call is billed per page. ")
		out.append("OCR can misread characters; verify numbers and names against the page.\n")

		val returned = minOf(pages.size, range.count())
		if (returned < range.count()) {
			out.append("[the service returned $returned of ${range.count()} requested pages]\n")
		}
		for (offset in 0 until returned) {
			val page = pages[offset]
			val originalPage = range.first + offset
			val text = page.text.ifBlank { "[no readable text]" }
			out.append("\npage ").append(originalPage).append(":\n")
			val room = MAX_OUTPUT_CHARS - out.length
			if (text.length > room) {
				out.append(text.take(maxOf(0, room)))
				out.append("\n[result truncated at $MAX_OUTPUT_CHARS characters]")
				return ToolResult.ok(out.toString().trim())
			}
			out.append(text)
			if (page.truncated) {
				out.append("\n[page ").append(originalPage).append(": OCR output truncated]")
			}
			out.append('\n')
		}
		return ToolResult.ok(out.toString().trim())
	}

	companion object {

		const val MAX_PAGES = 10

		/**
		 * A mobile upload ceiling, below the API's 100 MiB.
		 *
		 * Workspace writes are capped at 4 MiB, but a user-initiated download or
		 * an older workspace can hold a larger file; this tool refuses before
		 * spending the network and the OCR fee.
		 */
		const val MAX_FILE_BYTES = 20L * 1024 * 1024

		/** Bound on the text returned to the model for one call. */
		const val MAX_OUTPUT_CHARS = 40_000
	}
}

/**
 * Turn the `pages` argument into a bounded, one-based range.
 *
 * Kept out of [ReadScannedTool] so the arithmetic is testable without a device.
 * The default is the first [ReadScannedTool.MAX_PAGES] pages, because a model
 * that forgets the argument should not upload a whole book.
 */
internal fun scannedPages(text: String?, total: Int): Result<IntRange> {
	if (total <= 0) return Result.failure(IllegalArgumentException("the PDF has no pages"))
	if (text.isNullOrBlank()) {
		return Result.success(1..minOf(total, ReadScannedTool.MAX_PAGES))
	}
	val parts = text.trim().split('-').map { it.trim() }
	if (parts.size !in 1..2) {
		return Result.failure(IllegalArgumentException("pages must look like 2-6"))
	}
	val start = parts[0].toIntOrNull()
		?: return Result.failure(IllegalArgumentException("pages must look like 2-6"))
	val end = if (parts.size == 1) {
		start
	} else {
		parts[1].toIntOrNull()
			?: return Result.failure(IllegalArgumentException("pages must look like 2-6"))
	}
	if (start < 1 || end < start) {
		return Result.failure(IllegalArgumentException("page range must be one-based and ascending"))
	}
	if (end > total) {
		return Result.failure(IllegalArgumentException("page $end is beyond the $total-page PDF"))
	}
	if (end - start + 1 > ReadScannedTool.MAX_PAGES) {
		return Result.failure(
			IllegalArgumentException(
				"at most ${ReadScannedTool.MAX_PAGES} pages per call; split the range",
			),
		)
	}
	return Result.success(start..end)
}

/**
 * Copy [range] out of a PDF into a new in-memory document.
 *
 * The OCR endpoint has no page-range argument and bills every page in the
 * upload, so the client trims the PDF before the call. Kept out of the tool so
 * the copy can be tested without a device or a network.
 */
internal fun cropPdfPages(bytes: ByteArray, range: IntRange): ByteArray {
	PDDocument.load(bytes).use { source ->
		PDDocument().use { output ->
			for (page in range) output.importPage(source.getPage(page - 1))
			val buffer = ByteArrayOutputStream()
			output.save(buffer)
			return buffer.toByteArray()
		}
	}
}

private fun JsonObject.string(name: String): String? =
	(this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
		?.takeIf { it.isNotBlank() }
