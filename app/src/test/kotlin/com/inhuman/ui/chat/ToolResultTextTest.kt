package com.inhuman.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tool results keep monospace.
 *
 * The row is the biggest text area in a tool-heavy turn, so the choice is
 * visible: prose in monospace is harder to read, but a JSON blob or an indented
 * stack trace wrapped in a proportional font loses the alignment that makes it
 * legible. These pin the boundary.
 */
class ToolResultTextTest {

	@Test
	fun `json keeps monospace`() {
		assertTrue(toolResultIsMachinery("""{"path":"a.txt","bytes":12}"""))
		assertTrue(toolResultIsMachinery("[1, 2, 3]"))
		assertTrue(toolResultIsMachinery("  { \"nested\": true }  "))
	}

	@Test
	fun `prose is not machinery`() {
		assertFalse(toolResultIsMachinery("DeepSeek releases a new model. The announcement covers pricing."))
		assertFalse(toolResultIsMachinery("line one\nline two, not indented"))
		assertFalse(toolResultIsMachinery(""))
	}

	@Test
	fun `an indented block keeps monospace`() {
		assertTrue(
			toolResultIsMachinery(
				"""
				Exception in thread "main"
				    at com.inhuman.Main.run(Main.kt:12)
				    at java.base.Thread.run(Thread.java:833)
				""".trimIndent(),
			),
		)
		assertTrue(toolResultIsMachinery("diff --git a/x b/x\n\tremoved line"))
	}

	@Test
	fun `a single unwrapped bracket does not count as json`() {
		// `{` opening a sentence is not a document; the pair has to close.
		assertFalse(toolResultIsMachinery("{not json"))
		assertFalse(toolResultIsMachinery("[see the notes]"))
	}
}
