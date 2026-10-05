package com.inhuman.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Which workspace directories may be deleted.
 *
 * This decides what to remove from the user's private storage, so the cases
 * that matter are the ones where it says "yes" about something it should keep.
 * A UUID workspace id is never reused, which is what makes the rule safe; these
 * tests pin the rule rather than the ids.
 */
class OrphanedWorkspacesTest {

	private fun dirWith(name: String, files: Int = 1): File {
		val root = Files.createTempDirectory("sweep").toFile()
		val dir = File(root, name).apply { mkdirs() }
		repeat(files) { File(dir, "f$it.txt").writeText("x") }
		return root
	}

	@Test
	fun `a directory no conversation names is orphaned`() {
		val root = dirWith("kept")
		File(root, "forgotten").mkdirs()
		File(root, "forgotten/notes.txt").writeText("the user's work")

		val doomed = ConversationRepository.orphanedWorkspaces(root, setOf("kept"))

		assertEquals(listOf("forgotten"), doomed.map { it.name })
		root.deleteRecursively()
	}

	@Test
	fun `a named directory is never orphaned, even when empty`() {
		val root = dirWith("busy", files = 3)
		File(root, "empty-but-real").mkdirs()

		val doomed = ConversationRepository.orphanedWorkspaces(
			root,
			setOf("busy", "empty-but-real"),
		)

		assertEquals(emptyList<String>(), doomed.map { it.name })
		root.deleteRecursively()
	}

	@Test
	fun `a healthy install has nothing to sweep`() {
		val root = dirWith("a")
		File(root, "b").mkdirs()

		assertEquals(emptyList<String>(), ConversationRepository.orphanedWorkspaces(root, setOf("a", "b")))
		root.deleteRecursively()
	}

	@Test
	fun `a plain file in the workspaces root is left alone`() {
		// Not a shape the app writes, but deleting a file because it is not a
		// directory it recognises would be the wrong direction to fail in.
		val root = dirWith("a")
		File(root, "README.txt").writeText("not a workspace")

		val doomed = ConversationRepository.orphanedWorkspaces(root, setOf("a"))

		assertEquals(emptyList<String>(), doomed.map { it.name })
		root.deleteRecursively()
	}

	@Test
	fun `a missing root is not a crash`() {
		assertEquals(
			emptyList<String>(),
			ConversationRepository.orphanedWorkspaces(File("does-not-exist-${System.nanoTime()}"), emptySet())
				.map { it.name },
		)
	}
}
