package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advice block's parser and its streaming gate.
 *
 * The block is the only structure in the answer that the user is not supposed
 * to see arrive, so both halves are pinned here: a complete block is removed and
 * read, a partial marker is held back instead of flickering, and a cut-off
 * block loses the suggestions rather than half-parsing them.
 */
class AdviceTest {

	@Test
	fun `an answer with no block is left alone`() {
		val result = Advice.parse("Just an answer.\n")

		assertEquals("Just an answer.", result.visibleText)
		assertTrue(result.suggestions.isEmpty())
	}

	@Test
	fun `a complete block becomes suggestions and leaves the answer`() {
		val result = Advice.parse(
			"The answer.\n\n" +
				"$ADVICE_OPEN\nContinue\nExplain the second point\n$ADVICE_CLOSE\n",
		)

		assertEquals("The answer.", result.visibleText)
		assertEquals(listOf("Continue", "Explain the second point"), result.suggestions)
	}

	@Test
	fun `the parser tolerates bullets, numbering and blanks`() {
		val result = Advice.parse(
			"Answer\n$ADVICE_OPEN\n- Continue\n\n2. Explain it\n* Try another\n$ADVICE_CLOSE",
		)

		assertEquals(listOf("Continue", "Explain it", "Try another"), result.suggestions)
	}

	@Test
	fun `suggestions are capped and deduplicated`() {
		val result = Advice.parse(
			"Answer\n$ADVICE_OPEN\none\ntwo\none\nthree\nfour\n$ADVICE_CLOSE",
		)

		assertEquals(listOf("one", "two", "three"), result.suggestions)
	}

	@Test
	fun `an unclosed block hides itself and yields no suggestions`() {
		val result = Advice.parse("Answer\n$ADVICE_OPEN\nContinue")

		assertEquals("Answer", result.visibleText)
		assertTrue(result.suggestions.isEmpty())
	}

	@Test
	fun `text after the block is preserved`() {
		val result = Advice.parse(
			"Answer\n$ADVICE_OPEN\nContinue\n$ADVICE_CLOSE\n\nOne more note.",
		)

		assertEquals("Answer\n\nOne more note.", result.visibleText)
		assertEquals(listOf("Continue"), result.suggestions)
	}

	@Test
	fun `the streaming view hides a complete marker`() {
		assertEquals("Answer\n", Advice.visible("Answer\n$ADVICE_OPEN\nContinue"))
	}

	@Test
	fun `the streaming view holds back a partial marker`() {
		assertEquals("Answer", Advice.visible("Answer<<<sug"))
		assertEquals("Answer", Advice.visible("Answer<"))
	}

	@Test
	fun `a partial marker that turns out to be text is released`() {
		assertEquals("Answer<x", Advice.visible("Answer<x"))
	}

	@Test
	fun `ordinary text is unchanged`() {
		assertEquals("a < b", Advice.visible("a < b"))
	}
}