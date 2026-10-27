package com.inhuman.core.documents

/** The formats the first release reads and writes. */
enum class DocFormat(val extension: String) {
	Csv("csv"),
	Docx("docx"),
	Xlsx("xlsx"),
	Pdf("pdf"),
	Doc("doc"),
	Xls("xls"),
	Text("txt"),
	Unknown("");

	companion object {
		fun fromName(name: String): DocFormat {
			val ext = name.substringAfterLast('.', "").lowercase()
			return entries.firstOrNull { it.extension == ext } ?: Unknown
		}
	}
}

/**
 * A block of readable content.
 *
 * Deliberately small: fonts, colours, margins, charts, comments, macros and
 * images are not represented. A reader that sees one records it in a
 * [LossReport] rather than pretending the neutral model kept it.
 */
sealed interface Block {
	data class Heading(val level: Int, val text: String) : Block
	data class Paragraph(val text: String) : Block
	data class ListItem(val level: Int, val text: String) : Block
	data class Table(val rows: List<List<String>>) : Block
}

/** One workbook cell. [formula] is kept as text; the cached value is [value]. */
data class Cell(
	val value: String = "",
	val formula: String? = null,
	val numeric: Boolean = false,
)

data class Sheet(
	val name: String,
	val rows: List<List<Cell>> = emptyList(),
)

data class Page(val number: Int, val text: String)

/**
 * The common read result.
 *
 * [truncated] is true whenever a ceiling cut the input; [warnings] names what
 * was dropped. A caller must surface both, not just the text.
 */
data class Document(
	val format: DocFormat,
	val blocks: List<Block> = emptyList(),
	val sheets: List<Sheet> = emptyList(),
	val pages: List<Page> = emptyList(),
	val truncated: Boolean = false,
	val warnings: List<String> = emptyList(),
) {
	val isEmpty: Boolean
		get() = blocks.isEmpty() && sheets.isEmpty() && pages.isEmpty()

	/** Plain text for a model, without binary data or layout. */
	fun plainText(): String = buildString {
		blocks.forEach { block ->
			when (block) {
				is Block.Heading -> append("#".repeat(block.level.coerceIn(1, 6)))
					.append(' ')
					.appendLine(block.text)
				is Block.Paragraph -> appendLine(block.text)
				is Block.ListItem -> {
					append("  ".repeat(block.level.coerceAtLeast(0)))
					append("- ")
					appendLine(block.text)
				}
				is Block.Table -> block.rows.forEach { row ->
					appendLine(row.joinToString(" | "))
				}
			}
			appendLine()
		}
		sheets.forEach { sheet ->
			appendLine("## ${sheet.name}")
			sheet.rows.forEach { row ->
				appendLine(row.joinToString("\t") { it.value })
			}
			appendLine()
		}
		pages.forEach { page ->
			appendLine("--- page ${page.number} ---")
			appendLine(page.text)
		}
	}.trimEnd()
}

/** What a conversion could not carry. */
enum class LossKind {
	/** A source feature has no place in the neutral model. */
	UnsupportedFeature,

	/** A formula became a cached value, or PDF text lost its columns. */
	Flattened,

	/** Rows, pages or sheets beyond the read ceiling. */
	Skipped,

	/** Input bytes were cut at a ceiling. */
	Truncated,
}

data class LossReport(
	val kinds: Set<LossKind> = emptySet(),
	val details: List<String> = emptyList(),
) {
	val complete: Boolean get() = kinds.isEmpty()

	fun plus(kind: LossKind, detail: String): LossReport =
		copy(kinds = kinds + kind, details = details + detail)

	fun summary(): String = if (complete) {
		"no loss"
	} else {
		kinds.joinToString(", ") { it.name } + ": " + details.joinToString("; ")
	}

	companion object {
		val COMPLETE = LossReport()
	}
}

/** Read/write ceilings, shared so a caller cannot invent a larger one. */
object DocumentLimits {
	const val MAX_TEXT_CHARS = 2_000_000
	const val MAX_ROWS = 50_000
	const val MAX_COLUMNS = 1_000
	const val MAX_PARAGRAPHS = 20_000
	const val MAX_PAGES = 200
	const val MAX_BYTES = 4L * 1024 * 1024
}