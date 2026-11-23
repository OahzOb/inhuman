package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caption timeline.
 *
 * The notification cannot be sampled at every second from a test, so the pure
 * properties are pinned here: every caption covers a non-empty slice, the
 * slices are contiguous, the last one ends at the duration, and the caption at
 * a position is the one that actually covers it.
 */
class SpeechCaptionsTest {

	@Test
	fun distributesDurationOverSentences() {
		val segments = SpeechCaptions.segments(
			"第一句。第二句比较长一些！Third sentence?",
			9_000,
		)

		assertEquals(3, segments.size)
		assertEquals(0, segments.first().startMs)
		assertEquals(9_000, segments.last().endMs)
		assertEquals(segments[0].endMs, segments[1].startMs)
		assertEquals(segments[1].endMs, segments[2].startMs)
		assertTrue(segments.all { it.endMs > it.startMs })
	}

	@Test
	fun segmentAtPicksTheCoveringSlice() {
		val segments = SpeechCaptions.segments("一。二。三。", 3_000)
		assertEquals("一。", SpeechCaptions.segmentAt(segments, 0)?.text)
		assertEquals("二。", SpeechCaptions.segmentAt(segments, 1_100)?.text)
		assertEquals("三。", SpeechCaptions.segmentAt(segments, 2_999)?.text)
	}

	@Test
	fun longSentenceIsCutIntoReadableCaptions() {
		val long = "这是一句非常长的没有标点的话".repeat(20)
		val segments = SpeechCaptions.segments(long, 20_000)
		assertTrue(segments.size > 1)
		assertTrue(segments.all { it.text.length <= 90 })
	}

	@Test
	fun blankTextOrZeroDurationHasNoCaptions() {
		assertTrue(SpeechCaptions.segments("   \n ", 1_000).isEmpty())
		assertTrue(SpeechCaptions.segments("你好。", 0).isEmpty())
		assertTrue(SpeechCaptions.segmentAt(emptyList(), 0) == null)
	}

	@Test
	fun lastCaptionIsHeldAtTheEnd() {
		val segments = SpeechCaptions.segments("只有一句。", 2_000)
		assertNotNull(SpeechCaptions.segmentAt(segments, 99_000))
		assertEquals("只有一句。", SpeechCaptions.segmentAt(segments, 99_000)?.text)
	}
}
