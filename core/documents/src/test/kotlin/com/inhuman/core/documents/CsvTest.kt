package com.inhuman.core.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV is where "looks fine" is most dangerous: a quoted comma, a newline in a
 * cell and a trailing Excel newline are each one character away from corrupting
 * a report. The parser is pinned against those, not just against a two-column
 * happy path.
 */
class CsvTest {

	@Test
	fun `quoted commas, quotes and newlines stay in one field`() {
		val result = Csv.read(
			"name,note\r\n" +
				"\"Smith, John\",\"said \"\"hello\"\"\nnew line\"\r\n",
		)

		assertEquals(2, result.rows.size)
		assertEquals(listOf("Smith, John", "said \"hello\"\nnew line"), result.rows[1])
		assertFalse(result.truncated)
	}

	@Test
	fun `a final newline does not create an extra row`() {
		assertEquals(2, Csv.read("a,b\n1,2\n").rows.size)
		assertEquals(2, Csv.read("a,b\r\n1,2\r\n").rows.size)
	}

	@Test
	fun `a BOM from Excel is removed`() {
		assertEquals("id", Csv.read("\uFEFFid,name\n1,x").rows.first().first())
	}

	@Test
	fun `an empty quoted field is a field, not a missing cell`() {
		assertEquals(listOf("a", "", "c"), Csv.read("a,\"\",c").rows.single())
	}

	@Test
	fun `unbalanced quotes are reported and do not crash`() {
		val result = Csv.read("a,\"unfinished\nline")

		assertTrue(result.warnings.isNotEmpty())
		// The bytes read so far are still returned: silently dropping them
		// would be a worse answer than showing them with a warning.
		assertEquals("a", result.rows.single().first())
	}

	@Test
	fun `row and column ceilings are reported as truncated`() {
		val rows = Csv.read("1,2,3\n4,5,6\n7,8,9", maxRows = 2)
		assertTrue(rows.truncated)
		assertEquals(2, rows.rows.size)

		val columns = Csv.read("1,2,3", maxColumns = 2)
		assertTrue(columns.truncated)
		assertEquals(listOf("1", "2"), columns.rows.single())
	}

	@Test
	fun `writing quotes only the dangerous fields and round-trips`() {
		val rows = listOf(
			listOf("plain", "with,comma", "with\"quote", "with\nnewline"),
			listOf("", " ", "x"),
		)
		val text = Csv.write(rows)

		assertTrue(text, text.contains("\"with,comma\""))
		assertTrue(text, text.contains("\"with\"\"quote\""))
		assertEquals(rows, Csv.read(text).rows)
	}

	@Test
	fun `a document reports its format and plain text`() {
		val doc = Document(
			format = DocFormat.Csv,
			blocks = listOf(
				Block.Heading(2, "Report"),
				Block.Paragraph("hello"),
				Block.ListItem(0, "one"),
				Block.Table(listOf(listOf("a", "b"))),
			),
		)

		assertFalse(doc.isEmpty)
		assertEquals("## Report\n\nhello\n\n- one\n\na | b", doc.plainText())
	}

	@Test
	fun `an empty document is empty even with metadata`() {
		assertTrue(Document(format = DocFormat.Csv).isEmpty)
	}
}