package com.inhuman.ui.documents

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.documents.Block
import com.inhuman.core.documents.Cell
import com.inhuman.core.documents.Sheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * POI on Android, not only on the JVM. XLS reads and writes; DOC writes into a
 * bundled blank Word 97 template and reads back.
 */
@RunWith(AndroidJUnit4::class)
class PoiLegacyBackendTest {

	private val backend = PoiLegacyBackend(
		InstrumentationRegistry.getInstrumentation().targetContext,
	)

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
	fun theBundledTemplateStartsEmpty() {
		val template = InstrumentationRegistry.getInstrumentation()
			.targetContext.assets.open("blank.doc").use { it.readBytes() }

		assertEquals(emptyList<Block>(), backend.readDoc(template))
	}

	@Test
	fun docWritesThroughTheTemplate() {
		val bytes = backend.writeDoc(listOf(Block.Paragraph("hello")))
		val text = backend.readDoc(bytes).joinToString(" ") { block ->
			when (block) {
				is Block.Heading -> block.text
				is Block.Paragraph -> block.text
				is Block.ListItem -> block.text
				is Block.Table -> block.rows.joinToString(" ")
			}
		}
		assertTrue(text, text.contains("hello"))
	}
}