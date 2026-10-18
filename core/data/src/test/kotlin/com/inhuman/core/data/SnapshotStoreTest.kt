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
}
