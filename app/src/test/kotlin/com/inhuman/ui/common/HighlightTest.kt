package com.inhuman.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightTest {

	@Test
	fun `marks every case-insensitive occurrence`() {
		val result = highlightSnippet("Notes and notes", "NOTES")

		assertEquals(2, result.spanStyles.size)
		assertEquals(0, result.spanStyles[0].start)
		assertEquals(5, result.spanStyles[0].end)
		assertEquals(10, result.spanStyles[1].start)
		assertEquals(15, result.spanStyles[1].end)
	}

	@Test
	fun `a blank query leaves the text unstyled`() {
		val result = highlightSnippet("anything", "   ")

		assertEquals(0, result.spanStyles.size)
		assertEquals("anything", result.text)
	}

	@Test
	fun `a query that is not present leaves the text unstyled`() {
		val result = highlightSnippet("anything", "missing")

		assertEquals(0, result.spanStyles.size)
	}
}
