package com.inhuman.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout breakpoints, tested without a device.
 *
 * The bug these guard against is treating width as a proxy for the whole window:
 * a 640x360 landscape phone was classified as a tablet, got the two-pane home
 * and the tablet sheet spacing, and had almost no vertical room.
 */
class WindowFrameTest {

	@Test
	fun phonePortraitIsCompact() {
		val frame = WindowFrame(widthDp = 360, heightDp = 640)
		assertEquals(WindowWidthClass.Compact, frame.widthClass)
		assertEquals(WindowHeightClass.Medium, frame.heightClass)
		assertTrue(frame.isCompact)
		assertFalse(frame.isLandscape)
		assertFalse(frame.usesTwoPane)
		assertTrue(frame.usesFab)
		assertFalse(frame.usesHeaderOverflow)
	}

	@Test
	fun largeTextOnACompactPhoneUsesHeaderOverflow() {
		val frame = WindowFrame(widthDp = 360, heightDp = 640, fontScale = 1.5f)
		assertTrue(frame.usesHeaderOverflow)
	}

	@Test
	fun largeTextOnATabletDoesNotNeedHeaderOverflow() {
		val frame = WindowFrame(widthDp = 1024, heightDp = 768, fontScale = 1.5f)
		assertFalse(frame.usesHeaderOverflow)
	}

	@Test
	fun phoneLandscapeIsWideButShort() {
		val frame = WindowFrame(widthDp = 640, heightDp = 360)
		assertEquals(WindowWidthClass.Medium, frame.widthClass)
		assertEquals(WindowHeightClass.Compact, frame.heightClass)
		assertTrue(frame.isLandscape)
		assertTrue(frame.isCompact)
		assertFalse(frame.usesTwoPane)
		assertFalse(frame.usesFab)
	}

	@Test
	fun wideShortWindowDoesNotBecomeATablet() {
		val frame = WindowFrame(widthDp = 900, heightDp = 400)
		assertEquals(WindowWidthClass.Expanded, frame.widthClass)
		assertEquals(WindowHeightClass.Compact, frame.heightClass)
		assertTrue(frame.isCompact)
		assertFalse(frame.usesTwoPane)
	}

	@Test
	fun foldableInnerScreenIsMediumAndSinglePane() {
		val frame = WindowFrame(widthDp = 700, heightDp = 700)
		assertEquals(WindowWidthClass.Medium, frame.widthClass)
		assertEquals(WindowHeightClass.Medium, frame.heightClass)
		assertFalse(frame.isCompact)
		assertFalse(frame.usesTwoPane)
		assertFalse(frame.usesFab)
	}

	@Test
	fun fourByThreeTabletUsesTwoPanes() {
		val frame = WindowFrame(widthDp = 1024, heightDp = 768)
		assertEquals(WindowWidthClass.Expanded, frame.widthClass)
		assertEquals(WindowHeightClass.Medium, frame.heightClass)
		assertTrue(frame.usesTwoPane)
		assertFalse(frame.usesFab)
	}

	@Test
	fun tabletWithLargeHeightUsesTwoPanes() {
		val frame = WindowFrame(widthDp = 1280, heightDp = 900)
		assertEquals(WindowHeightClass.Expanded, frame.heightClass)
		assertTrue(frame.usesTwoPane)
	}

	@Test
	fun formFactorRemainsWidthOnlyForCompatibleCallers() {
		assertEquals(FormFactor.Medium, WindowFrame(640, 360).formFactor)
		assertEquals(FormFactor.Expanded, WindowFrame(900, 400).formFactor)
	}
}
