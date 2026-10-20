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

	/**
	 * The chat column.
	 *
	 * Wider than prose because a reply contains code and tables, but not the
	 * full tablet width: at 1280dp a paragraph set to the edge is a wall. The
	 * composer and the transcript share this axis so they do not look like two
	 * unrelated columns.
	 */
	val ChatMeasure: Dp = 900.dp

	/** A user's own message: a short remark, not a document. */
	val UserBubbleMeasure: Dp = 560.dp

	/** Gutter for phone-width layouts. */
	val GutterCompact: Dp = 20.dp

	/** Gutter once there is room to breathe. */
	val GutterWide: Dp = 32.dp

	/**
	 * Vertical rhythm, in one place.
	 *
	 * These were literals scattered across three screens, which is how the
	 * transcript ended up spending more vertical space on the gaps between
	 * things than on the things. Measured on a phone: a one-line collapsed tool
	 * result stood 53dp tall with an 18dp gap to the next, so a turn with eight
	 * searches put sixteen rows on screen of which most were margins.
	 *
	 * Naming them puts the trade in one file instead of eight.
	 */
	object Space {
		/**
		 * Between separate messages.
		 *
		 * The largest gap in the transcript, and it should be: a new message is
		 * a new thought. It stays clearly above the others so that grouping
		 * *inside* a message reads as grouping.
		 */
		val BetweenMessages: Dp = 10.dp

		/** Between blocks inside one message. */
		val InMessage: Dp = 6.dp

		/**
		 * Between consecutive collapsed rows.
		 *
		 * Smaller than [InMessage] on purpose. A stack of collapsed tool results
		 * is one list of things that happened, not several thoughts, and spacing
		 * them like separate thoughts is what made a search-heavy turn read as
		 * mostly empty page.
		 */
		val BetweenCollapsed: Dp = 4.dp

		/**
		 * Inside a collapsed header, above and below its label.
		 *
		 * Half the 20dp Material's minimum touch target would imply, because the
		 * row already exceeds that: a 14sp label plus this is past 40dp. Adding
		 * the full 20dp on each side is what made a one-line summary 53dp tall.
		 */
		val CollapsedPadding: Dp = 5.dp
	}
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
