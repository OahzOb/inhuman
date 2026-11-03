package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow-up sanitiser and the tool result's shape.
 *
 * Both paths into this file are model output -- a text answer from the
 * suggestion model and the arguments of `emit_suggestions` -- and both can
 * carry bullets, numbering, duplicates or more lines than the prompt asked for.
 * These are the rules the UI and the transcript both rely on.
 */
class AdviceTest {

	@Test
	fun `bullets, numbering and blanks are stripped`() {
		assertEquals(
			listOf("Continue", "Explain it", "Try another"),
			Advice.sanitize(
				sequenceOf("- Continue", "", "2. Explain it", "* Try another", "   "),
			),
		)
	}

	@Test
	fun `suggestions are capped and de-duplicated`() {
		assertEquals(
			listOf("one", "two", "three"),
			Advice.sanitize(sequenceOf("one", "two", "one", "three", "four")),
		)
	}

	@Test
	fun `a long line is truncated rather than dropped`() {
		val long = "x".repeat(120)

		assertEquals(80, Advice.sanitize(sequenceOf(long)).single().length)
	}

	@Test
	fun `the tool result round-trips through json`() {
		val items = listOf("Continue", "Explain the second point")

		assertEquals(items, Advice.fromJson(Advice.toJson(items)))
	}

	@Test
	fun `json is sanitised the same way as text`() {
		val json = """{"suggestions":["- one","2. two","one","three","four"]}"""

		assertEquals(listOf("one", "two", "three"), Advice.fromJson(json))
	}

	@Test
	fun `malformed json is no suggestions, not a failure`() {
		// The turn has already been answered. A throw here would turn a
		// cosmetic miss into a failed tool call in the transcript.
		assertTrue(Advice.fromJson("not json").isEmpty())
		assertTrue(Advice.fromJson("""{"suggestions":"Continue"}""").isEmpty())
		assertTrue(Advice.fromJson("""{"nope":[]}""").isEmpty())
	}

	@Test
	fun `an empty list survives the round trip`() {
		val json = Advice.toJson(emptyList())

		assertTrue(Advice.fromJson(json).isEmpty())
	}
}
