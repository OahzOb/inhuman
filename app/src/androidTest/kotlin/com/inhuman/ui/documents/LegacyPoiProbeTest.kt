package com.inhuman.ui.documents

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inhuman.core.documents.Block
import com.inhuman.core.documents.Cell
import com.inhuman.core.documents.Sheet
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Does Apache POI's legacy BIFF8 path actually run on Android?
 *
 * The decision doc says the legacy adapter must be tested on the device before
 * DOC/XLS is claimed; POI references AWT classes that Android does not have.
 * This probe creates and re-reads a workbook in memory. If it fails, the legacy
 * adapter stays a refusal rather than a broken format.
 */
@RunWith(AndroidJUnit4::class)
class LegacyPoiProbeTest {

	@Test
	fun biff8RoundTrips() {
		val workbook = HSSFWorkbook()
		val sheet = workbook.createSheet("S")
		sheet.createRow(0).createCell(0).setCellValue("hello")
		val out = ByteArrayOutputStream()
		workbook.write(out)
		workbook.close()

		val read = HSSFWorkbook(ByteArrayInputStream(out.toByteArray()))
		try {
			assertEquals("hello", read.getSheetAt(0).getRow(0).getCell(0).stringCellValue)
		} finally {
			read.close()
		}
	}

	@Test
	fun theLegacyAdapterReadsAndWritesXls() {
		val backend = PoiLegacyBackend()
		val bytes = backend.writeXls(
			listOf(
				Sheet(
					"Report",
					listOf(
						listOf(Cell("name"), Cell("amount")),
						listOf(Cell("Q1"), Cell("10", numeric = true)),
					),
				),
			),
		)

		val sheets = backend.readXls(bytes)

		assertEquals("Report", sheets.single().name)
		assertEquals("Q1", sheets.single().rows[1][0].value)
		assertEquals("10", sheets.single().rows[1][1].value)
	}

	@Test
	fun docWritingIsARefusalUntilATemplateExists() {
		val failure = runCatching {
			PoiLegacyBackend().writeDoc(listOf(Block.Paragraph("hello")))
		}
		assertTrue(failure.exceptionOrNull() is UnsupportedOperationException)
	}
}