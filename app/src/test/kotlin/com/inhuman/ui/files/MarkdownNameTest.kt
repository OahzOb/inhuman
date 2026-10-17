package com.inhuman.ui.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which names get the Markdown renderer in the file preview.
 *
 * A name that merely contains "md" is not enough: `notes.md.txt` is a text file
 * whose name happens to mention Markdown, and rendering it as one would hide the
 * literal content the user opened.
 */
class MarkdownNameTest {

	@Test
	fun `md is markdown`() {
		assertTrue(isMarkdownName("notes.md"))
	}

	@Test
	fun `the extension is case insensitive`() {
		assertTrue(isMarkdownName("README.MD"))
		assertTrue(isMarkdownName("Guide.Markdown"))
	}

	@Test
	fun `markdown is markdown`() {
		assertTrue(isMarkdownName("guide.markdown"))
	}

	@Test
	fun `a text file is not markdown`() {
		assertFalse(isMarkdownName("notes.txt"))
		assertFalse(isMarkdownName("notes.md.txt"))
	}

	@Test
	fun `a name without an extension is not markdown`() {
		assertFalse(isMarkdownName("md"))
		assertFalse(isMarkdownName("notes"))
	}

	@Test
	fun `a dot in a directory does not make a file markdown`() {
		assertFalse(isMarkdownName("notes.md/readme.txt"))
	}
}
