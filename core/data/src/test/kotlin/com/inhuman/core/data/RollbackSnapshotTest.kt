package com.inhuman.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The file half of rollback, tested without Room or a device.
 *
 * The case that matters is not "a file comes back" but "the workspace is the
 * one from before the turn": a file added during the turn must disappear, a
 * file deleted during it must return, and a nested/empty directory must survive
 * the copy. A row-only rollback is exactly the failure this prevents.
 */
class RollbackSnapshotTest {

	private fun tempDir(prefix: String): File =
		Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

	@Test
	fun `restore puts back changed, added and deleted paths`() {
		val root = tempDir("rollback-ws")
		val snapshot = tempDir("rollback-snap")
		val sub = File(root, "notes").apply { mkdirs() }
		val empty = File(root, "empty").apply { mkdirs() }
		File(root, "a.txt").writeText("before")
		File(sub, "b.txt").writeText("nested")

		assertTrue(RollbackSnapshot.capture(root, snapshot))

		File(root, "a.txt").writeText("after")
		File(root, "new.txt").writeText("new")
		File(sub, "b.txt").delete()
		File(root, "empty").deleteRecursively()

		assertTrue(RollbackSnapshot.restore(root, snapshot))

		assertEquals("before", File(root, "a.txt").readText())
		assertEquals("nested", File(sub, "b.txt").readText())
		assertFalse(File(root, "new.txt").exists())
		assertTrue("empty directory must survive", empty.isDirectory)
	}

	@Test
	fun `diff reports paths by actual content`() {
		val root = tempDir("rollback-ws")
		val snapshot = tempDir("rollback-snap")
		File(root, "same.txt").writeText("same")
		File(root, "changed.txt").writeText("before")
		File(root, "deleted.txt").writeText("gone")

		assertTrue(RollbackSnapshot.capture(root, snapshot))

		File(root, "changed.txt").writeText("after")
		File(root, "deleted.txt").delete()
		File(root, "added.txt").writeText("new")

		val changes = RollbackSnapshot.diff(root, snapshot).associate { it.path to it.kind }
		assertEquals(WorkspaceChange.Kind.Modified, changes["changed.txt"])
		assertEquals(WorkspaceChange.Kind.Deleted, changes["deleted.txt"])
		assertEquals(WorkspaceChange.Kind.Added, changes["added.txt"])
		assertFalse("unchanged file must be omitted", "same.txt" in changes)
	}

	@Test
	fun `restore refuses a missing snapshot`() {
		val root = tempDir("rollback-ws")
		File(root, "a.txt").writeText("still here")
		val missing = File(tempDir("rollback-missing"), "nope")

		assertFalse(RollbackSnapshot.restore(root, missing))
		assertEquals("still here", File(root, "a.txt").readText())
	}
}
