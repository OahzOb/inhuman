package com.inhuman.core.documents

/**
 * RFC 4180 CSV, read as a stream of fields.
 *
 * This exists instead of a dependency because the hard part is not splitting on
 * commas: it is quotes that contain commas and newlines, doubled quotes, a BOM
 * from Excel, and limiting a 100 MB export before it exhausts a phone. The
 * parser is a character state machine, so it never builds an intermediate list
 * of lines and never treats a newline inside a quoted field as a row boundary.
 */
object Csv {

	data class ReadResult(
		val rows: List<List<String>>,
		val truncated: Boolean = false,
		val warnings: List<String> = emptyList(),
	)

	fun read(
		text: String,
		maxRows: Int = DocumentLimits.MAX_ROWS,
		maxColumns: Int = DocumentLimits.MAX_COLUMNS,
	): ReadResult {
		val input = text.removePrefix("\uFEFF")
		val rows = mutableListOf<List<String>>()
		var row = mutableListOf<String>()
		val field = StringBuilder()
		var quoted = false
		var afterQuote = false
		var warning: String? = null
		var truncated = false
		var i = 0

		fun endField() {
			row += field.toString()
			field.setLength(0)
			afterQuote = false
			if (row.size > maxColumns) {
				truncated = true
				warning = warning ?: "A row had more than $maxColumns columns; extras were skipped."
			}
		}

		fun endRow(final: Boolean = false) {
			if (!final && row.isEmpty() && field.isEmpty() && !afterQuote) {
				// A blank record carries no fields; Excel also writes a final
				// newline, which must not become an extra row.
				return
			}
			endField()
			if (rows.size < maxRows) {
				rows += row.take(maxColumns)
			} else {
				truncated = true
			}
			row = mutableListOf()
		}

		while (i < input.length) {
			val c = input[i]
			if (quoted) {
				when {
					c == '"' && i + 1 < input.length && input[i + 1] == '"' -> {
						field.append('"')
						i++
					}
					c == '"' -> {
						quoted = false
						afterQuote = true
					}
					else -> field.append(c)
				}
				i++
				continue
			}
			when {
				c == '"' && field.isEmpty() && !afterQuote -> quoted = true
				c == ',' -> endField()
				c == '\r' -> {
					if (i + 1 < input.length && input[i + 1] == '\n') i++
					endRow()
				}
				c == '\n' -> endRow()
				afterQuote -> {
					// RFC says only a delimiter/newline may follow a closing
					// quote. Being lenient here keeps a hand-edited file usable,
					// but says so once.
					warning = warning ?: "A quoted field had characters after its closing quote."
					field.append(c)
					afterQuote = false
				}
				else -> field.append(c)
			}
			i++
		}

		if (quoted) {
			warning = "The file ended inside a quoted field; the final value may be incomplete."
		}
		if (field.isNotEmpty() || row.isNotEmpty() || afterQuote) {
			if (rows.size < maxRows) {
				endRow(final = true)
			} else {
				truncated = true
			}
		}

		return ReadResult(
			rows = rows,
			truncated = truncated,
			warnings = listOfNotNull(warning),
		)
	}

	fun write(rows: List<List<String>>): String = buildString {
		rows.forEachIndexed { index, row ->
			if (index > 0) append("\r\n")
			row.forEachIndexed { column, value ->
				if (column > 0) append(',')
				append(field(value))
			}
		}
	}

	private fun field(value: String): String {
		val needsQuote = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
		if (!needsQuote) return value
		return "\"" + value.replace("\"", "\"\"") + "\""
	}
}