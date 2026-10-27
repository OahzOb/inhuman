package com.inhuman.core.documents

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OOXML codecs are judged on round trips and on preserving parts they do
 * not understand. A formatter that quietly drops a comment or a macro is worse
 * than one that refuses the file.
 */
class OoxmlTest {

	@Test
	fun `docx headings, paragraphs, lists and tables round-trip`() {
		val blocks = listOf(
			Block.Heading(1, "Quarterly report & notes"),
			Block.Paragraph("Revenue was <stable>."),
			Block.ListItem(0, "first"),
			Block.ListItem(1, "nested"),
			Block.Table(
				listOf(
					listOf("Quarter", "Revenue"),
					listOf("Q1", "10"),
				),
			),
		)

		val bytes = Docx.write(blocks)
		val read = Docx.read(bytes)

		assertEquals(DocFormat.Docx, read.document.format)
		assertEquals(Block.Heading(1, "Quarterly report & notes"), read.document.blocks[0])
		assertEquals(Block.Paragraph("Revenue was <stable>."), read.document.blocks[1])
		assertEquals(Block.ListItem(0, "first"), read.document.blocks[2])
		assertEquals(Block.ListItem(1, "nested"), read.document.blocks[3])
		assertEquals(
			Block.Table(listOf(listOf("Quarter", "Revenue"), listOf("Q1", "10"))),
			read.document.blocks[4],
		)
		assertTrue(read.loss.complete)
		val entries = Ooxml.unzip(bytes)
		val rels = String(entries["word/_rels/document.xml.rels"]!!)
		assertTrue(rels, rels.contains("styles.xml"))
		assertTrue(rels, rels.contains("numbering.xml"))
	}

	@Test
	fun `docx write preserves a package part it does not understand`() {
		val template = Ooxml.zip(
			mapOf(
				"word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>old</w:t></w:r></w:p></w:body></w:document>""".toByteArray(),
				"word/comments.xml" to "<comments>keep me</comments>".toByteArray(),
				"custom/extra.bin" to byteArrayOf(1, 2, 3),
			),
		)

		val bytes = Docx.write(listOf(Block.Paragraph("new")), template)
		val entries = Ooxml.unzip(bytes)

		assertArrayEquals(byteArrayOf(1, 2, 3), entries["custom/extra.bin"])
		assertTrue(String(entries["word/comments.xml"]!!).contains("keep me"))
		assertEquals("new", Ooxml.text(Ooxml.parse(entries["word/document.xml"]!!).documentElement))
	}

	@Test
	fun `xlsx sheets and cells round-trip including formulas and quotes`() {
		val sheets = listOf(
			Sheet(
				name = "Revenue",
				rows = listOf(
					listOf(Cell("Quarter"), Cell("Amount")),
					listOf(Cell("Q1"), Cell(value = "10", numeric = true)),
					listOf(Cell("Total"), Cell(formula = "SUM(B2:B2)", value = "10", numeric = true)),
					listOf(Cell("say \"hi\""), Cell(value = "true", numeric = false)),
				),
			),
			Sheet(name = "Notes", rows = listOf(listOf(Cell("hello")))),
		)

		val bytes = Xlsx.write(sheets)
		val read = Xlsx.read(bytes)

		assertEquals(2, read.document.sheets.size)
		assertEquals(listOf("Revenue", "Notes"), read.document.sheets.map { it.name })
		val revenue = read.document.sheets[0]
		assertEquals("Q1", revenue.rows[1][0].value)
		assertEquals("10", revenue.rows[1][1].value)
		assertEquals("SUM(B2:B2)", revenue.rows[2][1].formula)
		assertTrue(revenue.rows[2][1].numeric)
		assertEquals("say \"hi\"", revenue.rows[3][0].value)
		assertTrue(read.loss.kinds.contains(LossKind.Flattened))
	}

	@Test
	fun `xlsx preserves unrelated package parts`() {
		val template = Ooxml.zip(
			mapOf(
				"xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Old" sheetId="1" r:id="rId1"/></sheets></workbook>""".toByteArray(),
				"xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>""".toByteArray(),
				"xl/charts/chart1.xml" to "<chart/>".toByteArray(),
			),
		)

		val bytes = Xlsx.write(listOf(Sheet("New", listOf(listOf(Cell("x"))))), template)
		val entries = Ooxml.unzip(bytes)

		assertEquals("<chart/>", String(entries["xl/charts/chart1.xml"]!!))
		assertEquals("New", Xlsx.read(bytes).document.sheets.single().name)
	}

	@Test
	fun `a template keeps its content types and root relationships`() {
		val contentTypes =
			"""<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
				"""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
				"""<Default Extension="xml" ContentType="application/xml"/>""" +
				"""<Override PartName="/word/custom.xml" ContentType="application/xml"/>""" +
				"""</Types>"""
		val rootRels =
			"""<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
				"""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
				"""<Relationship Id="rId2" Type="http://example.test/custom" Target="custom.xml"/>""" +
				"""</Relationships>"""
		val template = Ooxml.zip(
			mapOf(
				"word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>""".toByteArray(),
				"[Content_Types].xml" to contentTypes.toByteArray(),
				"_rels/.rels" to rootRels.toByteArray(),
				"word/custom.xml" to "<custom/>".toByteArray(),
			),
		)

		val entries = Ooxml.unzip(Docx.write(listOf(Block.Paragraph("new")), template))

		val types = String(entries["[Content_Types].xml"]!!)
		assertTrue(types.contains("/word/custom.xml"))
		assertTrue(types.contains("/word/document.xml"))
		val rels = String(entries["_rels/.rels"]!!)
		assertTrue(rels.contains("http://example.test/custom"))
		assertEquals("<custom/>", String(entries["word/custom.xml"]!!))
	}

	@Test
	fun `xlsx preserves a template styles relationship`() {
		val template = Ooxml.zip(
			mapOf(
				"xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Old" sheetId="1" r:id="rId1"/></sheets></workbook>""".toByteArray(),
				"xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""".toByteArray(),
				"xl/styles.xml" to "<styleSheet/>".toByteArray(),
			),
		)

		val entries = Ooxml.unzip(Xlsx.write(listOf(Sheet("New", listOf(listOf(Cell("x"))))), template))

		val rels = String(entries["xl/_rels/workbook.xml.rels"]!!)
		assertTrue(rels, rels.contains("styles.xml"))
		assertTrue(rels, rels.contains("worksheet"))
		assertEquals("<styleSheet/>", String(entries["xl/styles.xml"]!!))
	}

	@Test
	fun `a malformed package or xml is a refusal, not a crash`() {
		val zip = runCatching { Ooxml.unzip("not a zip".toByteArray()) }
		assertTrue(zip.isFailure)
		assertTrue(zip.exceptionOrNull() is DocumentException)

		val badDocx = Ooxml.zip(
			mapOf("word/document.xml" to "<w:document".toByteArray()),
		)
		val parsed = runCatching { Docx.read(badDocx) }
		assertTrue(parsed.isFailure)
		assertTrue(parsed.exceptionOrNull() is DocumentException)
	}

	@Test
	fun `a docx with a macro reports the loss instead of claiming success`() {
		val template = Ooxml.zip(
			mapOf(
				"word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>body</w:t></w:r></w:p></w:body></w:document>""".toByteArray(),
				"word/vbaProject.bin" to byteArrayOf(1),
			),
		)

		val read = Docx.read(template)

		assertFalse(read.loss.complete)
		assertTrue(read.loss.kinds.contains(LossKind.UnsupportedFeature))
		assertTrue(read.document.warnings.any { it.contains("macro", ignoreCase = true) })
	}

	@Test
	fun `xlsx uses shared strings from a real-shaped workbook`() {
		val template = Ooxml.zip(
			mapOf(
				"xl/workbook.xml" to """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""".toByteArray(),
				"xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""".toByteArray(),
				"xl/sharedStrings.xml" to """<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>hello</t></si><si><r><t>world</t></r></si></sst>""".toByteArray(),
				"xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row></sheetData></worksheet>""".toByteArray(),
			),
		)

		val read = Xlsx.read(template)

		assertEquals(listOf("hello", "world"), read.document.sheets.single().rows.single().map { it.value })
	}
}