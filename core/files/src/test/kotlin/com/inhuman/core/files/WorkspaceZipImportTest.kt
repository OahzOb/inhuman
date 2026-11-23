package com.inhuman.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Importing a zip into an existing workspace.
 *
 * The export path already refuses to follow links out of the workspace; this
 * one has the mirror-image risk: an entry name is attacker-controlled text, and
 * `SessionWorkspace.resolve` treats leading separators as relative, so a
 * name-only check is not enough. These tests pin both halves: the cheap name
 * filter refuses traversal, and the sandbox is still the thing that decides
 * where bytes land.
 */
class WorkspaceZipImportTest {

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
	fun `nested files are written and counted`() {
		val result = import(zip("notes/a.txt" to "hello", "root.txt" to "root"))

		assertEquals(2, result.files)
		assertEquals(9L, result.bytes)
		assertTrue(result.skipped.isEmpty())
		assertFalse(result.failed)
		assertEquals("hello", File(root, "notes/a.txt").readText())
		assertEquals("root", File(root, "root.txt").readText())
	}

	@Test
	fun `directory entries are skipped, not written as files`() {
		val result = WorkspaceZip.importInto(
			workspace,
			ByteArrayInputStream(
				buildZip(
					listOf(
						"notes/" to null,
						"notes/a.txt" to "hello",
					),
				),
			),
		)

		assertEquals(1, result.files)
		assertEquals(5L, result.bytes)
		assertEquals(1, result.skipped.size)
		assertTrue(result.skipped.single().contains("directory"))
		assertTrue(File(root, "notes/a.txt").isFile)
	}

	@Test
	fun `traversal and absolute names are refused and write nothing`() {
		val result = import(
			zip(
				"../evil.txt" to "outside",
				"/abs.txt" to "absolute",
				"C:\\evil.txt" to "drive",
				"a\\..\\b" to "dots",
			),
		)

		assertEquals(0, result.files)
		assertEquals(0L, result.bytes)
		assertFalse(result.failed)
		assertEquals(4, result.skipped.size)
		// `../evil.txt` would have landed beside the workspace root.
		assertFalse("traversal escaped the workspace", File(tmp.root, "evil.txt").exists())
		assertTrue(workspaceFiles().isEmpty())
	}

	@Test
	fun `an empty zip imports nothing`() {
		val result = import(ByteArray(0))

		assertEquals(0, result.files)
		assertEquals(0L, result.bytes)
		assertTrue(result.skipped.isEmpty())
		assertFalse(result.failed)
	}

	@Test
	fun `a repeated name is written once`() {
		// `ZipOutputStream` refuses duplicate names, so two entries are written
		// and the second one's name is patched in both headers afterwards:
		// same length, so no offset moves.
		val bytes = patchName(
			buildZip(listOf("dup1.txt" to "one", "dup2.txt" to "two")),
			from = "dup2.txt",
			to = "dup1.txt",
		)

		val result = import(bytes)

		assertEquals(1, result.files)
		assertEquals(3L, result.bytes)
		assertEquals("one", File(root, "dup1.txt").readText())
		assertTrue(result.skipped.any { it.contains("duplicate") })
	}

	@Test
	fun `a file over the per-file ceiling is skipped and the rest continues`() {
		val small = workspaceWith("small", WorkspaceLimits(maxWriteBytes = 4))

		val result = import(zip("big.txt" to "12345", "ok.txt" to "1234"), into = small)

		assertEquals(1, result.files)
		assertEquals(4L, result.bytes)
		assertFalse(result.failed)
		assertTrue(result.skipped.any { it.contains("larger") })
		assertFalse(File(small.rootPath(), "big.txt").exists())
		assertTrue(File(small.rootPath(), "ok.txt").exists())
	}

	@Test
	fun `a quota refusal stops the import and keeps what was written`() {
		val tiny = workspaceWith("tiny", WorkspaceLimits(maxTotalBytes = 3))

		val result = import(zip("a.bin" to "aa", "b.bin" to "bb"), into = tiny)

		assertTrue("the import stopped", result.failed)
		assertNotNull(result.failure)
		assertTrue(result.failure.orEmpty().contains("full"))
		assertEquals(1, result.files)
		assertEquals(2L, result.bytes)
		assertTrue(File(tiny.rootPath(), "a.bin").isFile)
		assertFalse(File(tiny.rootPath(), "b.bin").exists())
	}

	@Test
	fun `a truncated archive stops with a failure`() {
		val complete = buildZip(listOf("a.txt" to "hello"))
		// The local header is 30 bytes and the name is 5, so cutting at 36
		// leaves one byte of deflated data: the entry cannot finish.
		val truncated = complete.copyOf(36)

		val result = import(truncated)

		assertTrue("a truncated zip must not look complete", result.failed)
		assertNotNull(result.failure)
	}

	private fun workspaceWith(name: String, limits: WorkspaceLimits): SessionWorkspace =
		SessionWorkspace.open(File(tmp.root, name).apply { mkdirs() }, limits)

	private fun import(
		bytes: ByteArray,
		into: SessionWorkspace = workspace,
	): WorkspaceZip.ImportResult =
		WorkspaceZip.importInto(into, ByteArrayInputStream(bytes))

	private fun workspaceFiles(): List<File> =
		workspace.rootPath().walkTopDown().filter { it.isFile }.toList()

	private fun zip(vararg entries: Pair<String, String>): ByteArray =
		buildZip(entries.map { it.first to it.second })

	private fun buildZip(entries: List<Pair<String, String?>>): ByteArray {
		val out = ByteArrayOutputStream()
		ZipOutputStream(out).use { zip ->
			for ((name, content) in entries) {
				zip.putNextEntry(ZipEntry(name))
				if (content != null) zip.write(content.toByteArray(Charsets.UTF_8))
				zip.closeEntry()
			}
		}
		return out.toByteArray()
	}

	/**
	 * Replace an entry name in both the local header and the central directory.
	 *
	 * Binary-safe through ISO-8859-1, and the two names are required to be the
	 * same length so no offset in the archive moves.
	 */
	private fun patchName(bytes: ByteArray, from: String, to: String): ByteArray {
		require(from.length == to.length) { "patching must not change lengths" }
		val patched = String(bytes, Charsets.ISO_8859_1).replace(from, to)
		return patched.toByteArray(Charsets.ISO_8859_1)
	}
}
