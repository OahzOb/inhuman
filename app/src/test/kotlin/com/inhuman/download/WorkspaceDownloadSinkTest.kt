package com.inhuman.download

import com.inhuman.core.ai.tools.DownloadSaveResult
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Where a `download_url` call's bytes land, and under what ceilings.
 *
 * The transfer itself is tested in `:core:files`; what matters here is the
 * policy this sink adds: a fixed `downloads/` directory, a name that can never
 * become a path, never overwriting, and the per-call ceiling reaching the
 * import path that enforces quota and free space.
 */
class WorkspaceDownloadSinkTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private fun openWorkspace(limits: WorkspaceLimits = WorkspaceLimits()) =
		SessionWorkspace.open(File(tmp.root, "ws").apply { mkdirs() }, limits)

	private fun sink(ws: SessionWorkspace) = WorkspaceDownloadSink(ws)

	private fun bytes(text: String) = ByteArrayInputStream(text.toByteArray())

	private fun save(
		ws: SessionWorkspace,
		name: String,
		text: String,
		maxBytes: Long = 1024,
	): DownloadSaveResult = runBlocking {
		sink(ws).save(name, maxBytes, bytes(text))
	}

	@Test
	fun `a download lands under downloads with its name`() {
		val ws = openWorkspace()
		val result = save(ws, "report.pdf", "hello")

		val saved = result as DownloadSaveResult.Saved
		assertEquals("downloads/report.pdf", saved.relativePath)
		assertEquals(5L, saved.bytes)
		assertEquals("hello", File(tmp.root, "ws/downloads/report.pdf").readText())
	}

	@Test
	fun `a second download of the same name is numbered rather than overwriting`() {
		val ws = openWorkspace()

		val first = save(ws, "report.pdf", "one") as DownloadSaveResult.Saved
		val second = save(ws, "report.pdf", "two") as DownloadSaveResult.Saved

		assertEquals("downloads/report.pdf", first.relativePath)
		assertEquals("downloads/report (2).pdf", second.relativePath)
		assertEquals("one", File(tmp.root, "ws/downloads/report.pdf").readText())
		assertEquals("two", File(tmp.root, "ws/downloads/report (2).pdf").readText())
	}

	@Test
	fun `a hostile file name cannot escape the folder`() {
		val ws = openWorkspace()
		val saved = save(ws, "../../etc/passwd", "x") as DownloadSaveResult.Saved

		assertTrue(saved.relativePath, saved.relativePath.startsWith("downloads/"))
		// One path segment under downloads/: separators were replaced, so the
		// ".." that survives inside the name is a literal filename, not a
		// traversal component.
		val tail = saved.relativePath.removePrefix("downloads/")
		assertFalse("the name must not add a separator", tail.contains('/'))
		assertFalse("the name must not add a separator", tail.contains('\\'))
		assertTrue(File(tmp.root, "ws/${saved.relativePath}").isFile)
		assertFalse("nothing may land outside downloads/", File(tmp.root, "ws/etc/passwd").exists())
		assertFalse("nothing may land outside the workspace", File(tmp.root, "etc/passwd").exists())
	}

	@Test
	fun `the per-call ceiling is enforced while streaming`() {
		val ws = openWorkspace()
		val result = save(ws, "big.bin", "1234567890", maxBytes = 4)

		assertTrue("over the limit must fail, not truncate", result is DownloadSaveResult.Failed)
		// The import path is atomic: a failed download must not leave a partial
		// file that looks like a complete one.
		assertFalse(File(tmp.root, "ws/downloads/big.bin").exists())
	}

	@Test
	fun `the workspace quota still applies`() {
		// The sink passes the per-call ceiling, not "unlimited": the ordinary
		// total/quota checks are what stop a download from filling the workspace.
		val ws = openWorkspace(WorkspaceLimits(maxTotalBytes = 8))
		val result = save(ws, "a.bin", "1234567890", maxBytes = 1024)

		assertTrue("the workspace total must bind", result is DownloadSaveResult.Failed)
	}
}
