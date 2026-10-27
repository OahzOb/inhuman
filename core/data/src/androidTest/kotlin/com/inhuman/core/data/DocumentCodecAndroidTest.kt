package com.inhuman.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inhuman.core.documents.Block
import com.inhuman.core.documents.Cell
import com.inhuman.core.documents.Docx
import com.inhuman.core.documents.Sheet
import com.inhuman.core.documents.Xlsx
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The OOXML codecs run on the JVM in `:core:documents`, but Android's DOM
 * implementation is not the JVM's. This is the check that the zip/XML path
 * actually works on the platform the app ships on; a JVM-only test would have
 * missed a missing parser feature or a namespace difference.
 */
@RunWith(AndroidJUnit4::class)
class DocumentCodecAndroidTest {

	@Test
	fun docxRoundTripsOnAndroid() {
		val bytes = Docx.write(
			listOf(
				Block.Heading(1, "Title"),
				Block.Paragraph("Body"),
				Block.Table(listOf(listOf("a", "b"))),
			),
		)
		val read = Docx.read(bytes)

		assertEquals("Title", (read.document.blocks[0] as Block.Heading).text)
		assertEquals("Body", (read.document.blocks[1] as Block.Paragraph).text)
	}

	@Test
	fun xlsxRoundTripsOnAndroid() {
		val bytes = Xlsx.write(
			listOf(
				Sheet(
					"Sheet1",
					listOf(
						listOf(Cell("A"), Cell("B")),
						listOf(Cell("1", numeric = true), Cell("2", numeric = true)),
					),
				),
			),
		)
		val read = Xlsx.read(bytes)

		assertEquals(2, read.document.sheets.single().rows.size)
		assertEquals("A", read.document.sheets.single().rows[0][0].value)
	}
}