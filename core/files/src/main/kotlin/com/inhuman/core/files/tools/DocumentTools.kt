package com.inhuman.core.files.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.documents.Block
import com.inhuman.core.documents.Cell
import com.inhuman.core.documents.Csv
import com.inhuman.core.documents.DocFormat
import com.inhuman.core.documents.Docx
import com.inhuman.core.documents.Document
import com.inhuman.core.documents.DocumentException
import com.inhuman.core.documents.DocumentLimits
import com.inhuman.core.documents.LossKind
import com.inhuman.core.documents.LossReport
import com.inhuman.core.documents.Sheet
import com.inhuman.core.documents.Xlsx
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The three document tools.
 *
 * They are workspace-bound exactly like the file tools: a path is resolved by
 * [SessionWorkspace], never by this file, and no tool accepts an absolute path.
 * A document is parsed locally; there is no conversion service and no network.
 */
object DocumentTools {

	fun all(workspace: SessionWorkspace): List<Tool> = listOf(
		ReadDocumentTool(workspace),
		WriteDocumentTool(workspace),
		ConvertDocumentTool(workspace),
	)
}

private const val DOCUMENT_READ_BYTES = DocumentLimits.MAX_BYTES

private fun documentPath(description: String): Map<String, Any?> =
	mapOf("type" to "string", "description" to description)

private fun readDocument(file: File, format: DocFormat, sheet: String?): Document {
	val bytes = file.readBytes()
	if (bytes.size > DOCUMENT_READ_BYTES) {
		throw IllegalArgumentException(
			"${file.name} is ${bytes.size} bytes, over the ${DOCUMENT_READ_BYTES}-byte document limit.",
		)
	}
	return when (format) {
		DocFormat.Csv -> {
			val text = bytes.toString(Charsets.UTF_8)
			val read = Csv.read(text)
			Document(
				format = format,
				blocks = listOf(Block.Table(read.rows)),
				truncated = read.truncated,
				warnings = read.warnings +
					if (read.truncated) listOf("Only part of the CSV was read.") else emptyList(),
			)
		}
		DocFormat.Text -> Document(
			format = format,
			blocks = bytes.toString(Charsets.UTF_8).lines()
				.filter { it.isNotBlank() }
				.map(Block::Paragraph),
		)
		DocFormat.Docx -> Docx.read(bytes).document
		DocFormat.Xlsx -> {
			val read = Xlsx.read(bytes)
			val selected = if (sheet == null) {
				read.document
			} else {
				val match = read.document.sheets.firstOrNull { it.name == sheet }
					?: throw IllegalArgumentException(
						"no sheet named '$sheet'; sheets: " +
							read.document.sheets.joinToString(", ") { it.name },
					)
				read.document.copy(sheets = listOf(match))
			}
			selected
		}
		DocFormat.Pdf, DocFormat.Doc, DocFormat.Xls -> throw IllegalArgumentException(
			"${format.extension.uppercase()} reading is not wired into this build yet.",
		)
		DocFormat.Unknown -> throw IllegalArgumentException(
			"unsupported document format; supported: csv, txt, docx, xlsx.",
		)
	}
}

private fun lossFor(source: Document, target: DocFormat): LossReport {
	var loss = LossReport(
		kinds = buildSet {
			if (source.truncated) add(LossKind.Truncated)
			if (source.warnings.any { it.startsWith("unsupported") }) add(LossKind.UnsupportedFeature)
			if (source.warnings.any { it.startsWith("flattened") }) add(LossKind.Flattened)
		},
		details = source.warnings,
	)
	if (source.sheets.size > 1 && target == DocFormat.Csv) {
		loss = loss.plus(LossKind.Skipped, "only one sheet can be represented in CSV")
	}
	if (target == DocFormat.Csv && source.sheets.any { sheet -> sheet.rows.any { row -> row.any { it.formula != null } } }) {
		loss = loss.plus(LossKind.Flattened, "formulas become their cached values in CSV")
	}
	return loss
}

private fun render(document: Document): String = buildString {
	appendLine("format: ${document.format.extension}")
	if (document.truncated) appendLine("truncated: yes (the read ceiling was reached)")
	document.warnings.forEach { appendLine("warning: $it") }
	if (document.isEmpty) {
		appendLine("The document has no readable text.")
	} else {
		appendLine()
		appendLine(document.plainText())
	}
}

private fun rowsFromTables(tables: List<List<List<String>>>): List<List<String>> =
	tables.firstOrNull().orEmpty().map { row -> row.toList() }

private class ReadDocumentTool(workspace: SessionWorkspace) :
	WorkspaceTool(workspace), Tool {

	override val name = "read_document"
	override val mutates = false
	override val description =
		"Read a CSV, TXT, DOCX or XLSX file from the workspace. Returns the text, " +
			"table or sheet contents plus any warnings; it never guesses at an " +
			"unreadable file. Use pages or sheet to read only part of a large document."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"path" to documentPath("Workspace-relative document path."),
			"sheet" to mapOf("type" to "string", "description" to "XLSX sheet name."),
			"pages" to mapOf("type" to "string", "description" to "Page range for later PDF support, e.g. 1-5."),
		),
		"required" to listOf("path"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult = guard {
		val path = arguments.requireString("path")
		val file = resolve(path).getOrElse { throw it }
		if (!file.exists()) throw Refused("no such file: $path")
		if (file.isDirectory) throw Refused("$path is a directory, not a document")
		val format = DocFormat.fromName(file.name)
		try {
			render(readDocument(file, format, arguments.optionalString("sheet")))
		} catch (e: DocumentException) {
			throw Refused("could not read $path: ${e.message}")
		}
	}
}

private class WriteDocumentTool(workspace: SessionWorkspace) :
	WorkspaceTool(workspace), Tool {

	override val name = "write_document"
	override val mutates = true
	override val description =
		"Create or replace a CSV, TXT, DOCX or XLSX document in the workspace from " +
			"a title, paragraphs, tables and/or sheets. Existing DOCX/XLSX files are " +
			"modified while unrelated package parts are preserved."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"path" to documentPath("Workspace-relative destination; the extension selects the format."),
			"title" to mapOf("type" to "string", "description" to "Optional document title."),
			"paragraphs" to mapOf(
				"type" to "array",
				"items" to mapOf("type" to "string"),
				"description" to "Body paragraphs.",
			),
			"tables" to mapOf(
				"type" to "array",
				"items" to mapOf(
					"type" to "array",
					"items" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
				),
				"description" to "Tables, each a list of rows of cell strings.",
			),
			"sheets" to mapOf(
				"type" to "array",
				"items" to mapOf(
					"type" to "object",
					"properties" to mapOf(
						"name" to mapOf("type" to "string"),
						"rows" to mapOf(
							"type" to "array",
							"items" to mapOf(
								"type" to "array",
								"items" to mapOf("type" to "string"),
							),
						),
					),
					"required" to listOf("name", "rows"),
				),
			),
		),
		"required" to listOf("path"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult = guard {
		val path = arguments.requireString("path")
		val file = resolve(path).getOrElse { throw it }
		val format = DocFormat.fromName(path)
		if (format == DocFormat.Unknown) {
			throw Refused("unsupported document extension; use csv, txt, docx or xlsx")
		}
		val title = arguments.optionalString("title")?.takeIf { it.isNotBlank() }
		val paragraphs = stringList(arguments["paragraphs"])
		val tables = tableList(arguments["tables"])
		val sheets = sheetList(arguments["sheets"])
		val blocks = buildList {
			title?.let { add(Block.Heading(1, it)) }
			paragraphs.forEach { add(Block.Paragraph(it)) }
			tables.forEach { add(Block.Table(it)) }
		}

		val bytes = when (format) {
			DocFormat.Csv -> {
				val rows = if (sheets.isNotEmpty()) {
					sheets.first().rows.map { row -> row.map(Cell::value) }
				} else {
					rowsFromTables(tables).ifEmpty {
						paragraphs.map { listOf(it) }
					}
				}
				Csv.write(rows).toByteArray()
			}
			DocFormat.Text -> {
				(title?.let { "$it\n\n" }.orEmpty() + paragraphs.joinToString("\n\n"))
					.toByteArray()
			}
			DocFormat.Docx -> {
				val previous = if (file.exists() && file.isFile) file.readBytes() else null
				try {
					Docx.write(blocks, previous)
				} catch (e: DocumentException) {
					throw Refused("could not write $path: ${e.message}")
				}
			}
			DocFormat.Xlsx -> {
				val previous = if (file.exists() && file.isFile) file.readBytes() else null
				try {
					Xlsx.write(xlsxSheets(sheets, tables, paragraphs), previous)
				} catch (e: DocumentException) {
					throw Refused("could not write $path: ${e.message}")
				}
			}
			else -> throw Refused("writing ${format.extension.uppercase()} is not wired into this build yet")
		}
		val size = writeBytes(file, bytes)
		"wrote ${workspace.relativize(file)} (${format.extension}, $size bytes)"
	}

	private fun xlsxSheets(
		sheets: List<Sheet>,
		tables: List<List<List<String>>>,
		paragraphs: List<String>,
	): List<Sheet> {
		if (sheets.isNotEmpty()) return sheets
		val rows = rowsFromTables(tables).ifEmpty { paragraphs.map { listOf(it) } }
		return listOf(Sheet("Sheet1", rows.map { row -> row.map { Cell(it) } }))
	}
}

private class ConvertDocumentTool(workspace: SessionWorkspace) :
	WorkspaceTool(workspace), Tool {

	override val name = "convert_document"
	override val mutates = true
	override val description =
		"Convert a workspace document between CSV, TXT, DOCX and XLSX. Reports " +
			"exactly what was lost (formatting, formulas, sheets) instead of " +
			"claiming a clean conversion."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"source" to documentPath("Workspace-relative source document."),
			"target" to documentPath("Workspace-relative target path; extension selects the format."),
			"sheet" to mapOf("type" to "string", "description" to "Source XLSX sheet, or CSV sheet name."),
		),
		"required" to listOf("source", "target"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult = guard {
		val sourcePath = arguments.requireString("source")
		val targetPath = arguments.requireString("target")
		val sourceFile = resolve(sourcePath).getOrElse { throw it }
		val targetFile = resolve(targetPath).getOrElse { throw it }
		if (!sourceFile.exists() || sourceFile.isDirectory) {
			throw Refused("no such document: $sourcePath")
		}
		val sourceFormat = DocFormat.fromName(sourceFile.name)
		val targetFormat = DocFormat.fromName(targetPath)
		if (sourceFormat == DocFormat.Unknown || targetFormat == DocFormat.Unknown) {
			throw Refused("unsupported conversion; supported: csv, txt, docx, xlsx")
		}
		if (sourceFormat == targetFormat) {
			val bytes = sourceFile.readBytes()
			writeBytes(targetFile, bytes)
			return@guard "copied ${sourceFile.name} to $targetPath (same format; no conversion)"
		}
		val doc = try {
			readDocument(sourceFile, sourceFormat, arguments.optionalString("sheet"))
		} catch (e: DocumentException) {
			throw Refused("could not read $sourcePath: ${e.message}")
		}
		val loss = lossFor(doc, targetFormat)
		val bytes = when (targetFormat) {
			DocFormat.Csv -> Csv.write(rowsFor(doc)).toByteArray()
			DocFormat.Text -> doc.plainText().toByteArray()
			DocFormat.Docx -> Docx.write(blocksFor(doc))
			DocFormat.Xlsx -> Xlsx.write(sheetsFor(doc))
			else -> throw Refused("conversion to ${targetFormat.extension.uppercase()} is not wired into this build yet")
		}
		writeBytes(targetFile, bytes)
		val tail = if (loss.complete) "no loss reported" else "loss: ${loss.summary()}"
		"converted $sourcePath (${sourceFormat.extension}) to $targetPath " +
			"(${targetFormat.extension}); $tail"
	}

	private fun rowsFor(doc: Document): List<List<String>> {
		if (doc.sheets.isNotEmpty()) {
			return doc.sheets.first().rows.map { row -> row.map { it.value } }
		}
		val tables = doc.blocks.filterIsInstance<Block.Table>()
		if (tables.isNotEmpty()) return tables.first().rows
		return doc.blocks.mapNotNull { block ->
			when (block) {
				is Block.Heading -> listOf("#".repeat(block.level) + " " + block.text)
				is Block.Paragraph -> listOf(block.text)
				is Block.ListItem -> listOf("- " + block.text)
				is Block.Table -> null
			}
		}
	}

	private fun blocksFor(doc: Document): List<Block> {
		if (doc.blocks.isNotEmpty()) return doc.blocks
		return doc.sheets.flatMap { sheet ->
			listOf<Block>(Block.Heading(1, sheet.name)) +
				sheet.rows.map { row -> Block.Paragraph(row.joinToString(" | ") { it.value }) }
		}
	}

	private fun sheetsFor(doc: Document): List<Sheet> {
		if (doc.sheets.isNotEmpty()) return doc.sheets
		val tables = doc.blocks.filterIsInstance<Block.Table>()
		val paragraphs = doc.blocks.mapNotNull {
			when (it) {
				is Block.Heading -> "# ${it.text}"
				is Block.Paragraph -> it.text
				is Block.ListItem -> "- ${it.text}"
				is Block.Table -> null
			}
		}
		val rows = tables.firstOrNull()?.rows ?: paragraphs.map { listOf(it) }
		return listOf(Sheet("Sheet1", rows.map { row -> row.map { Cell(it) } }))
	}
}

private fun stringList(element: kotlinx.serialization.json.JsonElement?): List<String> =
	(element as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

private fun tableList(element: kotlinx.serialization.json.JsonElement?): List<List<List<String>>> =
	(element as? JsonArray)?.mapNotNull { table ->
		(table as? JsonArray)?.mapNotNull { row ->
			(row as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
		}
	} ?: emptyList()

private fun sheetList(element: kotlinx.serialization.json.JsonElement?): List<Sheet> =
	(element as? JsonArray)?.mapNotNull { item ->
		val obj = item as? JsonObject ?: return@mapNotNull null
		val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
		val rows = (obj["rows"] as? JsonArray)?.map { row ->
			(row as? JsonArray)?.map { Cell(it.jsonPrimitive.contentOrNull.orEmpty()) }.orEmpty()
		}.orEmpty()
		Sheet(name, rows)
	} ?: emptyList()