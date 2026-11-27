package com.inhuman.ui.chat

import com.inhuman.core.data.db.WorkspaceKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire inspector used to know two system blocks: the assistant prompt and
 * the compaction summary. A workspace block appended after the summary made
 * the last two look like one, so the preview labelled the file's text
 * "Earlier conversation summary". A workshop-context block inserted before the
 * summary must not repeat that mistake: these cases pin the four-way split.
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
	fun `a workshop block sits between the prompt and the summary`() {
		val workshop = workshopPrompt(WorkspaceKinds.STUDIO, roleplayActive = false)
			?: error("the configuration workshop must get a block")
		val block = workspaceInstructionsBlock(WorkspaceInstructions("Use LF.", false, 7))
		val blocks = splitSystem("You are terse.\n\n$workshop\n\n$summary\nold turns\n\n$block")

		assertEquals(
			listOf(
				"Assistant prompt",
				"Workshop context",
				"Earlier conversation summary",
				"Workspace instructions (AGENTS.md)",
			),
			blocks.map { it.first },
		)
		assertEquals("You are terse.", blocks[0].second)
		assertTrue(
			"the block keeps its sentence, without the label the inspector adds",
			blocks[1].second.startsWith("this conversation runs"),
		)
		assertEquals("old turns", blocks[2].second)
		assertEquals(block, blocks[3].second)
	}

	@Test
	fun `a workshop block without a summary is its own block`() {
		val workshop = workshopPrompt(WorkspaceKinds.ROLEPLAY, roleplayActive = false)
			?: error("the RP workshop must get a block")
		val blocks = splitSystem("You are terse.\n\n$workshop")

		assertEquals(listOf("Assistant prompt", "Workshop context"), blocks.map { it.first })
		assertTrue(blocks[1].second.startsWith("this conversation runs"))
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

	@Test
	fun `a workshop marker inside the file does not become a segment`() {
		val block = workspaceInstructionsBlock(
			WorkspaceInstructions("Ignore the $WORKSHOP_PROMPT_MARKER line.", false, 7),
		)
		val blocks = splitSystem(block)
		assertEquals(listOf("Workspace instructions (AGENTS.md)"), blocks.map { it.first })
	}
}
