package com.inhuman.core.documents

import org.w3c.dom.Element

/**
 * DOCX: paragraphs, heading styles, list items and tables.
 *
 * The reader does not try to reproduce layout. It extracts what the model can
 * act on and records the features it cannot represent (images, comments,
 * fields, macros) so the caller can say what was lost instead of presenting a
 * clean-looking but incomplete document.
 */
object Docx {

	data class ReadResult(
		val document: Document,
		val loss: LossReport,
	)

	fun read(bytes: ByteArray): ReadResult {
		val entries = Ooxml.unzip(bytes)
		val documentPart = entries["word/document.xml"]
			?: throw DocumentException("The DOCX package has no word/document.xml.")
		val xml = Ooxml.parse(documentPart)
		val body = Ooxml.child(xml.documentElement, "body")
			?: throw DocumentException("The DOCX document has no body.")

		val blocks = ArrayList<Block>()
		var truncated = false
		val warnings = featureWarnings(entries, documentPart, "docx").toMutableList()

		fun add(block: Block) {
			if (blocks.size >= DocumentLimits.MAX_PARAGRAPHS) {
				truncated = true
				return
			}
			blocks += block
		}

		Ooxml.children(body).forEach { element ->
			when (element.localName) {
				"p" -> paragraph(element)?.let(::add)
				"tbl" -> add(Block.Table(table(element)))
			}
		}

		if (truncated) warnings += "Only the first ${DocumentLimits.MAX_PARAGRAPHS} paragraphs were read."
		val loss = LossReport(
			kinds = buildSet {
				if (truncated) add(LossKind.Truncated)
				if (warnings.any { it.startsWith("unsupported") }) add(LossKind.UnsupportedFeature)
			},
			details = warnings.filter { it.startsWith("unsupported") },
		)
		return ReadResult(
			Document(
				format = DocFormat.Docx,
				blocks = blocks,
				truncated = truncated,
				warnings = warnings,
			),
			loss,
		)
	}

	/**
	 * Create a document, or replace the body of [template] while copying every
	 * other package part unchanged.
	 */
	fun write(blocks: List<Block>, template: ByteArray? = null): ByteArray {
		val entries = if (template == null) {
			LinkedHashMap()
		} else {
			Ooxml.unzip(template)
		}
		entries["[Content_Types].xml"] = CONTENT_TYPES.toByteArray()
		entries["_rels/.rels"] = ROOT_RELS.toByteArray()
		entries["word/document.xml"] = documentXml(blocks).toByteArray()
		if ("word/styles.xml" !in entries) entries["word/styles.xml"] = STYLES.toByteArray()
		if ("word/numbering.xml" !in entries) entries["word/numbering.xml"] = NUMBERING.toByteArray()
		return Ooxml.zip(entries)
	}

	private fun paragraph(element: Element): Block? {
		val text = Ooxml.text(element)
		if (text.isBlank()) return null
		val properties = Ooxml.child(element, "pPr")
		val style = properties?.let { Ooxml.child(it, "pStyle") }
			?.let { Ooxml.attr(it, "val") }
		val level = headingLevel(style)
		if (level != null) return Block.Heading(level, text)
		if (properties?.let { Ooxml.child(it, "numPr") } != null) {
			val listLevel = Ooxml.child(properties, "numPr")
				?.let { Ooxml.child(it, "ilvl") }
				?.let { Ooxml.attr(it, "val")?.toIntOrNull() } ?: 0
			return Block.ListItem(listLevel, text)
		}
		return Block.Paragraph(text)
	}

	private fun headingLevel(style: String?): Int? {
		if (style == null) return null
		val normalized = style.lowercase()
		if (!normalized.contains("heading") && !normalized.contains("标题")) return null
		val digits = style.filter(Char::isDigit)
		return (digits.toIntOrNull() ?: 1).coerceIn(1, 6)
	}

	private fun table(element: Element): List<List<String>> =
		Ooxml.descendants(element, "tr").map { row ->
			Ooxml.children(row)
				.filter { it.localName == "tc" }
				.map { cell -> Ooxml.text(cell) }
		}

	private fun featureWarnings(
		entries: Map<String, ByteArray>,
		documentPart: ByteArray,
		format: String,
	): List<String> {
		val warnings = mutableListOf<String>()
		val xml = String(documentPart, Charsets.UTF_8)
		if ("vbaProject.bin" in entries.keys || entries.keys.any { it.endsWith("vbaProject.bin") }) {
			warnings += "unsupported: VBA macros are not represented and will not run."
		}
		if (entries.keys.any { it.startsWith("word/media/") }) {
			warnings += "unsupported: images are not represented in the text model."
		}
		if ("word/comments.xml" in entries || "comments" in xml) {
			warnings += "unsupported: comments are not represented."
		}
		if ("footnoteReference" in xml || "endnoteReference" in xml) {
			warnings += "unsupported: footnotes and endnotes are not represented."
		}
		if ("w:drawing" in xml || "<w:drawing" in xml) {
			warnings += "unsupported: drawings and shapes are not represented."
		}
		if (entries.keys.any { it.startsWith("word/charts/") || it.startsWith("word/embeddings/") }) {
			warnings += "unsupported: charts and embedded objects are not represented."
		}
		if (format == "docx" && entries.keys.none { it == "[Content_Types].xml" }) {
			warnings += "unsupported: the package has no content-types part."
		}
		return warnings
	}

	private fun documentXml(blocks: List<Block>): String = buildString {
		append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
		append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
		append("<w:body>")
		blocks.forEach { block ->
			when (block) {
				is Block.Heading -> {
					append("<w:p><w:pPr><w:pStyle w:val=\"Heading")
					append(block.level.coerceIn(1, 6))
					append("\"/></w:pPr><w:r><w:t xml:space=\"preserve\">")
					append(Ooxml.escape(block.text))
					append("</w:t></w:r></w:p>")
				}
				is Block.Paragraph -> append(paragraphXml(block.text, list = false))
				is Block.ListItem -> append(paragraphXml(block.text, list = true, level = block.level))
				is Block.Table -> append(tableXml(block.rows))
			}
		}
		append("<w:sectPr/></w:body></w:document>")
	}

	private fun paragraphXml(text: String, list: Boolean, level: Int = 0): String = buildString {
		append("<w:p>")
		if (list) {
			append("<w:pPr><w:numPr><w:ilvl w:val=\"")
			append(level.coerceIn(0, 8))
			append("\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>")
		}
		append("<w:r><w:t xml:space=\"preserve\">")
		append(Ooxml.escape(text))
		append("</w:t></w:r></w:p>")
	}

	private fun tableXml(rows: List<List<String>>): String = buildString {
		append("<w:tbl>")
		val columns = rows.maxOfOrNull { it.size } ?: 0
		if (columns > 0) {
			append("<w:tblGrid>")
			repeat(columns) { append("<w:gridCol/>") }
			append("</w:tblGrid>")
		}
		rows.forEach { row ->
			append("<w:tr>")
			repeat(columns) { index ->
				append("<w:tc><w:p><w:r><w:t xml:space=\"preserve\">")
				append(Ooxml.escape(row.getOrElse(index) { "" }))
				append("</w:t></w:r></w:p></w:tc>")
			}
			append("</w:tr>")
		}
		append("</w:tbl>")
	}

	private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
</Types>"""

	private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

	private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="1"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="2"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading4"><w:name w:val="heading 4"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="3"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading5"><w:name w:val="heading 5"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="4"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading6"><w:name w:val="heading 6"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="5"/></w:pPr></w:style>
</w:styles>"""

	private const val NUMBERING = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val=""/></w:lvl></w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
</w:numbering>"""
}