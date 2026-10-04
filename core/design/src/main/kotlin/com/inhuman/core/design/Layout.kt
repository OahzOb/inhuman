package com.inhuman.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The few form-factor questions this app actually asks.
 *
 * Deliberately not `material3-window-size-class`: that library wants a whole
 * activity-level setup and reports a class, while everything here needs is a
 * number and a boolean. Reading `screenWidthDp` and comparing is less machinery
 * for the same answer, and it keeps `core:design` free of another dependency.
 *
 * The breakpoints are the long-standing Android ones: 600dp is where a device
 * stops being a phone in the layout sense regardless of what it is marketed as.
 */
enum class FormFactor {
	/** A phone in portrait: narrow enough that long paragraphs hurt. */
	Compact,

	/** A small tablet, a phone in landscape, or a foldable's inner screen. */
	Medium,

	/** A large tablet. */
	Expanded,
}

object Layout {

	/**
	 * Longest comfortable line of running text, in dp.
	 *
	 * Characters per line is what governs reading comfort, and it is a function
	 * of *width*, not of screen size. On a 1280dp tablet a paragraph set to the
	 * full width is roughly 150 characters per line, which is genuinely hard to
	 * read; capping the measure is what makes the tablet layout work rather
	 * than merely fill the screen.
	 */
	val ReadableMeasure: Dp = 640.dp

	/** Gutter for phone-width layouts. */
	val GutterCompact: Dp = 20.dp

	/** Gutter once there is room to breathe. */
	val GutterWide: Dp = 32.dp
}

/**
 * The current form factor, derived from the window width in dp.
 *
 * Uses the *window* width from LocalConfiguration rather than the physical
 * display, so a foldable or a multi-window split reports the size it actually
 * has. Reading the display metrics instead is the classic bug where a
 * split-screen app lays itself out for a full screen it does not occupy.
 */
@Composable
@ReadOnlyComposable
fun currentFormFactor(): FormFactor =
	when (LocalConfiguration.current.screenWidthDp) {
		in 0..599 -> FormFactor.Compact
		in 600..839 -> FormFactor.Medium
		else -> FormFactor.Expanded
	}

/**
 * A comfortable horizontal gutter for the current window width.
 *
 * On a phone 32dp of gutter out of 411dp is 8% of the screen spent on nothing.
 */
@Composable
@ReadOnlyComposable
fun comfortableGutter(): Dp =
	if (currentFormFactor() == FormFactor.Compact) Layout.GutterCompact else Layout.GutterWide

/**
 * Whether a floating action button is the right affordance.
 *
 * On a phone a top-bar action sits in the hardest place to reach one-handed.
 * On a tablet the top bar is fine and a FAB would be redundant, so the two
 * form factors get different answers rather than the same one scaled.
 */
@Composable
@ReadOnlyComposable
fun prefersFabForPrimaryAction(): Boolean =
	currentFormFactor() == FormFactor.Compact
