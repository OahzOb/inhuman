package com.inhuman.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The file half of checkpointed rollback, without Room or a device.
 *
 * The interesting cases are the mid-turn ones: after a checkpoint is captured,
 * the workspace can move on, and restoring an older checkpoint must put back
 * the exact file versions from that moment -- including removing files created
 * later and restoring files deleted later. The start checkpoint and the
 * content-hash diff are the other two guarantees.
 */
class SnapshotStoreTest {

	private fun tempDir(prefix: String): File =
		Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

	@Test
	fun `start and tool checkpoints restore their own versions`() {
		val root = tempDir("snapshot-ws")
		val turns = tempDir("snapshot-turns")
		val turn = SnapshotStore.turnDir(turns, "turn-1")
		File(root, "a.txt").writeText("one")

		assertTrue(SnapshotStore.capture(root, turn, SnapshotStore.START))

		File(root, "a.txt").writeText("two")
		File(root, "new.txt").writeText("new")
		assertTrue(SnapshotStore.capture(root, turn, "after-write"))

		File(root, "a.txt").writeText("three")
		File(root, "new.txt").delete()

		assertTrue(SnapshotStore.restore(root, turn, SnapshotStore.START))
		assertEquals("one", File(root, "a.txt").readText())
		assertFalse(File(root, "new.txt").exists())

		assertTrue(SnapshotStore.restore(root, turn, "after-write"))
		assertEquals("two", File(root, "a.txt").readText())
		assertEquals("new", File(root, "new.txt").readText())
	}

	@Test
	fun `diff compares content against a checkpoint`() {
		val root = tempDir("snapshot-ws")
		val turns = tempDir("snapshot-turns")
		val turn = SnapshotStore.turnDir(turns, "turn-1")
		File(root, "same.txt").writeText("same")
		File(root, "changed.txt").writeText("before")
		File(root, "deleted.txt").writeText("gone")
		assertTrue(SnapshotStore.capture(root, turn, SnapshotStore.START))

		File(root, "changed.txt").writeText("after")
		File(root, "deleted.txt").delete()
		File(root, "added.txt").writeText("new")

		val changes = SnapshotStore.diff(root, turn, SnapshotStore.START)
			.associate { it.path to it.kind }
		assertEquals(WorkspaceChange.Kind.Modified, changes["changed.txt"])
		assertEquals(WorkspaceChange.Kind.Deleted, changes["deleted.txt"])
		assertEquals(WorkspaceChange.Kind.Added, changes["added.txt"])
		assertNull(changes["same.txt"])
	}

	@Test
	fun `a checkpoint with a missing blob leaves the workspace alone`() {
		val root = tempDir("snapshot-ws")
		val turns = tempDir("snapshot-turns")
		val turn = SnapshotStore.turnDir(turns, "turn-1")
		File(root, "a.txt").writeText("one")
		assertTrue(SnapshotStore.capture(root, turn, SnapshotStore.START))

		// What a half-deleted rollback directory looks like on disk.
		File(turn, "blobs").listFiles().orEmpty().forEach { it.delete() }
		File(root, "a.txt").writeText("edited after the checkpoint")

		assertFalse(SnapshotStore.restore(root, turn, SnapshotStore.START))
		// The bug this pins: restore used to empty the workspace before
		// copying, so a failure left neither the checkpoint nor the work the
		// user had when they asked to roll back.
		assertEquals("edited after the checkpoint", File(root, "a.txt").readText())
	}

	@Test
	fun `restore leaves no staging directory beside the workspace`() {
		val root = tempDir("snapshot-ws")
		val turns = tempDir("snapshot-turns")
		val turn = SnapshotStore.turnDir(turns, "turn-1")
		File(root, "a.txt").writeText("one")
		assertTrue(SnapshotStore.capture(root, turn, SnapshotStore.START))
		File(root, "a.txt").writeText("two")

		assertTrue(SnapshotStore.restore(root, turn, SnapshotStore.START))
		assertEquals("one", File(root, "a.txt").readText())

		val leftovers = root.parentFile?.listFiles().orEmpty()
			.filter { it.name.startsWith(".restore") }
		assertTrue("left behind: ${leftovers.map { it.name }}", leftovers.isEmpty())
	}

	@Test
	fun `stats count checkpointed turns and prune keeps the newest`() {
		val root = tempDir("snapshot-ws")
		val turns = tempDir("snapshot-turns")
		File(root, "a.txt").writeText("one")
		val first = SnapshotStore.turnDir(turns, "turn-1")
		assertTrue(SnapshotStore.capture(root, first, SnapshotStore.START))
		Thread.sleep(5)
		val second = SnapshotStore.turnDir(turns, "turn-2")
		assertTrue(SnapshotStore.capture(root, second, SnapshotStore.START))

		assertEquals(2, SnapshotStore.stats(turns).turns)
		assertTrue(SnapshotStore.stats(turns).bytes > 0)
		SnapshotStore.prune(turns, 1)
		assertEquals(1, SnapshotStore.stats(turns).turns)
		assertFalse(File(first, "meta.json").exists())
	}

	@Test
	fun `turns lists each retained turn with its checkpoints, newest first`() {
		val root = tempDir("snapshot-ws")
		val turns = tempDir("snapshot-turns")
		File(root, "a.txt").writeText("one")
		val first = SnapshotStore.turnDir(turns, "turn-1")
		assertTrue(SnapshotStore.capture(root, first, SnapshotStore.START))
		assertTrue(SnapshotStore.capture(root, first, "after-write"))
		Thread.sleep(5)
		val second = SnapshotStore.turnDir(turns, "turn-2")
		assertTrue(SnapshotStore.capture(root, second, SnapshotStore.START))

		val listed = SnapshotStore.turns(turns)
		assertEquals(listOf("turn-2", "turn-1"), listed.map { it.token })
		assertEquals(1, listed[0].checkpoints)
		assertEquals(2, listed[1].checkpoints)
		assertTrue(listed[1].bytes > 0)
		assertEquals(2, SnapshotStore.stats(turns).turns)
	}
}
