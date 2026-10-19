package com.inhuman.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Import must not be a way around the quotas, and must not delete anything.
 *
 * ## The two failures this pins
 *
 * `WorkspaceTransfer.import` streamed straight into its destination and called
 * `target.delete()` on every failure path. That produced two ways to lose data,
 * and the second is not obvious from reading the limit check:
 *
 * 1. A failed import of an **existing** file deleted the original. The user
 *    picks a file, the read fails halfway, and what was already there is gone.
 * 2. `resolve` returns an existing *empty directory* without complaint, so
 *    `outputStream()` throws -- and the catch then deleted that directory.
 *    Importing into a folder the user had just created removed the folder.
 *
 * It also enforced only `maxWriteBytes`, so importing many files one at a time
 * walked straight past the total-bytes and file-count ceilings. Those checks now
 * live on [SessionWorkspace.quotaRefusal], shared with `WorkspaceTool`, because
 * a rule with two copies is how one of them comes to be missing.
 */
class WorkspaceTransferTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private fun openWorkspace(limits: WorkspaceLimits = WorkspaceLimits()) =
		SessionWorkspace.open(File(tmp.root, "ws").apply { mkdirs() }, limits)

	private fun transfer(ws: SessionWorkspace) =
		WorkspaceTransfer(ws, freeSpaceBytes = { null })

	private fun bytes(count: Int) = ByteArrayInputStream(ByteArray(count) { 'x'.code.toByte() })

	@Test
	fun `the per-file ceiling is a parameter and null removes only that ceiling`() {
		val ws = openWorkspace(WorkspaceLimits(maxWriteBytes = 4))
		val transfer = transfer(ws)

		assertTrue(
			"the default still applies to a tool-sized import",
			transfer.import("tool.bin") { bytes(5) } is WorkspaceTransfer.Outcome.Failed,
		)
		assertTrue(
			"a user download can raise the per-file ceiling",
			transfer.import("download.bin", maxBytes = null) { bytes(5) }
				is WorkspaceTransfer.Outcome.Ok,
		)
	}

	@Test
	fun `a failed import does not delete the file that was already there`() {
		val ws = openWorkspace()
		val transfer = transfer(ws)
		val existing = File(tmp.root, "ws/notes.txt").apply {
			// `parentFile` is nullable in the JVM API even though this path always
			// has a parent, and the warning it produced was the only one in the
			// tree -- which this project keeps at zero on purpose.
			parentFile?.mkdirs()
			writeText("the user's work")
		}

		// A source that throws after its first chunk, which is what a revoked
		// content:// grant or a pulled SD card looks like from here.
		val exploding = object : java.io.InputStream() {
			private var served = false
			override fun read(): Int = throw java.io.IOException("source went away")
			override fun read(b: ByteArray, off: Int, len: Int): Int {
				if (served) throw java.io.IOException("source went away")
				served = true
				b[off] = 'x'.code.toByte()
				return 1
			}
		}

		val outcome = transfer.import("notes.txt") { exploding }

		assertTrue("the import failed, as it should", outcome is WorkspaceTransfer.Outcome.Failed)
		assertTrue("the original file still exists", existing.exists())
		assertEquals("and still has its contents", "the user's work", existing.readText())
	}

	@Test
	fun `importing onto a directory does not delete the directory`() {
		val ws = openWorkspace()
		val transfer = transfer(ws)
		val dir = File(tmp.root, "ws/reports").apply { mkdirs() }

		val outcome = transfer.import("reports") { bytes(10) }

		assertTrue(outcome is WorkspaceTransfer.Outcome.Failed)
		assertTrue("the directory is still there", dir.isDirectory)
	}

	@Test
	fun `a successful import replaces the target and leaves no staging file`() {
		val ws = openWorkspace()
		val transfer = transfer(ws)
		File(tmp.root, "ws").mkdirs()

		val first = transfer.import("notes.txt") { bytes(10) }
		val second = transfer.import("notes.txt") { bytes(20) }

		assertTrue(first is WorkspaceTransfer.Outcome.Ok)
		assertTrue(second is WorkspaceTransfer.Outcome.Ok)
		assertEquals(20L, (second as WorkspaceTransfer.Outcome.Ok).bytes)
		assertEquals(20L, File(tmp.root, "ws/notes.txt").length())
		assertFalse(
			"no staging file survives",
			File(tmp.root, "ws").listFiles().orEmpty().any { it.name.startsWith(".") },
		)
	}

	@Test
	fun `import respects the total bytes ceiling`() {
		// The side door: 1 KB at a time is inside maxWriteBytes forever, and the
		// workspace could still be filled.
		val ws = openWorkspace(WorkspaceLimits(maxTotalBytes = 3_000))
		val transfer = transfer(ws)

		assertTrue(transfer.import("a.bin") { bytes(2_000) } is WorkspaceTransfer.Outcome.Ok)
		val second = transfer.import("b.bin") { bytes(2_000) }

		assertTrue("the second import must be refused", second is WorkspaceTransfer.Outcome.Failed)
		assertTrue((second as WorkspaceTransfer.Outcome.Failed).reason.contains("full"))
	}

	@Test
	fun `import respects the file count ceiling`() {
		val ws = openWorkspace(WorkspaceLimits(maxFileCount = 2))
		val transfer = transfer(ws)

		assertTrue(transfer.import("a.txt") { bytes(1) } is WorkspaceTransfer.Outcome.Ok)
		assertTrue(transfer.import("b.txt") { bytes(1) } is WorkspaceTransfer.Outcome.Ok)
		val third = transfer.import("c.txt") { bytes(1) }

		assertTrue(third is WorkspaceTransfer.Outcome.Failed)
		assertTrue((third as WorkspaceTransfer.Outcome.Failed).reason.contains("file limit"))
	}

	@Test
	fun `replacing a file is charged by its growth, not its whole size`() {
		// Otherwise a workspace at the ceiling could not have a file corrected,
		// and the count would be charged twice for the same file.
		val ws = openWorkspace(WorkspaceLimits(maxTotalBytes = 1_000, maxFileCount = 1))
		val transfer = transfer(ws)

		assertTrue(transfer.import("a.txt") { bytes(800) } is WorkspaceTransfer.Outcome.Ok)
		// Same file, smaller: no new file, no growth.
		assertTrue(transfer.import("a.txt") { bytes(400) } is WorkspaceTransfer.Outcome.Ok)
		// Same file, still inside the ceiling even though 400 + 200 exceeds nothing.
		assertTrue(transfer.import("a.txt") { bytes(200) } is WorkspaceTransfer.Outcome.Ok)
	}

	@Test
	fun `the free space check refuses rather than filling the volume`() {
		val ws = openWorkspace(WorkspaceLimits(reservedFreeBytes = 1_000))
		val transfer = WorkspaceTransfer(ws, freeSpaceBytes = { 500 })

		val outcome = transfer.import("big.bin") { bytes(100) }

		assertTrue(outcome is WorkspaceTransfer.Outcome.Failed)
		assertTrue((outcome as WorkspaceTransfer.Outcome.Failed).reason.contains("free space"))
	}
}
