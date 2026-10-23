package com.inhuman.desktop

import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.tools.FileTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Executes the real file tools on the JVM, no Android runtime involved. */
class FileToolSmokeTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `write_file and read_file work on the desktop`() = runBlocking {
		val ws = SessionWorkspace.open(tmp.newFolder("ws"))
		val tools = FileTools.all(ws)
		val write = tools.first { it.name == "write_file" }
		val read = tools.first { it.name == "read_file" }

		val wrote = write.execute(
			buildJsonObject {
				put("path", "notes/todo.txt")
				put("content", "shared core\n")
			},
		)
		assertFalse(wrote.isError)

		val result = read.execute(buildJsonObject { put("path", "notes/todo.txt") })
		assertFalse(result.isError)
		assertTrue(result.content, result.content.contains("shared core"))
	}
}
