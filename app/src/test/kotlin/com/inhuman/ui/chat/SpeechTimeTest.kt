package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTimeTest {

	@Test
	fun formatsMinutesAndSeconds() {
		assertEquals("0:00", SpeechTime.format(0))
		assertEquals("0:42", SpeechTime.format(42_000))
		assertEquals("1:05", SpeechTime.format(65_000))
		assertEquals("12:34", SpeechTime.format(754_000))
	}

	@Test
	fun clampsNegativeInput() {
		assertEquals("0:00", SpeechTime.format(-1))
	}
}
