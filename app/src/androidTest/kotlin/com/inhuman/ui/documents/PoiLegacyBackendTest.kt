package com.inhuman.ui.documents

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inhuman.core.documents.Cell
import com.inhuman.core.documents.Sheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * POI on Android, not only on the JVM. XLS is the writable legacy format;
 * DOC writing is deliberately refused because it needs a Word 97 template and
 * a wrong `.doc` would not open in Word.
 */
@RunWith(AndroidJUnit4::class)
class PoiLegacyBackendTest {

	private val backend = PoiLegacyBackend()

	@Test
	fun xlsRoundTripsCells() {
		val bytes = backend.writeXls(
			listOf(
				Sheet(
					"Report",
					listOf(
						listOf(Cell("Name"), Cell("Amount")),
						listOf(Cell("Q1"), Cell(value = "12.5", numeric = true)),
					),
				),
			),
		)

		val sheets = backend.readXls(bytes)

		assertEquals("Report", sheets.single().name)
		assertEquals("Name", sheets.single().rows[0][0].value)
		assertEquals("Q1", sheets.single().rows[1][0].value)
		assertEquals("12.5", sheets.single().rows[1][1].value)
	}

	@Test
	fun docWritingIsRefusedInsteadOfWritingAFakeDoc() {
		assertThrows(UnsupportedOperationException::class.java) {
			backend.writeDoc(emptyList())
		}
	}
}