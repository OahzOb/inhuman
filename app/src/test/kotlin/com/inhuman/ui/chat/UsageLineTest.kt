package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The usage summary must state a cache miss, not silently omit the cache.
 *
 * After a final-answer regenerate the request drops its tool definitions and
 * can legitimately report zero cached tokens. Hiding the zero made that look
 * like the cache display had broken.
 */
class UsageLineTest {

	@Test
	fun `zero cache is shown rather than omitted`() {
		val summary = summaryOf(
			UsageUi(promptTokens = 191, completionTokens = 2, cachedPromptTokens = 0),
		)
		assertEquals("2 out · 191 in · cache 0%", summary)
	}

	@Test
	fun `a cache hit is shown as a percentage`() {
		val summary = summaryOf(
			UsageUi(promptTokens = 2204, completionTokens = 13, cachedPromptTokens = 2048),
		)
		assertEquals("13 out · 2204 in · cache 93%", summary)
	}
}
