package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The role-play display parser.
 *
 * Two properties matter more than the styling details: the painted spans always
 * concatenate back to the stored text (display-only means exactly that), and a
 * half-streamed reply never throws or loses characters. The convention cases
 * then pin what a scene reads as: speech, action, emphasis, scene break.
 */
class RoleplayTypographyTest {

	private fun parse(text: String, finished: Boolean = true): List<RpBlock> =
		RoleplayTypography.parse(text, finished)

	private fun textOf(blocks: List<RpBlock>): String = blocks.joinToString("") { block ->
		when (block) {
			is RpBlock.Paragraph -> block.spans.joinToString("") { it.text }
			RpBlock.SceneBreak -> ""
		}
	}

	/** What a reader sees: raw spans minus markup delimiters. */
	private fun paintedOf(blocks: List<RpBlock>): String = blocks.joinToString("") { block ->
		when (block) {
			is RpBlock.Paragraph -> block.spans.joinToString("") { it.paintedText }
			RpBlock.SceneBreak -> ""
		}
	}

	private fun paragraph(blocks: List<RpBlock>, index: Int = 0): RpBlock.Paragraph =
		blocks[index] as RpBlock.Paragraph

	private fun spans(text: String, finished: Boolean = true): List<RpSpan> =
		paragraph(parse(text, finished)).spans

	@Test
	fun `plain prose is one narration span`() {
		val body = "The keeper sets the lamp down and waits."
		assertEquals(listOf(RpSpan(body, RpInline.Plain)), spans(body))
	}

	@Test
	fun `english conventions are speech, action and emphasis`() {
		val body = "*She smiles.* \"You are late,\" she says. **Then** waits."
		assertEquals(
			listOf(
				RpSpan("*She smiles.*", RpInline.Action, RpMarkers.Asterisk),
				RpSpan(" ", RpInline.Plain),
				RpSpan("\"You are late,\"", RpInline.Dialogue),
				RpSpan(" she says. ", RpInline.Plain),
				RpSpan("**Then**", RpInline.Emphasis, RpMarkers.Bold),
				RpSpan(" waits.", RpInline.Plain),
			),
			spans(body),
		)
	}

	@Test
	fun `markup delimiters are not painted but the content is`() {
		assertEquals(
			"She smiles. \"You are late.\"",
			paintedOf(parse("*She smiles.* \"You are late.\"")),
		)
		assertEquals("Then waits.", paintedOf(parse("**Then** waits.")))
		// Parentheses and quotes are punctuation, so they stay.
		assertEquals("（她抬眼）「你来晚了。」", paintedOf(parse("（她抬眼）「你来晚了。」")))
	}

	@Test
	fun `chinese conventions are speech and action`() {
		val body = "（她抬起眼）「你来晚了。」"
		assertEquals(
			listOf(
				RpSpan("（她抬起眼）", RpInline.Action),
				RpSpan("「你来晚了。」", RpInline.Dialogue),
			),
			spans(body),
		)
	}

	@Test
	fun `typographic quotes and corner brackets both count as speech`() {
		assertEquals(
			listOf(RpSpan("“走吧”", RpInline.Dialogue)),
			spans("“走吧”"),
		)
		assertEquals(
			listOf(RpSpan("『走吧』", RpInline.Dialogue)),
			spans("『走吧』"),
		)
	}

	@Test
	fun `a quote inside an action stays part of the action`() {
		assertEquals(
			listOf(RpSpan("（她\"笑\"了）", RpInline.Action)),
			spans("（她\"笑\"了）"),
		)
	}

	@Test
	fun `adjacent runs of the same kind merge`() {
		assertEquals(
			listOf(RpSpan("*a**b*", RpInline.Action, RpMarkers.Asterisk)),
			spans("*a**b*"),
		)
	}

	@Test
	fun `a line of only a break marker becomes a scene break`() {
		val blocks = parse("First paragraph.\n\n---\n\nSecond paragraph.")
		assertEquals(3, blocks.size)
		assertEquals(RpBlock.SceneBreak, blocks[1])
		assertEquals("First paragraph.", textOf(listOf(blocks[0])))
		assertEquals("Second paragraph.", textOf(listOf(blocks[2])))
	}

	@Test
	fun `break markers must be alone on their line`() {
		val blocks = parse("He counts --- and stops.")
		assertEquals(1, blocks.size)
		assertTrue((blocks[0] as RpBlock.Paragraph).spans.all { it.inline == RpInline.Plain })
	}

	@Test
	fun `several break markers and spaces are accepted`() {
		for (marker in listOf("---", "***", "* * *", "___", "  ---  ")) {
			val blocks = parse("A\n\n$marker\n\nB")
			assertEquals("marker=$marker", RpBlock.SceneBreak, blocks[1])
		}
	}

	@Test
	fun `an unclosed quote is speech while streaming and plain when finished`() {
		val streaming = spans("\"You are late", finished = false)
		assertEquals(RpSpan("\"You are late", RpInline.Dialogue), streaming.single())

		val finished = spans("\"You are late", finished = true)
		assertEquals(RpSpan("\"You are late", RpInline.Plain), finished.single())
	}

	@Test
	fun `an unclosed parenthesis is an action while streaming and plain when finished`() {
		assertEquals(
			RpSpan("（她抬眼", RpInline.Action),
			spans("（她抬眼", finished = false).single(),
		)
		assertEquals(
			RpSpan("（她抬眼", RpInline.Plain),
			spans("（她抬眼", finished = true).single(),
		)
	}

	@Test
	fun `an unclosed asterisk is literal in both states`() {
		assertEquals(listOf(RpSpan("*she smiles", RpInline.Plain)), spans("*she smiles", finished = false))
		assertEquals(listOf(RpSpan("*she smiles", RpInline.Plain)), spans("*she smiles", finished = true))
	}

	@Test
	fun `an unfinished bold marker is literal`() {
		assertEquals(listOf(RpSpan("**very", RpInline.Plain)), spans("**very", finished = false))
	}

	@Test
	fun `the spans cover every character of the stored text`() {
		// The raw spans (markers included) concatenate back to the stored text:
		// display can drop notation, but it can never invent or lose content.
		val samples = listOf(
			"*She smiles.* \"You are late,\" she says.",
			"（她抬眼）「你来晚了。」*她等着。*",
			"\"unclosed quote and （unclosed paren",
			"**unclosed bold and *single",
			"plain",
		)
		for (sample in samples) {
			assertEquals(sample, textOf(parse(sample, finished = true)))
			assertEquals(sample, textOf(parse(sample, finished = false)))
			// Every prefix must also round-trip: while streaming, the view has
			// exactly the received characters and nothing else.
			for (length in 0..sample.length) {
				val prefix = sample.substring(0, length)
				assertEquals(prefix, textOf(parse(prefix, finished = false)))
			}
		}
	}

	@Test
	fun `blank lines separate paragraphs and nothing else is dropped`() {
		val blocks = parse("First paragraph.\n\nSecond *action* paragraph.")
		assertEquals(2, blocks.size)
		assertEquals("First paragraph.", textOf(listOf(blocks[0])))
		assertEquals("Second *action* paragraph.", textOf(listOf(blocks[1])))
	}

	@Test
	fun `paragraphs keep their line breaks`() {
		val body = "line one\nline two"
		assertEquals(body, paragraph(parse(body)).spans.joinToString("") { it.text })
	}

	@Test
	fun `blank text has no blocks`() {
		assertTrue(parse("").isEmpty())
		assertTrue(parse("   \n\n  ").isEmpty())
	}

	@Test
	fun `markdown block constructs are detected so the caller can fall back`() {
		assertFalse(RoleplayTypography.hasMarkdownBlocks("（她抬眼）「你来晚了。」\n\n*她等着。*"))
		assertFalse(RoleplayTypography.hasMarkdownBlocks("A scene with a --- break inside a paragraph."))
		assertTrue(RoleplayTypography.hasMarkdownBlocks("```\ncode\n```"))
		assertTrue(RoleplayTypography.hasMarkdownBlocks("# Heading"))
		assertTrue(RoleplayTypography.hasMarkdownBlocks("| a | b |"))
		assertTrue(RoleplayTypography.hasMarkdownBlocks("> quoted line"))
		assertTrue(RoleplayTypography.hasMarkdownBlocks("- a list item"))
	}
}
