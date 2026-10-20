package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The context arithmetic, where the wrong answer is invisible.
 *
 * A gauge that is off by a percent is not noticed; a gauge that invents a
 * number is worse than no gauge, because it is trusted. So the cases asserted
 * here are the ones where a plausible implementation quietly lies: no window
 * reported, a window with nothing measured, and the two ends of the scale.
 */
class ContextUsageTest {

	@Test
	fun `a window with no measurement is known but not computable`() {
		val usage = ContextUsage(usedTokens = null, contextWindow = 1_048_576)

		assertTrue("the window alone is worth showing", usage.isKnown)
		assertNull(usage.fraction)
		assertNull(usage.percent)
		assertNull("nothing to subtract from", usage.remainingTokens)
		assertNull(usage.summary)
	}

	@Test
	fun `no window reported means no fraction, whatever was measured`() {
		// A provider that does not report a window must not be given a
		// plausible-looking denominator: 400K of an invented 1M is 40%, and
		// that number would be acted on.
		val usage = ContextUsage(usedTokens = 412_000, contextWindow = null)

		assertTrue(usage.isKnown)
		assertNull(usage.fraction)
		assertNull(usage.percent)
		assertEquals("412K", usage.summary)
	}

	@Test
	fun `nothing known at all is not drawn`() {
		assertFalse(ContextUsage(null, null).isKnown)
	}

	@Test
	fun `the ordinary case divides`() {
		val usage = ContextUsage(usedTokens = 262_144, contextWindow = 1_048_576)

		assertEquals(0.25f, usage.fraction!!, 0.0001f)
		assertEquals(25, usage.percent)
		assertEquals(786_432L, usage.remainingTokens)
		assertEquals("25% · 262K / 1M", usage.summary)
	}

	@Test
	fun `a non-zero amount never reads as zero percent`() {
		// One token in a million is 0.0001%, which rounds to 0 and makes the
		// gauge look broken on the very turn it first has anything to say.
		assertEquals(1, ContextUsage(usedTokens = 1, contextWindow = 1_048_576).percent)
		assertEquals(1, ContextUsage(usedTokens = 5_000, contextWindow = 1_048_576).percent)
	}

	@Test
	fun `going over the window reports one hundred percent, not more`() {
		val over = ContextUsage(usedTokens = 1_200_000, contextWindow = 1_048_576)

		assertEquals(100, over.percent)
		assertEquals(1.0f, over.fraction!!, 0.0001f)
		assertEquals("stops at zero rather than going negative", 0L, over.remainingTokens)
	}

	@Test
	fun `a zero window is treated as no window`() {
		// Would otherwise divide by zero, and a model that reports 0 is
		// reporting nothing.
		val usage = ContextUsage(usedTokens = 100, contextWindow = 0)

		assertNull(usage.percent)
		assertNull(usage.fraction)
	}

	@Test
	fun `nothing used yet is zero percent`() {
		val usage = ContextUsage(usedTokens = 0, contextWindow = 1_048_576)

		assertEquals(0, usage.percent)
		assertEquals(0.0f, usage.fraction!!, 0.0001f)
	}

	@Test
	fun `exact counts are grouped by three`() {
		assertEquals("0", TokenFormat.exact(0))
		assertEquals("998", TokenFormat.exact(998))
		assertEquals("1,000", TokenFormat.exact(1_000))
		assertEquals("421,338", TokenFormat.exact(421_338))
		assertEquals("1,048,576", TokenFormat.exact(1_048_576))
	}

	@Test
	fun `compact counts switch unit at ten`() {
		assertEquals("998", TokenFormat.compact(998))
		assertEquals("1K", TokenFormat.compact(1_000))
		assertEquals("1.2K", TokenFormat.compact(1_240))
		assertEquals("9.9K", TokenFormat.compact(9_940))
		assertEquals("10K", TokenFormat.compact(9_960))
		assertEquals("412K", TokenFormat.compact(412_000))
		assertEquals("1M", TokenFormat.compact(1_048_576))
		assertEquals("1.2M", TokenFormat.compact(1_200_000))
		assertEquals("12M", TokenFormat.compact(12_400_000))
	}

	@Test
	fun `compact rounds half up`() {
		// 1,250 is exactly 1.25K, and 1.3 is the answer that does not
		// under-report how full a window is. Written down because the first
		// version of this assertion said 1.2 and was simply wrong about
		// arithmetic rather than about the code.
		assertEquals("1.3K", TokenFormat.compact(1_250))
		assertEquals("1.3K", TokenFormat.compact(1_251))
		assertEquals("1.2K", TokenFormat.compact(1_249))
	}

	@Test
	fun `compact never carries a comma into a token count`() {
		// A count is a list of digits; "1,2M" is a different number to most of
		// the people who would read it.
		(0L..2_000_000L step 997).forEach { n ->
			assertFalse("comma in ${TokenFormat.compact(n)}", TokenFormat.compact(n).contains(','))
		}
	}
}
