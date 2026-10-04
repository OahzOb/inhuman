package com.inhuman.core.files.tools

import com.inhuman.core.files.SessionWorkspace
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tools may be replayed, and why the answer is a property of the tool.
 *
 * ## Why this is tested rather than reviewed
 *
 * Regenerating a reply re-runs the turn, tools included. A tool marked as
 * read-only when it is not means the regenerate control stays enabled and the
 * write happens a second time -- silently, on a workspace that already has the
 * first one's changes. Nothing about that is visible in a diff or caught by a
 * compiler, so the classification is asserted here.
 *
 * The set of tools is closed today. The test fails when a ninth is added
 * without an answer, which is the point: the omission is loud rather than
 * inherited from a default.
 */
class ToolMutationClassificationTest {

	private fun workspace(): SessionWorkspace {
		val dir = Files.createTempDirectory("tool-mutates").toFile()
		return SessionWorkspace.open(dir)
	}

	private fun tools(): Map<String, com.inhuman.core.ai.Tool> =
		FileTools.all(workspace(), freeSpaceBytes = { 1_000_000_000L })
			.associateBy { it.name }

	@Test
	fun `every tool is classified`() {
		val tools = tools()

		// Named explicitly so adding a tool without deciding fails here.
		assertEquals(
			setOf(
				"list_files", "read_file", "file_info",
				"write_file", "append_file", "create_directory", "move_file", "delete_file",
			),
			tools.keys,
		)
	}

	@Test
	fun `the read-only tools say so`() {
		val tools = tools()

		// Replaying these costs nothing but tokens.
		assertFalse("list_files", tools.getValue("list_files").mutates)
		assertFalse("read_file", tools.getValue("read_file").mutates)
		assertFalse("file_info", tools.getValue("file_info").mutates)
	}

	@Test
	fun `everything that touches the filesystem says so`() {
		val tools = tools()

		// Each of these changes the workspace, so a turn that used one cannot be
		// replayed -- a second run writes, moves or deletes again.
		listOf(
			"write_file",
			"append_file",
			"create_directory",
			"move_file",
			"delete_file",
		).forEach { name ->
			assertTrue("$name must be marked as mutating", tools.getValue(name).mutates)
		}
	}

	@Test
	fun `a read tool actually leaves nothing behind`() {
		// The classification is a claim; this checks it against the filesystem
		// for one read tool, so the flag is not merely an assertion about a
		// comment.
		val ws = workspace()
		val before = ws.fileCount()
		val tool = FileTools.all(ws, freeSpaceBytes = { 1_000_000_000L })
			.first { it.name == "list_files" }

		kotlinx.coroutines.runBlocking {
			tool.execute(kotlinx.serialization.json.buildJsonObject { })
		}

		assertEquals(before, ws.fileCount())
	}

	@Test
	fun `a writing tool actually changes the workspace`() {
		// And the converse, for the flag that guards regeneration.
		val ws = workspace()
		val before = ws.fileCount()
		val tool = FileTools.all(ws, freeSpaceBytes = { 1_000_000_000L })
			.first { it.name == "write_file" }

		kotlinx.coroutines.runBlocking {
			tool.execute(
				kotlinx.serialization.json.buildJsonObject {
					put("path", kotlinx.serialization.json.JsonPrimitive("proof.txt"))
					put("content", kotlinx.serialization.json.JsonPrimitive("written"))
				},
			)
		}

		assertTrue("write_file must have created something", ws.fileCount() > before)
	}
}
