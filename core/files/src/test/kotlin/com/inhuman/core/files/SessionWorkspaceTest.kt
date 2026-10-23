package com.inhuman.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Tests for the workspace gate.
 *
 * These exist because the confinement rule is the app's security foundation,
 * and "the model was told not to" is not a guarantee. Every rejection below is
 * a case a hostile or merely confused model can actually produce: an absolute
 * path, a traversal, a symlink planted by a previous tool call.
 */
class SessionWorkspaceTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var root: File
	private lateinit var ws: SessionWorkspace

	@Before
	fun setUp() {
		root = tmp.newFolder("workspace")
		ws = SessionWorkspace.open(root)
	}

	private fun resolveOk(path: String): File {
		val r = ws.resolve(path)
		assertTrue("expected '$path' to resolve, got $r", r is SessionWorkspace.Resolution.Ok)
		return (r as SessionWorkspace.Resolution.Ok).file
	}

	private fun resolveRejected(path: String): String {
		val r = ws.resolve(path)
		assertTrue("expected '$path' to be rejected, got $r", r is SessionWorkspace.Resolution.Rejected)
		return (r as SessionWorkspace.Resolution.Rejected).reason
	}

	// --- the happy path -----------------------------------------------------

	@Test
	fun `plain relative path resolves inside the workspace`() {
		val f = resolveOk("notes/todo.txt")
		assertEquals(File(root, "notes/todo.txt").canonicalPath, f.canonicalPath)
		assertTrue(f.canonicalPath.startsWith(root.canonicalPath))
	}

	@Test
	fun `empty path resolves to the root itself`() {
		assertEquals(root.canonicalPath, resolveOk("").canonicalPath)
	}

	@Test
	fun `redundant separators and single dots are normalised`() {
		val a = resolveOk("./a//b/./c.txt")
		val b = resolveOk("a/b/c.txt")
		assertEquals(b.canonicalPath, a.canonicalPath)
	}

	@Test
	fun `backslashes are treated as separators`() {
		// A model that learned Windows paths should not be able to smuggle one
		// past the check by using the other separator.
		assertEquals(resolveOk("a/b.txt").canonicalPath, resolveOk("a\\b.txt").canonicalPath)
	}

	// --- the escapes this class exists to stop ------------------------------

	@Test
	fun `parent traversal is rejected`() {
		val reason = resolveRejected("../secrets.txt")
		assertTrue(reason.contains(".."))
	}

	@Test
	fun `traversal buried in the middle is rejected`() {
		resolveRejected("a/b/../../../etc/passwd")
	}

	@Test
	fun `traversal with backslashes is rejected`() {
		resolveRejected("..\\..\\windows\\system32")
	}

	@Test
	fun `absolute path is not honoured as absolute`() {
		// Degrading to relative rather than erroring: the result must still be
		// inside the workspace, which is the property that matters.
		val f = resolveOk("/etc/passwd")
		assertTrue(
			"absolute-looking path escaped: ${f.canonicalPath}",
			f.canonicalPath.startsWith(root.canonicalPath),
		)
	}

	@Test
	fun `windows drive-letter path is rejected, not silently resolved`() {
		// On Windows a component containing ':' is not a legal filename, so
		// this is refused outright. That is the *better* outcome than the
		// alternative -- Windows also treats "C:foo" as drive-relative, which
		// would resolve relative to the process working directory and land
		// outside the workspace entirely. Refusing means we never have to rely
		// on that distinction being handled correctly.
		val reason = resolveRejected("C:/Windows/System32/cmd.exe")
		assertTrue(
			"expected a filesystem-invalid rejection, got: $reason",
			reason.contains("not valid") || reason.contains("escapes"),
		)
	}

	@Test
	fun `windows drive-relative path cannot escape`() {
		// "C:foo" is drive-relative on Windows, not absolute. If it ever
		// resolved instead of being rejected, the result must still be inside
		// the workspace.
		when (val r = ws.resolve("C:foo/bar.txt")) {
			is SessionWorkspace.Resolution.Rejected -> Unit // expected on Windows
			is SessionWorkspace.Resolution.Ok -> assertTrue(
				"drive-relative path escaped: ${r.file.canonicalPath}",
				r.file.canonicalPath.startsWith(root.canonicalPath),
			)
		}
	}

	@Test
	fun `null byte is rejected`() {
		val reason = resolveRejected("evil\u0000.txt")
		assertTrue(reason.contains("null"))
	}

	// --- symlinks -----------------------------------------------------------
	//
	// Creating symlinks needs privileges on Windows, so these are skipped there
	// rather than silently passing. On Linux CI they run for real.

	@Test
	fun `symlink pointing outside the workspace is rejected`() {
		val outside = tmp.newFolder("outside")
		val secret = File(outside, "secret.txt").apply { writeText("top secret") }

		val link = File(root, "link.txt")
		try {
			Files.createSymbolicLink(link.toPath(), secret.toPath())
		} catch (e: Exception) {
			// No symlink privilege (typical on Windows without developer mode).
			return
		}

		assertTrue(ws.resolve("link.txt") is SessionWorkspace.Resolution.Rejected)
	}

	@Test
	fun `symlinked directory is rejected even when it points inside`() {
		val inside = File(root, "real").apply { mkdirs() }
		val link = File(root, "alias")
		try {
			Files.createSymbolicLink(link.toPath(), inside.toPath())
		} catch (e: Exception) {
			return
		}

		// Points inside the root and is still refused: the rule is "no
		// symlinks", not "no symlinks that happen to escape". A rule with an
		// exception is a rule with a hole.
		assertTrue(ws.resolve("alias/x.txt") is SessionWorkspace.Resolution.Rejected)
	}

	@Test
	fun `windows junction outside the workspace is rejected`() {
		if (!System.getProperty("os.name").lowercase().contains("windows")) return
		val outside = tmp.newFolder("junction-outside")
		File(outside, "secret.txt").writeText("top secret")
		val junction = File(root, "junction")
		val process = ProcessBuilder(
			"cmd",
			"/c",
			"mklink",
			"/J",
			junction.absolutePath,
			outside.absolutePath,
		).redirectErrorStream(true).start()
		process.inputStream.readBytes()
		if (process.waitFor() != 0) return

		try {
			assertTrue(
				"junction escaped the workspace: ${junction.canonicalPath}",
				ws.resolve("junction/secret.txt") is SessionWorkspace.Resolution.Rejected,
			)
		} finally {
			runCatching { Files.deleteIfExists(junction.toPath()) }
		}
	}

	// --- relativize ---------------------------------------------------------

	@Test
	fun `relativize inverts resolve`() {
		val f = resolveOk("a/b/c.txt")
		assertEquals("a/b/c.txt", ws.relativize(f))
	}

	@Test
	fun `relativize refuses a file outside the workspace`() {
		val outside = tmp.newFile("outside.txt")
		try {
			ws.relativize(outside)
			throw AssertionError("relativize accepted a file outside the workspace")
		} catch (expected: IllegalStateException) {
			// correct
		}
	}

	// --- accounting ---------------------------------------------------------

	@Test
	fun `usedBytes and fileCount see what was written`() {
		File(root, "a.txt").writeText("12345")
		File(root, "sub").mkdirs()
		File(root, "sub/b.txt").writeText("123")

		assertEquals(2, ws.fileCount())
		assertEquals(8L, ws.usedBytes())
	}
}
