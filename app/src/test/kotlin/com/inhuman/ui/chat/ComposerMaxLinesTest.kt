package com.inhuman.ui.chat

import com.inhuman.core.design.WindowFrame
import com.inhuman.core.design.WindowHeightClass
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerMaxLinesTest {

	@Test
	fun `the matrix landscape phone gets a two-line field`() {
		// 640x360 is the ratio the cap exists for: the shortest window the
		// project verifies, and the one where a six-line draft pushed the
		// action row off-screen.
		val frame = WindowFrame(widthDp = 640, heightDp = 360)
		assertEquals(WindowHeightClass.Compact, frame.heightClass)
		assertEquals(2, composerMaxLines(frame.heightClass))
	}

	@Test
	fun `a square foldable stays at the six-line field`() {
		// 700x700 is Medium height: single-pane, but tall enough for the
		// composer's full draft, so the cap must not bind here.
		val frame = WindowFrame(widthDp = 700, heightDp = 700)
		assertEquals(WindowHeightClass.Medium, frame.heightClass)
		assertEquals(6, composerMaxLines(frame.heightClass))
	}

	@Test
	fun `both phone portrait ratios keep the six-line field`() {
		for (height in listOf(640, 800)) {
			val frame = WindowFrame(widthDp = 360, heightDp = height)
			assertEquals(
				"360x$height should keep the full draft",
				6,
				composerMaxLines(frame.heightClass),
			)
		}
	}

	@Test
	fun `tablet windows keep the six-line field`() {
		for ((w, h) in listOf(1024 to 768, 1280 to 800)) {
			val frame = WindowFrame(widthDp = w, heightDp = h)
			assertEquals("${w}x$h should keep the full draft", 6, composerMaxLines(frame.heightClass))
		}
	}

	@Test
	fun `short landscape is the only matrix cell the cap binds`() {
		// The matrix from AGENTS.md, one line: which windows are capped.
		val capped = listOf(640 to 360)
		val uncapped = listOf(360 to 640, 360 to 800, 700 to 700, 1024 to 768, 1280 to 800)
		for ((w, h) in capped) {
			assertEquals(
				"$w x $h must be capped",
				2,
				composerMaxLines(WindowFrame(widthDp = w, heightDp = h).heightClass),
			)
		}
		for ((w, h) in uncapped) {
			assertEquals(
				"$w x $h must not be capped",
				6,
				composerMaxLines(WindowFrame(widthDp = w, heightDp = h).heightClass),
			)
		}
	}
}
