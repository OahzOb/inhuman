package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wire inspector used to know two system blocks: the assistant prompt and
 * the compaction summary. A workspace block appended after the summary made
 * the last two look like one, so the preview labelled the file's text
 * "Earlier conversation summary". These cases pin the three-way split.
 */
class WireSheetSystemSplitTest {

	private val summary =
		"Earlier conversation summary (the messages it covers are omitted from this request):"

	@Test
	fun `assistant prompt, summary and workspace instructions are three blocks`() {
		val block = workspaceInstructionsBlock(WorkspaceInstructions("Use LF.", false, 7))
		val blocks = splitSystem("You are terse.\n\n$summary\nold turns\n\n$block")
		assertEquals(
			listOf("Assistant prompt", "Earlier conversation summary", "Workspace instructions (AGENTS.md)"),
			blocks.map { it.first },
		)
		assertEquals("You are terse.", blocks[0].second)
		assertEquals("old turns", blocks[1].second)
		assertEquals(block, blocks[2].second)
	}

	@Test
	fun `a workspace block without a summary is still labelled`() {
		val block = workspaceInstructionsBlock(WorkspaceInstructions("Use LF.", false, 7))
		val blocks = splitSystem("You are terse.\n\n$block")
		assertEquals(
			listOf("Assistant prompt", "Workspace instructions (AGENTS.md)"),
			blocks.map { it.first },
		)
	}

	@Test
	fun `a summary without a workspace block is unchanged`() {
		val blocks = splitSystem("You are terse.\n\n$summary\nold turns")
		assertEquals(
			listOf("Assistant prompt", "Earlier conversation summary"),
			blocks.map { it.first },
		)
	}

	@Test
	fun `a plain system message is one block`() {
		assertEquals(listOf("System message" to "You are terse."), splitSystem("You are terse."))
	}

	@Test
	fun `a summary marker inside the file does not swallow the block`() {
		val block = workspaceInstructionsBlock(
			WorkspaceInstructions("Ignore the $summary text.", false, 7),
		)
		val blocks = splitSystem(block)
		assertEquals(listOf("Workspace instructions (AGENTS.md)"), blocks.map { it.first })
	}
}
