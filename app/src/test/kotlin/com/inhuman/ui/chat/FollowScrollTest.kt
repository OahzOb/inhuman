package com.inhuman.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the transcript stops following the newest content.
 *
 * The case that matters is the one no screenshot shows: a code block wider than
 * the screen scrolls sideways, and on the 360dp emulator that drag reached the
 * parent nested-scroll connection with `y == 0`, turned following off, and put
 * the "jump to latest" button on screen for a list that had not moved.
 */
class FollowScrollTest {

	@Test
	fun `a horizontal drag does not stop following`() {
		assertFalse(detachesFollow(availableY = 0f, ours = false))
	}

	@Test
	fun `a vertical drag does stop following`() {
		assertTrue(detachesFollow(availableY = 12f, ours = false))
		assertTrue(detachesFollow(availableY = -12f, ours = false))
	}

	@Test
	fun `the app's own scroll never stops following`() {
		// SideEffect is the app scrolling itself; a programmatic jump arrives
		// through nested scroll on some Compose versions. Turning following off
		// there would undo the user's "take me to the bottom".
		assertFalse(detachesFollow(availableY = 40f, ours = true))
		assertFalse(detachesFollow(availableY = 0f, ours = true))
	}
}