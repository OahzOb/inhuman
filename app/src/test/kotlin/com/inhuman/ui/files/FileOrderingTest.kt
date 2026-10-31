package com.inhuman.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileOrderingTest {

	private fun file(
		name: String,
		directory: Boolean = false,
		size: Long = 0,
		modified: Long = 0,
	) = FileEntryUi(
		name = name,
		relativePath = name,
		isDirectory = directory,
		sizeBytes = size,
		modifiedAt = modified,
		mimeType = "text/plain",
	)

	@Test
	fun `directories stay first in every order`() {
		val rows = listOf(
			file("b.txt", size = 1, modified = 3),
			file("a-folder", directory = true),
			file("a.txt", size = 9, modified = 1),
		)

		for (sort in FileSort.entries) {
			val ordered = orderedFiles(rows, query = "", sort = sort)
			assertEquals("$sort", "a-folder", ordered.first().name)
		}
	}

	@Test
	fun `name order ignores case`() {
		val rows = listOf(file("beta.txt"), file("Alpha.txt"), file("gamma.txt"))

		assertEquals(
			listOf("Alpha.txt", "beta.txt", "gamma.txt"),
			orderedFiles(rows, query = "", sort = FileSort.Name).map { it.name },
		)
	}

	@Test
	fun `newest and largest sort descending with a name tie-break`() {
		val rows = listOf(
			file("old.txt", size = 10, modified = 1),
			file("new.txt", size = 5, modified = 9),
			file("same-new.txt", size = 5, modified = 9),
		)

		assertEquals(
			listOf("new.txt", "same-new.txt", "old.txt"),
			orderedFiles(rows, query = "", sort = FileSort.Modified).map { it.name },
		)
		assertEquals(
			listOf("old.txt", "new.txt", "same-new.txt"),
			orderedFiles(rows, query = "", sort = FileSort.Size).map { it.name },
		)
	}

	@Test
	fun `query filters names case-insensitively and trims`() {
		val rows = listOf(file("Notes.md"), file("photo.png"), file("notes.txt"))

		assertEquals(
			listOf("Notes.md", "notes.txt"),
			orderedFiles(rows, query = "  NOTES ", sort = FileSort.Name).map { it.name },
		)
	}
}
