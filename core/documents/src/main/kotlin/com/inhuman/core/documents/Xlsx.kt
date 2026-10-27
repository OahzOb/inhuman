package com.inhuman.core.documents

import org.w3c.dom.Element

/**
 * XLSX: workbook metadata, shared strings, formulas and rows.
 *
 * Cells are read as their displayed values, with a formula kept beside the
 * cached result. A workbook is not a table with a header: sheet boundaries and
 * formulas must survive, or a read looks successful while changing what the
 * numbers mean.
 */
object Xlsx {

	data class ReadResult(
		val document: Document,
		val loss: LossReport,
	)

	fun read(bytes: ByteArray): ReadResult {
		val entries = Ooxml.unzip(bytes)
		val workbookPart = entries["xl/workbook.xml"]
			?: throw DocumentException("The XLSX package has no xl/workbook.xml.")
		val workbook = Ooxml.parse(workbookPart)
		val relationships = relationships(entries["xl/_rels/workbook.xml.rels"])
		val shared = sharedStrings(entries["xl/sharedStrings.xml"])
		val warnings = mutableListOf<String>()
		val sheets = mutableListOf<Sheet>()
		var truncated = false
		var formulaCells = 0

		Ooxml.descendants(workbook.documentElement, "sheet").forEach { sheet ->
			val name = Ooxml.attr(sheet, "name") ?: "Sheet${sheets.size + 1}"
			val relationId = Ooxml.attr(sheet, "id")
			val target = relationId?.let(relationships::get)
			if (target == null) {
				warnings += "unsupported: sheet '$name' has no worksheet relationship."
				return@forEach
			}
			val path = resolve(target)
			val part = entries[path]
			if (part == null) {
				warnings += "unsupported: sheet '$name' is missing its worksheet part."
				return@forEach
			}
			val parsed = sheet(part, shared)
			formulaCells += parsed.formulas
			if (parsed.truncated) truncated = true
			sheets += Sheet(name, parsed.rows)
		}

		if (truncated) warnings += "Only part of the workbook was read (row/cell ceiling)."
		if (formulaCells > 0) {
			warnings += "flattened: $formulaCells formula cells are shown as their cached values."
		}
		if (entries.keys.any { it.startsWith("xl/charts/") || it.startsWith("xl/embeddings/") }) {
			warnings += "unsupported: charts and embedded objects are not represented."
		}
		if ("xl/comments" in entries.keys || entries.keys.any { it.contains("comments") }) {
			warnings += "unsupported: comments are not represented."
		}
		if (entries.keys.any { it.endsWith("vbaProject.bin") }) {
			warnings += "unsupported: VBA macros are not represented and will not run."
		}

		val loss = LossReport(
			kinds = buildSet {
				if (truncated) add(LossKind.Truncated)
				if (warnings.any { it.startsWith("unsupported") }) add(LossKind.UnsupportedFeature)
				if (formulaCells > 0) add(LossKind.Flattened)
			},
			details = warnings,
		)
		return ReadResult(
			Document(
				format = DocFormat.Xlsx,
				sheets = sheets,
				truncated = truncated,
				warnings = warnings,
			),
			loss,
		)
	}

	fun write(sheets: List<Sheet>, template: ByteArray? = null): ByteArray {
		if (sheets.isEmpty()) throw DocumentException("A workbook needs at least one sheet.")
		val entries = if (template == null) LinkedHashMap() else Ooxml.unzip(template)
		val names = uniqueNames(sheets)
		val sheetEntries = sheets.mapIndexed { index, sheet ->
			"xl/worksheets/sheet${index + 1}.xml" to sheetXml(sheet.rows).toByteArray()
		}
		sheetEntries.forEach { (path, data) -> entries[path] = data }
		entries["[Content_Types].xml"] = Ooxml.contentTypes(
			existing = entries["[Content_Types].xml"],
			defaults = mapOf(
				"rels" to "application/vnd.openxmlformats-package.relationships+xml",
				"xml" to "application/xml",
			),
			overrides = buildMap {
				put(
					"/xl/workbook.xml",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
				)
				sheetEntries.indices.forEach { index ->
					put(
						"/xl/worksheets/sheet${index + 1}.xml",
						"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
					)
				}
			},
		)
		if ("_rels/.rels" !in entries) entries["_rels/.rels"] = ROOT_RELS.toByteArray()
		val (rels, ids) = workbookRelationships(
			entries["xl/_rels/workbook.xml.rels"],
			sheetEntries.size,
		)
		entries["xl/_rels/workbook.xml.rels"] = rels.toByteArray()
		entries["xl/workbook.xml"] = workbookXml(names, ids).toByteArray()
		// Old worksheets beyond the new count are left in the package. They are
		// unreferenced; deleting them would also delete a chart they own.
		return Ooxml.zip(entries)
	}

	private data class ParsedSheet(
		val rows: List<List<Cell>>,
		val formulas: Int,
		val truncated: Boolean,
	)

	private fun sheet(bytes: ByteArray, shared: List<String>): ParsedSheet {
		val xml = Ooxml.parse(bytes)
		val data = Ooxml.child(xml.documentElement, "sheetData")
			?: return ParsedSheet(emptyList(), 0, false)
		val rows = ArrayList<List<Cell>>()
		var truncated = false
		var formulas = 0
		Ooxml.children(data).filter { it.localName == "row" }.forEach { row ->
			if (rows.size >= DocumentLimits.MAX_ROWS) {
				truncated = true
				return@forEach
			}
			val cells = ArrayList<Cell>()
			Ooxml.children(row).filter { it.localName == "c" }.forEach { cell ->
				if (cells.size >= DocumentLimits.MAX_COLUMNS) {
					truncated = true
					return@forEach
				}
				val reference = Ooxml.attr(cell, "r")
				val column = if (reference.isNullOrBlank()) cells.size else columnIndex(reference)
				if (column >= DocumentLimits.MAX_COLUMNS) {
					truncated = true
					return@forEach
				}
				while (cells.size < column) cells += Cell()
				val formula = Ooxml.child(cell, "f")?.textContent?.takeIf { it.isNotBlank() }
				if (formula != null) formulas++
				val raw = Ooxml.child(cell, "v")?.textContent
				val inline = Ooxml.child(cell, "is")?.let { Ooxml.text(it) }
				val type = Ooxml.attr(cell, "t")
				val value = when {
					inline != null -> inline
					type == "s" -> raw?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
					type == "b" -> if (raw == "1") "TRUE" else "FALSE"
					else -> raw.orEmpty()
				}
				val numeric = type == null || type == "n" || type == "b"
				cells += Cell(value = value, formula = formula, numeric = numeric)
			}
			if (cells.isNotEmpty()) rows += cells
		}
		return ParsedSheet(rows, formulas, truncated)
	}

	private fun columnIndex(reference: String): Int {
		var column = 0
		var found = false
		for (c in reference) {
			if (c in 'A'..'Z') {
				column = column * 26 + (c - 'A' + 1)
				found = true
			} else if (c in 'a'..'z') {
				column = column * 26 + (c - 'a' + 1)
				found = true
			} else if (found) {
				break
			}
		}
		return (column - 1).coerceAtLeast(0)
	}

	private fun sharedStrings(bytes: ByteArray?): List<String> {
		if (bytes == null) return emptyList()
		val xml = Ooxml.parse(bytes)
		return Ooxml.children(xml.documentElement)
			.filter { it.localName == "si" }
			.map { Ooxml.text(it) }
	}

	private fun relationships(bytes: ByteArray?): Map<String, String> {
		if (bytes == null) return emptyMap()
		val xml = Ooxml.parse(bytes)
		return Ooxml.descendants(xml.documentElement, "Relationship").mapNotNull { relation ->
			val id = Ooxml.attr(relation, "Id") ?: return@mapNotNull null
			val target = Ooxml.attr(relation, "Target") ?: return@mapNotNull null
			id to target
		}.toMap()
	}

	private fun resolve(target: String): String {
		val clean = target.removePrefix("/")
		return if (clean.startsWith("xl/")) clean else "xl/$clean"
	}

	private fun uniqueNames(sheets: List<Sheet>): List<String> {
		val used = mutableSetOf<String>()
		return sheets.mapIndexed { index, sheet ->
			val base = sheet.name.trim().ifBlank { "Sheet${index + 1}" }
				.replace(Regex("[\\\\/?*\\[\\]:]"), "_")
				.take(31)
			var name = base
			var suffix = 2
			// Excel sheet names are case-insensitive; a set that is not would
			// let "Data" and "data" reach the package and make Excel repair it.
			while (!used.add(name.lowercase())) {
				val tail = " ($suffix)"
				name = base.take((31 - tail.length).coerceAtLeast(1)) + tail
				suffix++
			}
			name
		}
	}

	private fun sheetXml(rows: List<List<Cell>>): String = buildString {
		append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
		append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
		val columns = rows.maxOfOrNull { it.size } ?: 0
		if (columns > 0 && rows.isNotEmpty()) {
			append("<dimension ref=\"A1:")
			append(columnName(columns - 1))
			append(rows.size)
			append("\"/>")
		}
		append("<sheetData>")
		rows.forEachIndexed { rowIndex, row ->
			append("<row r=\"")
			append(rowIndex + 1)
			append("\">")
			row.forEachIndexed { columnIndex, cell ->
				append("<c r=\"")
				append(columnName(columnIndex))
				append(rowIndex + 1)
				append("\"")
				val numericValue = cell.value.toDoubleOrNull() != null
				val hasFormula = !cell.formula.isNullOrBlank()
				when {
					hasFormula && !numericValue && cell.value.isNotBlank() -> append(" t=\"str\"")
					!hasFormula && (!cell.numeric || !numericValue) -> append(" t=\"inlineStr\"")
				}
				append(">")
				// Schema order is f, v, is. A formula after an inline string is
				// rejected by strict readers even though it is only a warning in
				// Excel's own parser.
				cell.formula?.takeIf { it.isNotBlank() }?.let {
					append("<f>")
					append(Ooxml.escape(it))
					append("</f>")
				}
				when {
					cell.numeric && numericValue -> {
						append("<v>")
						append(Ooxml.escape(cell.value))
						append("</v>")
					}
					hasFormula -> {
						append("<v>")
						append(Ooxml.escape(cell.value))
						append("</v>")
					}
					else -> {
						append("<is><t xml:space=\"preserve\">")
						append(Ooxml.escape(cell.value))
						append("</t></is>")
					}
				}
				append("</c>")
			}
			append("</row>")
		}
		append("</sheetData></worksheet>")
	}

	private fun columnName(index: Int): String {
		var value = index + 1
		val result = StringBuilder()
		while (value > 0) {
			val remainder = (value - 1) % 26
			result.insert(0, ('A' + remainder))
			value = (value - 1) / 26
		}
		return result.toString()
	}

	private fun workbookXml(names: List<String>, ids: List<String>): String = buildString {
		append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
		append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
		names.forEachIndexed { index, name ->
			append("""<sheet name="${Ooxml.escape(name)}" sheetId="${index + 1}" r:id="${Ooxml.escape(ids[index])}"/>""")
		}
		append("</sheets></workbook>")
	}

	private data class Relationship(
		val id: String,
		val type: String,
		val target: String,
		val targetMode: String?,
	)

	/**
	 * Keep the template's workbook relationships (styles, shared strings, theme)
	 * and replace only the worksheet links, whose targets this writer owns.
	 * Dropping every relationship is how a template loses its number formats
	 * while the workbook still opens.
	 */
	private fun workbookRelationships(existing: ByteArray?, sheetCount: Int): Pair<String, List<String>> {
		val kept = if (existing == null) {
			emptyList()
		} else {
			val xml = Ooxml.parse(existing)
			Ooxml.descendants(xml.documentElement, "Relationship").mapNotNull { relation ->
				val id = Ooxml.attr(relation, "Id") ?: return@mapNotNull null
				val type = Ooxml.attr(relation, "Type") ?: return@mapNotNull null
				val target = Ooxml.attr(relation, "Target") ?: return@mapNotNull null
				Relationship(id, type, target, Ooxml.attr(relation, "TargetMode"))
			}.filterNot { it.type.endsWith("/worksheet") }
		}
		val occupied = kept.map { it.id }.toMutableSet()
		val ids = (1..sheetCount).map { index ->
			var id = "rIdW$index"
			var suffix = 2
			while (!occupied.add(id)) {
				id = "rIdW${index}_${suffix}"
				suffix++
			}
			id
		}
		val xml = buildString {
			append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
			append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
			kept.forEach { relation ->
				append(
					"<Relationship Id=\"${Ooxml.escape(relation.id)}\" " +
						"Type=\"${Ooxml.escape(relation.type)}\" " +
						"Target=\"${Ooxml.escape(relation.target)}\"",
				)
				relation.targetMode?.let {
					append(" TargetMode=\"${Ooxml.escape(it)}\"")
				}
				append("/>")
			}
			ids.forEachIndexed { index, id ->
				append("""<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${index + 1}.xml"/>""")
			}
			append("</Relationships>")
		}
		return xml to ids
	}

	private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
}