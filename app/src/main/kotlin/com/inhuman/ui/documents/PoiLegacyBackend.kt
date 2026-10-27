package com.inhuman.ui.documents

import android.content.Context
import com.inhuman.core.documents.Block
import com.inhuman.core.documents.Cell
import com.inhuman.core.documents.DocumentLimits
import com.inhuman.core.documents.LegacyOfficeBackend
import com.inhuman.core.documents.Sheet
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.ss.usermodel.DataFormatter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Legacy DOC/XLS through Apache POI.
 *
 * These formats are compound binary files, not zips of XML; POI is the mature
 * Apache-2.0 implementation and is isolated here so a failure is a refusal with
 * a reason, not an empty document. DOC reading extracts paragraphs only; DOC
 * writing would need a Word 97 template and is refused rather than producing a
 * file with the wrong bytes.
 *
 * POI touches `java.awt` in some code paths. This adapter only uses the text and
 * cell APIs, and every call is wrapped so a missing Android class becomes a
 * tool-level error instead of a process crash.
 */
class PoiLegacyBackend(private val context: Context) : LegacyOfficeBackend {

	override fun readDoc(bytes: ByteArray): List<Block> {
		HWPFDocument(ByteArrayInputStream(bytes)).use { document ->
			val paragraphs = WordExtractor(document).use { it.paragraphText }
			return paragraphs.asSequence()
				.map { it.replace("\u0007", "").trim() }
				.filter { it.isNotEmpty() }
				.take(DocumentLimits.MAX_PARAGRAPHS)
				.map(Block::Paragraph)
				.toList()
		}
	}

	override fun readXls(bytes: ByteArray): List<Sheet> {
		HSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
			val formatter = DataFormatter(Locale.ROOT)
			return (0 until minOf(workbook.numberOfSheets, 100)).map { index ->
				val sheet = workbook.getSheetAt(index)
				val rows = ArrayList<List<Cell>>()
				for (row in sheet) {
					if (rows.size >= DocumentLimits.MAX_ROWS) break
					val cells = ArrayList<Cell>()
					for (cell in row) {
						if (cells.size >= DocumentLimits.MAX_COLUMNS) break
						val formula = runCatching { cell.cellFormula }.getOrNull()
						cells += Cell(
							value = formatter.formatCellValue(cell),
							formula = formula?.takeIf { it.isNotBlank() },
							numeric = cell.cellType == org.apache.poi.ss.usermodel.CellType.NUMERIC,
						)
					}
					if (cells.isNotEmpty()) rows += cells
				}
				Sheet(sheet.sheetName, rows)
			}
		}
	}

	override fun writeDoc(blocks: List<Block>): ByteArray {
		// A blank Word 97 file is a template, not a generated format. The app
		// writes text into its empty range; it does not rename a DOCX or RTF.
		val template = try {
			context.assets.open("blank.doc").use { it.readBytes() }
		} catch (e: Exception) {
			throw UnsupportedOperationException("The Word 97 template asset is missing.", e)
		}
		val document = HWPFDocument(ByteArrayInputStream(template))
		try {
			val text = blocks.joinToString("\n") { block ->
				when (block) {
					is Block.Heading -> block.text
					is Block.Paragraph -> block.text
					is Block.ListItem -> "• " + block.text
					is Block.Table -> block.rows.joinToString("\n") { row ->
						row.joinToString(" | ")
					}
				}
			}
			document.range.replaceText(text, true)
			val out = ByteArrayOutputStream()
			document.write(out)
			return out.toByteArray()
		} finally {
			document.close()
		}
	}

	override fun writeXls(sheets: List<Sheet>): ByteArray {
		HSSFWorkbook().use { workbook ->
			sheets.take(100).forEachIndexed { sheetIndex, source ->
				val sheet = workbook.createSheet(source.name.ifBlank { "Sheet${sheetIndex + 1}" })
				source.rows.take(DocumentLimits.MAX_ROWS).forEachIndexed { rowIndex, sourceRow ->
					val row = sheet.createRow(rowIndex)
					sourceRow.take(DocumentLimits.MAX_COLUMNS).forEachIndexed { column, cell ->
						val target = row.createCell(column)
						val number = cell.value.toDoubleOrNull()
						when {
							!cell.formula.isNullOrBlank() -> target.cellFormula = cell.formula
							cell.numeric && number != null -> target.setCellValue(number)
							else -> target.setCellValue(cell.value)
						}
					}
				}
			}
			val out = ByteArrayOutputStream()
			workbook.write(out)
			return out.toByteArray()
		}
	}
}