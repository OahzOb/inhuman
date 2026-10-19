package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "did the model leave a fence open" check behind the reply banner.
 *
 * These are the shapes actually seen in transcripts: a normal pair, a reply that
 * forgets the close, an info string, a longer closing fence, and a fence-looking
 * line inside a block that must not close it.
 */
class MarkdownFenceTest {

	@Test
	fun `a paired fence is closed`() {
		assertNull(openFence("before\n```\ncode\n```\nafter"))
	}

	@Test
	fun `an unclosed fence keeps its marker`() {
		assertEquals("```", openFence("before\n```kotlin\nval x = 1\n")?.marker)
		assertEquals("~~~", openFence("before\n~~~\ncode\n")?.marker)
	}

	@Test
	fun `a line inside the block is not a close`() {
		val text = "```\ncode\n```this is still code\nstill code\n```\nafter"
		assertNull(openFence(text))
	}

	@Test
	fun `a longer fence can close a shorter one`() {
		assertNull(openFence("```\ncode\n````\n"))
	}

	@Test
	fun `an unclosed fence survives a different marker inside it`() {
		assertEquals("```", openFence("```\ncode\n~~~\nstill code\n")?.marker)
	}

	@Test
	fun `removing the stray opening lets the rest render`() {
		val text = "before\n\n```\nval x = 1\n\n## A heading\n\n| A | B |\n"
		val repaired = requireNotNull(withoutOpenFence(text))
		assertTrue(repaired.contains("## A heading"))
		assertTrue(repaired.contains("| A | B |"))
		assertFalse(repaired.contains("```"))
	}
}
