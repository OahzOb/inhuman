package com.inhuman.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rewind cache, measured rather than assumed.
 *
 * `turns()` now reuses a turn's byte total until the turn directory changes.
 * The failure mode of that cache is a stale menu label, not a wrong restore,
 * but a stale label is still wrong -- so this pins both halves: a second read
 * does not change the answer, and adding a blob does.
 */
class SnapshotStoreTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private fun turnDir(name: String = "turn-1"): File {
		val conversation = tmp.root
		val turn = File(conversation, name).apply { mkdirs() }
		File(turn, "blobs").mkdirs()
		File(turn, "manifests").mkdirs()
		File(turn, "meta.json").writeText("""{"token":"$name","createdAt":1}""")
		return turn
	}

	@Test
	fun `an unchanged capture reuses every hash`() {
		val workspace = tmp.newFolder("ws")
		val turn = File(tmp.newFolder("conv"), "turn")
		File(workspace, "a.txt").writeText("one")
		File(workspace, "b.txt").writeText("two")

		assertTrue(SnapshotStore.capture(workspace, turn, "start"))
		SnapshotStore.resetHashedFilesForTest()
		assertTrue(SnapshotStore.capture(workspace, turn, "group"))

		// The cache key is size + nanosecond mtime; nothing changed, so no file
		// may be read. This is the property the checkpoint cost depends on.
		assertEquals("unchanged files must not be re-read", 0, SnapshotStore.hashedFiles)
	}

	@Test
	fun `only the file that changed is hashed again`() {
		val workspace = tmp.newFolder("ws")
		val turn = File(tmp.newFolder("conv"), "turn")
		File(workspace, "a.txt").writeText("one")
		File(workspace, "b.txt").writeText("two")
		assertTrue(SnapshotStore.capture(workspace, turn, "start"))

		File(workspace, "a.txt").writeText("changed")
		SnapshotStore.resetHashedFilesForTest()
		assertTrue(SnapshotStore.capture(workspace, turn, "group"))

		assertEquals(1, SnapshotStore.hashedFiles)
	}

	@Test
	fun `restore puts the captured content back and diff sees the change`() {
		val workspace = tmp.newFolder("ws")
		val turn = File(tmp.newFolder("conv"), "turn")
		File(workspace, "a.txt").writeText("one")
		assertTrue(SnapshotStore.capture(workspace, turn, "start"))

		File(workspace, "a.txt").writeText("three")
		assertTrue(SnapshotStore.capture(workspace, turn, "group"))

		val changes = SnapshotStore.diff(workspace, turn, "start")
		assertTrue("the edit should be reported: $changes", changes.any { it.path == "a.txt" })

		assertTrue(SnapshotStore.restore(workspace, turn, "start"))
		assertEquals("one", File(workspace, "a.txt").readText())
	}

	@Test
	fun `a turn's bytes include its blobs and follow additions`() {
		val turn = turnDir()
		File(turn, "blobs/first").writeBytes(ByteArray(1000))
		File(turn, "manifests/start.json").writeText("{}")

		val first = SnapshotStore.turns(tmp.root).single().bytes
		assertTrue("the blob should be counted: $first", first >= 1000)

		// A second read hits the cache and must not drift.
		assertEquals(first, SnapshotStore.turns(tmp.root).single().bytes)

		// A new blob changes the directory entry count, which is the cache key.
		File(turn, "blobs/second").writeBytes(ByteArray(500))
		assertEquals(first + 500, SnapshotStore.turns(tmp.root).single().bytes)
	}
}
