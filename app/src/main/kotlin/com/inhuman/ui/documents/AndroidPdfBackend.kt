package com.inhuman.ui.documents

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.inhuman.core.documents.DocumentException
import com.inhuman.core.documents.DocumentLimits
import com.inhuman.core.documents.Page
import com.inhuman.core.documents.PdfBackend
import com.inhuman.core.documents.PdfRead
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream

/**
 * PDF on Android: PDFBox for the text layer, the platform PDF writer for output.
 *
 * PDFBox is the maintained Android port and is used only for reading; its
 * standard fonts cannot draw CJK, so writing uses `android.graphics.pdf`, whose
 * text goes through the system font stack. A document layer without this
 * adapter refuses PDF rather than returning an empty document.
 */
class AndroidPdfBackend(context: Context) : PdfBackend {

	init {
		PDFBoxResourceLoader.init(context.applicationContext)
	}

	override fun read(bytes: ByteArray, pages: IntRange?): PdfRead {
		if (bytes.size > DocumentLimits.MAX_BYTES) {
			throw DocumentException("The PDF is larger than the read limit.")
		}
		val document = try {
			PDDocument.load(bytes)
		} catch (e: Exception) {
			throw DocumentException("The PDF could not be opened: ${e.message ?: "invalid file"}.")
		}
		document.use {
			val total = it.numberOfPages
			if (total <= 0) throw DocumentException("The PDF has no pages.")
			val start = (pages?.first ?: 1).coerceIn(1, total)
			val end = (pages?.last ?: total).coerceIn(start, total)
			val extracted = ArrayList<Page>()
			val warnings = ArrayList<String>()
			val stripper = PDFTextStripper().apply {
				sortByPosition = true
			}
			for (number in start..end) {
				stripper.startPage = number
				stripper.endPage = number
				val text = try {
					stripper.getText(it)
				} catch (e: Exception) {
					warnings += "page $number could not be read: ${e.message ?: "parser error"}"
					""
				}
				if (text.isBlank()) {
					warnings += "page $number has no text layer (it may be scanned); " +
						"read_pdf_vision can transcribe it when that tool is available."
				}
				extracted += Page(number, text.trim())
			}
			if (extracted.size >= DocumentLimits.MAX_PAGES) {
				warnings += "Only the first ${DocumentLimits.MAX_PAGES} pages were read."
			}
			return PdfRead(extracted, warnings)
		}
	}

	override fun write(pages: List<String>): ByteArray {
		val document = PdfDocument()
		try {
			val paint = Paint().apply {
				color = Color.BLACK
				textSize = 11f
				isAntiAlias = true
				typeface = android.graphics.Typeface.create(
					android.graphics.Typeface.SANS_SERIF,
					android.graphics.Typeface.NORMAL,
				)
			}
			val margin = 48f
			val leading = 15f
			val pageWidth = 595
			val pageHeight = 842
			val usableWidth = pageWidth - margin * 2
			var pageNumber = 0

			fun startPage(): PdfDocument.Page {
				pageNumber += 1
				return document.startPage(
					PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create(),
				)
			}

			fun drawText(page: PdfDocument.Page, text: String): PdfDocument.Page {
				var current = page
				var y = margin + paint.textSize
				val lines = text.lines().flatMap { line -> wrap(line, paint, usableWidth) }
				for (line in lines) {
					if (y > pageHeight - margin) {
						document.finishPage(current)
						current = startPage()
						y = margin + paint.textSize
					}
					current.canvas.drawText(line, margin, y, paint)
					y += leading
				}
				return current
			}

			// The list is a page list: a paragraph passed as page 2 must start a
			// new page, not continue the first one's flow. Content longer than a
			// page still spills onto another page inside `drawText`.
			if (pages.isEmpty()) {
				document.finishPage(startPage())
			} else {
				pages.forEach { text ->
					document.finishPage(drawText(startPage(), text))
				}
			}
			val out = ByteArrayOutputStream()
			document.writeTo(out)
			return out.toByteArray()
		} finally {
			document.close()
		}
	}

	private fun wrap(line: String, paint: Paint, width: Float): List<String> {
		if (line.isEmpty()) return listOf("")
		val result = ArrayList<String>()
		var rest = line
		while (rest.isNotEmpty()) {
			val count = paint.breakText(rest, true, width, null)
			if (count <= 0) break
			result += rest.substring(0, count)
			rest = rest.substring(count)
		}
		return if (result.isEmpty()) listOf(line) else result
	}
}