package com.inhuman.ui.chat

import com.inhuman.core.files.SessionWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `AGENTS.md` is the one workspace file that enters the prompt without a tool
 * call, so the reader's refusals matter more than its successes: a symlink, a
 * binary file, a 16 KiB single line or a missing file must leave the turn
 * running with no workspace block rather than fail it.
 */
class WorkspaceInstructionsTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var workspace: SessionWorkspace

	@Before
	fun setUp() {
		workspace = SessionWorkspace.open(tmp.newFolder("ws"))
	}

	private fun write(relative: String, text: String) {
		val file = (workspace.resolve(relative) as SessionWorkspace.Resolution.Ok).file
		file.parentFile?.mkdirs()
		file.writeText(text, Charsets.UTF_8)
	}

	private fun file(relative: String) =
		(workspace.resolve(relative) as SessionWorkspace.Resolution.Ok).file

	@Test
	fun `the root file is read`() {
		write(WORKSPACE_INSTRUCTIONS_FILE, "# Rules\n\nBe brief.\n")
		val info = readWorkspaceInstructions(workspace)
		assertNotNull(info)
		assertEquals("# Rules\n\nBe brief.", info!!.body)
		assertFalse(info.truncated)
	}

	@Test
	fun `a missing file is ignored`() {
		assertNull(readWorkspaceInstructions(workspace))
	}

	@Test
	fun `a nested AGENTS dot md is not the workspace instruction`() {
		write("docs/AGENTS.md", "# Nested")
		assertNull(readWorkspaceInstructions(workspace))
	}

	@Test
	fun `a blank file is ignored`() {
		write(WORKSPACE_INSTRUCTIONS_FILE, " \n\t\r\n")
		assertNull(readWorkspaceInstructions(workspace))
	}

	@Test
	fun `a BOM and CRLF are normalized`() {
		write(WORKSPACE_INSTRUCTIONS_FILE, "\uFEFF# A\r\nB\r\n")
		assertEquals("# A\nB", readWorkspaceInstructions(workspace)?.body)
	}

	@Test
	fun `a long file is cut at a line boundary`() {
		val line = "x".repeat(100)
		write(WORKSPACE_INSTRUCTIONS_FILE, (1..300).joinToString("\n") { line })
		val info = readWorkspaceInstructions(workspace)
		assertNotNull(info)
		assertTrue(info!!.truncated)
		assertTrue(info.bytes <= MAX_WORKSPACE_INSTRUCTIONS_BYTES)
		assertTrue(info.body.lines().all { it.length <= line.length })
		assertTrue(
			workspaceInstructionsBlock(info).contains(
				"[AGENTS.md was truncated at 16 KiB; use read_file to read the rest.]",
			),
		)
	}

	@Test
	fun `a single overlong line is refused`() {
		write(WORKSPACE_INSTRUCTIONS_FILE, "x".repeat(MAX_WORKSPACE_INSTRUCTIONS_BYTES + 10))
		assertNull(readWorkspaceInstructions(workspace))
	}

	@Test
	fun `a NUL byte makes it binary`() {
		file(WORKSPACE_INSTRUCTIONS_FILE)
			.writeBytes(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))
		assertNull(readWorkspaceInstructions(workspace))
	}

	@Test
	fun `a directory named AGENTS dot md is ignored`() {
		file(WORKSPACE_INSTRUCTIONS_FILE).mkdirs()
		assertNull(readWorkspaceInstructions(workspace))
	}

	@Test
	fun `the block has delimiters and never the absolute path`() {
		write(WORKSPACE_INSTRUCTIONS_FILE, "Use the workspace.")
		val block = workspaceInstructionsBlock(readWorkspaceInstructions(workspace)!!)
		assertTrue(block.startsWith(WORKSPACE_INSTRUCTIONS_MARKER))
		assertTrue(block.contains("--- BEGIN AGENTS.md ---"))
		assertTrue(block.contains("--- END AGENTS.md ---"))
		assertFalse(block.contains(workspace.rootPath().path))
	}
}
