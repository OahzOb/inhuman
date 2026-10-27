package com.inhuman.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Copying between workspaces: a snapshot, a copy, and never an overwrite.
 *
 * The source is left byte-for-byte alone (it is the user's other conversation,
 * not a move), and the target uses the same naming and quota path as a system
 * import. The traversal case is the one that matters for the fence: the browser
 * supplies a relative path, but the function re-resolves it rather than trusting
 * the value that reached the UI.
 */
class WorkspaceCopyTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var source: SessionWorkspace
	private lateinit var target: SessionWorkspace

	@Before
	fun setUp() {
		source = SessionWorkspace.open(tmp.newFolder("source"))
		target = SessionWorkspace.open(tmp.newFolder("target"))
	}

	@Test
	fun `a second copy is numbered, not an overwrite`() {
		File(source.rootPath(), "notes.txt").writeText("hello")

		val first = WorkspaceCopy.file(source, "notes.txt", target, "")
		assertTrue("first copy failed: $first", first is WorkspaceTransfer.Outcome.Ok)
		assertEquals("hello", File(target.rootPath(), "notes.txt").readText())

		val second = WorkspaceCopy.file(source, "notes.txt", target, "")
		assertTrue("second copy failed: $second", second is WorkspaceTransfer.Outcome.Ok)
		assertTrue(File(target.rootPath(), "notes (2).txt").isFile)
		// The first copy is still intact.
		assertEquals("hello", File(target.rootPath(), "notes.txt").readText())
		// And the source was never moved or changed.
		assertEquals("hello", File(source.rootPath(), "notes.txt").readText())
	}

	@Test
	fun `copy lands in the directory the user is browsing`() {
		File(target.rootPath(), "sub").mkdirs()
		File(source.rootPath(), "a.txt").writeText("a")

		val outcome = WorkspaceCopy.file(source, "a.txt", target, "sub")

		assertTrue("copy failed: $outcome", outcome is WorkspaceTransfer.Outcome.Ok)
		assertEquals("sub/a.txt", (outcome as WorkspaceTransfer.Outcome.Ok).relativePath)
		assertEquals("a", File(target.rootPath(), "sub/a.txt").readText())
	}

	@Test
	fun `source traversal is refused even though the browser built the path`() {
		File(tmp.root, "outside.txt").writeText("not mine")

		val outcome = WorkspaceCopy.file(source, "../outside.txt", target, "")

		assertTrue("traversal was accepted: $outcome", outcome is WorkspaceTransfer.Outcome.Failed)
		assertTrue(!File(target.rootPath(), "outside.txt").exists())
	}

	@Test
	fun `a directory is not copied as a file`() {
		File(source.rootPath(), "folder").mkdirs()

		val outcome = WorkspaceCopy.file(source, "folder", target, "")

		assertTrue("a directory was accepted: $outcome", outcome is WorkspaceTransfer.Outcome.Failed)
	}
}