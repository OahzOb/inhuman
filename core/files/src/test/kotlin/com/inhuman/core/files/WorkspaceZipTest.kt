package com.inhuman.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class WorkspaceZipTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var root: File
	private lateinit var workspace: SessionWorkspace

	@Before
	fun setUp() {
		root = tmp.newFolder("workspace")
		workspace = SessionWorkspace.open(root)
	}

	@Test
	fun `zip keeps relative paths and file contents`() {
		File(root, "notes").mkdirs()
		File(root, "notes/a.txt").writeText("hello")
		File(root, "root.txt").writeText("root")

		val result = zip()

		assertEquals(2, result.files)
		assertEquals(9L, result.bytes)
		assertEquals(0, result.skipped)
		val entries = entries(result.bytesArray)
		assertEquals("hello", entries["notes/a.txt"])
		assertEquals("root", entries["root.txt"])
		assertTrue(entries.containsKey("notes/"))
	}

	@Test
	fun `an empty directory survives the round trip`() {
		File(root, "empty").mkdirs()

		val result = zip()

		assertEquals(0, result.files)
		assertTrue(entries(result.bytesArray).containsKey("empty/"))
	}

	@Test
	fun `an empty workspace produces an empty zip`() {
		val result = zip()

		assertEquals(0, result.files)
		assertEquals(0, result.bytes)
		assertTrue(entries(result.bytesArray).isEmpty())
	}

	@Test
	fun `a symlinked file outside the workspace is skipped`() {
		val outside = File(tmp.newFolder("outside"), "secret.txt").apply { writeText("top secret") }
		val link = File(root, "leak.txt")
		try {
			Files.createSymbolicLink(link.toPath(), outside.toPath())
		} catch (_: Exception) {
			return // No symlink privilege (typical on Windows without developer mode).
		}

		val result = zip()

		assertEquals(1, result.skipped)
		assertFalse(entries(result.bytesArray).containsKey("leak.txt"))
	}

	@Test
	fun `a symlinked directory outside the workspace is skipped`() {
		val outside = File(tmp.newFolder("outside-dir"), "secret.txt").apply {
			parentFile.mkdirs()
			writeText("top secret")
		}
		val link = File(root, "escape")
		try {
			Files.createSymbolicLink(link.toPath(), outside.parentFile.toPath())
		} catch (_: Exception) {
			return
		}

		val result = zip()

		assertTrue(result.skipped >= 1)
		assertFalse(entries(result.bytesArray).containsKey("escape/secret.txt"))
	}

	@Test
	fun `a windows junction outside the workspace is not followed`() {
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
			val result = zip()
			assertTrue(result.skipped >= 1)
			assertFalse(entries(result.bytesArray).containsKey("junction/secret.txt"))
		} finally {
			runCatching { Files.deleteIfExists(junction.toPath()) }
		}
	}

	private fun zip(): ZipBytes {
		val out = ByteArrayOutputStream()
		val result = WorkspaceZip.write(workspace, out)
		return ZipBytes(result.files, result.bytes, result.skipped, out.toByteArray())
	}

	private fun entries(bytes: ByteArray): Map<String, String> {
		val out = LinkedHashMap<String, String>()
		ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				out[entry.name] = if (entry.isDirectory) "" else zip.readBytes().toString(Charsets.UTF_8)
				zip.closeEntry()
			}
		}
		return out
	}

	private data class ZipBytes(
		val files: Int,
		val bytes: Long,
		val skipped: Int,
		val bytesArray: ByteArray,
	)
}