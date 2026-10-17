package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which section is allowed to stay open while a turn runs.
 *
 * These are the transitions the stream actually produces: reasoning first, then
 * a tool call, then the answer. The rule is checked without a device because
 * "which one is newest" is arithmetic on the state, not a layout fact.
 */
class LiveAssistantSectionTest {

	private fun live(
		streaming: Boolean = true,
		latest: Boolean = true,
		reasoning: Boolean = true,
		text: Boolean = false,
		tools: Int = 0,
		afterCalls: Boolean = false,
	) = liveAssistantSection(streaming, latest, reasoning, text, tools, afterCalls)

	@Test
	fun `reasoning is open while it is the newest thing`() {
		assertEquals(LiveAssistantSection.Reasoning, live())
	}

	@Test
	fun `a tool call supersedes reasoning`() {
		assertEquals(LiveAssistantSection.Tools, live(reasoning = true, tools = 2))
	}

	@Test
	fun `tool calls without reasoning are still the live section`() {
		assertEquals(LiveAssistantSection.Tools, live(reasoning = false, tools = 1))
	}

	@Test
	fun `a preamble before the calls does not close the tool section`() {
		// "I'll search for that" is text, but it is not the answer. Closing the
		// calls here means the section named after them is shut while they run.
		assertEquals(
			LiveAssistantSection.Tools,
			live(reasoning = true, text = true, tools = 2, afterCalls = false),
		)
	}

	@Test
	fun `the answer closes both`() {
		assertEquals(
			LiveAssistantSection.None,
			live(reasoning = true, tools = 2, text = true, afterCalls = true),
		)
	}

	@Test
	fun `an older message is never live`() {
		assertEquals(
			LiveAssistantSection.None,
			live(latest = false, reasoning = true, tools = 3),
		)
	}

	@Test
	fun `a finished turn has no live section`() {
		// Otherwise reopening an old transcript whose last message happened to
		// end in reasoning would silently expand it.
		assertEquals(
			LiveAssistantSection.None,
			live(streaming = false, reasoning = true, tools = 1),
		)
	}

	@Test
	fun `a message with no working-out has nothing to open`() {
		assertEquals(
			LiveAssistantSection.None,
			live(reasoning = false, text = true, tools = 0),
		)
	}
}
