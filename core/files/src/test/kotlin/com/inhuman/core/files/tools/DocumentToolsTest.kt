package com.inhuman.core.files.tools

import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.documents.DocumentBackends
import com.inhuman.core.documents.Page
import com.inhuman.core.documents.PdfBackend
import com.inhuman.core.documents.PdfRead
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The document tools are workspace-bound: they write through the same quota and
 * free-space path as `write_file`, and they refuse a format they cannot read
 * instead of returning an empty document.
 */
class DocumentToolsTest {

	private fun workspace(): SessionWorkspace {
		val dir = Files.createTempDirectory("document-tools").toFile()
		return SessionWorkspace.open(dir)
	}

	private fun tools(ws: SessionWorkspace) = FileTools.all(ws).associateBy { it.name }

	private fun SessionWorkspace.file(path: String): java.io.File =
		(resolve(path) as SessionWorkspace.Resolution.Ok).file

	@Test
	fun `write then read a docx and convert it to csv`() = runBlocking {
		val ws = workspace()
		val tools = tools(ws)

		val written = tools.getValue("write_document").execute(
			buildJsonObject {
				put("path", "report.docx")
				put("title", "Report")
				put("paragraphs", buildJsonArray { add(JsonPrimitive("hello")) })
				put(
					"tables",
					buildJsonArray {
						add(
							buildJsonArray {
								add(buildJsonArray { add(JsonPrimitive("A")); add(JsonPrimitive("B")) })
								add(buildJsonArray { add(JsonPrimitive("1")); add(JsonPrimitive("2")) })
							},
						)
					},
				)
			},
		)
		assertFalse(written.isError)

		val read = tools.getValue("read_document").execute(
			buildJsonObject { put("path", "report.docx") },
		)
		assertFalse(read.content, read.isError)
		assertTrue(read.content.contains("hello"))
		assertTrue(read.content.contains("A | B"))

		val converted = tools.getValue("convert_document").execute(
			buildJsonObject {
				put("source", "report.docx")
				put("target", "report.csv")
			},
		)
		assertFalse(converted.content, converted.isError)
		assertTrue(converted.content, converted.content.contains("converted"))
		assertTrue(ws.file("report.csv").readText().contains("A,B"))
	}

	@Test
	fun `write and read a csv with quoted commas`() = runBlocking {
		val ws = workspace()
		val tools = tools(ws)

		tools.getValue("write_document").execute(
			buildJsonObject {
				put("path", "list.csv")
				put(
					"tables",
					buildJsonArray {
						add(
							buildJsonArray {
								add(buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("note")) })
								add(
									buildJsonArray {
										add(JsonPrimitive("Smith, John"))
										add(JsonPrimitive("said \"hi\""))
									},
								)
							},
						)
					},
				)
			},
		)

		val read = tools.getValue("read_document").execute(
			buildJsonObject { put("path", "list.csv") },
		)
		assertFalse(read.content, read.isError)
		assertTrue(read.content.contains("Smith, John"))
		assertTrue(read.content.contains("said"))
	}

	@Test
	fun `pdf read uses the host backend and honours a page range`() = runBlocking {
		val ws = workspace()
		ws.file("scan.pdf").writeBytes(byteArrayOf(1, 2, 3))
		val backend = object : PdfBackend {
			var requested: IntRange? = null
			override fun read(bytes: ByteArray, pages: IntRange?): PdfRead {
				requested = pages
				return PdfRead(listOf(Page(2, "page two")), listOf("page 1 has no text layer"))
			}
			override fun write(pages: List<String>): ByteArray =
				pages.joinToString("|").toByteArray()
		}
		val tools = DocumentTools.all(ws, DocumentBackends(pdf = backend)).associateBy { it.name }

		val read = tools.getValue("read_document").execute(
			buildJsonObject {
				put("path", "scan.pdf")
				put("pages", "2-3")
			},
		)
		assertFalse(read.content, read.isError)
		assertEquals(2..3, backend.requested)
		assertTrue(read.content.contains("page two"))
		assertTrue(read.content.contains("no text layer"))

		val written = tools.getValue("write_document").execute(
			buildJsonObject {
				put("path", "made.pdf")
				put("paragraphs", buildJsonArray { add(JsonPrimitive("hello")) })
			},
		)
		assertFalse(written.content, written.isError)
		assertEquals("hello", ws.file("made.pdf").readText())
	}

	@Test
	fun `a pdf is refused when the host has no backend`() = runBlocking {
		val ws = workspace()
		ws.file("scan.pdf").writeBytes(byteArrayOf(1, 2, 3))
		val read = tools(ws).getValue("read_document").execute(
			buildJsonObject { put("path", "scan.pdf") },
		)
		assertTrue(read.isError)
		assertTrue(read.content, read.content.contains("PDF"))
	}

	@Test
	fun `an unsupported document is refused with the reason`() = runBlocking {
		val ws = workspace()
		ws.file("scan.pdf").writeBytes(byteArrayOf(1, 2, 3))
		val read = tools(ws).getValue("read_document").execute(
			buildJsonObject { put("path", "scan.pdf") },
		)

		assertTrue(read.isError)
		assertTrue(read.content, read.content.contains("PDF"))
	}

	@Test
	fun `a traversal path is refused before a file is read`() = runBlocking {
		val read = tools(workspace()).getValue("read_document").execute(
			buildJsonObject { put("path", "../outside.docx") },
		)
		assertTrue(read.isError)
	}

	@Test
	fun `an oversized document is refused before its bytes are read`() = runBlocking {
		val ws = workspace()
		val file = ws.file("huge.docx")
		java.io.RandomAccessFile(file, "rw").use { it.setLength(5L * 1024 * 1024) }
		val read = tools(ws).getValue("read_document").execute(
			buildJsonObject { put("path", "huge.docx") },
		)

		assertTrue(read.isError)
		assertTrue(read.content, read.content.contains("document limit"))
	}

	@Test
	fun `the schema exposes the write fields`() {
		val write = tools(workspace()).getValue("write_document")
		val properties = (write.parameters["properties"] as Map<*, *>).keys
		assertTrue(properties.containsAll(setOf("path", "title", "paragraphs", "tables", "sheets")))
	}
}