package com.inhuman.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A user preference, not a device classification.
 *
 * Auto follows the window. Compact forces the single-column shell even on a
 * tablet; Comfortable keeps the wide shell and larger gutters. It is a fallback
 * for taste or accessibility, not a request for the user to identify their
 * screen ratio.
 */
enum class LayoutPreference {
	Auto,
	Compact,
	Comfortable,
	;

	companion object {
		fun fromStored(value: String?): LayoutPreference =
			entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Auto
	}
}

/** Supplied once at the root; `currentWindowFrame()` reads it. */
val LocalLayoutPreference = staticCompositionLocalOf { LayoutPreference.Auto }

/**
 * The two dimensions a layout actually has to reason about.
 *
 * Width alone is not enough. A 16:9 phone in landscape is 640dp wide but only
 * 360dp tall: it crosses the width breakpoint for a tablet while having less
 * vertical room than a phone portrait. Treating it as a tablet produced a
 * two-pane rail with almost no room for either pane.
 *
 * Height uses the same 480/900dp boundaries as the width classes, so a layout
 * can say "wide but short" without inventing device names. Both values come from
 * `LocalConfiguration`, which describes the current *window* (split-screen and
 * foldables included), not the physical display.
 */
enum class WindowWidthClass { Compact, Medium, Expanded }

enum class WindowHeightClass { Compact, Medium, Expanded }

/**
 * A snapshot of the window constraints, independent of Compose.
 *
 * Keeping the classification pure makes the breakpoints testable without a
 * device; [currentWindowFrame] is the only Compose-aware entry point.
 */
data class WindowFrame(
	val widthDp: Int,
	val heightDp: Int,
	val fontScale: Float = 1f,
	val preference: LayoutPreference = LayoutPreference.Auto,
) {
	val widthClass: WindowWidthClass = when {
		widthDp < 600 -> WindowWidthClass.Compact
		widthDp < 840 -> WindowWidthClass.Medium
		else -> WindowWidthClass.Expanded
	}

	val heightClass: WindowHeightClass = when {
		heightDp < 480 -> WindowHeightClass.Compact
		heightDp < 900 -> WindowHeightClass.Medium
		else -> WindowHeightClass.Expanded
	}

	/** True when either axis is phone-sized; short landscape belongs here too. */
	val isCompact: Boolean = when (preference) {
		LayoutPreference.Compact -> true
		LayoutPreference.Comfortable -> false
		LayoutPreference.Auto ->
			widthClass == WindowWidthClass.Compact || heightClass == WindowHeightClass.Compact
	}

	val isLandscape: Boolean = widthDp > heightDp

	val aspectRatio: Float = if (heightDp <= 0) 0f else widthDp.toFloat() / heightDp

	/**
	 * A two-pane home needs width for the rail *and* height for the list.
	 *
	 * The previous rule was `width >= 600`, which made a 640x360 landscape phone
	 * look like a tablet. Requiring an Expanded width and at least Medium height
	 * keeps the rail for real tablets and large foldables, while leaving a wide,
	 * short window as one scrollable column. The manual Compact mode suppresses
	 * it even on a tablet.
	 */
	val usesTwoPane: Boolean = when (preference) {
		LayoutPreference.Compact -> false
		LayoutPreference.Comfortable -> widthClass == WindowWidthClass.Expanded
		LayoutPreference.Auto ->
			widthClass == WindowWidthClass.Expanded && heightClass != WindowHeightClass.Compact
	}

	/** A FAB only on a genuinely narrow window; medium gets a header action. */
	val usesFab: Boolean =
		widthClass == WindowWidthClass.Compact && preference != LayoutPreference.Comfortable

	/**
	 * Compact window with large text cannot fit three destinations beside the
	 * wordmark; collapse them into one menu rather than clipping Settings.
	 */
	val usesHeaderOverflow: Boolean =
		widthClass == WindowWidthClass.Compact && fontScale > 1.2f

	/** Width-only compatibility for callers that genuinely only care about width. */
	val formFactor: FormFactor = when (widthClass) {
		WindowWidthClass.Compact -> FormFactor.Compact
		WindowWidthClass.Medium -> FormFactor.Medium
		WindowWidthClass.Expanded -> FormFactor.Expanded
	}
}

@Composable
@ReadOnlyComposable
fun currentWindowFrame(): WindowFrame {
	val configuration = LocalConfiguration.current
	return WindowFrame(
		widthDp = configuration.screenWidthDp,
		heightDp = configuration.screenHeightDp,
		fontScale = configuration.fontScale,
		preference = LocalLayoutPreference.current,
	)
}

/**
 * The few window questions this app actually asks.
 *
 * Deliberately not `material3-window-size-class`: that library wants a whole
 * activity-level setup and reports a class, while this app needs width, height
 * and a handful of derived booleans. The important part is that **width alone is
 * not a layout**: a 16:9 phone in landscape is 640dp wide but only 360dp tall,
 * and a square foldable is 700dp on both axes. Both must stay single-pane even
 * though their widths cross the old 600dp threshold.
 *
 * Values come from `LocalConfiguration`, which describes the current *window*
 * (split-screen and foldables included), not the physical display.
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
 * The current width-only form factor.
 *
 * Kept for callers that only need horizontal density; new layout decisions
 * should use [currentWindowFrame], because a short landscape window is not a
 * tablet even when its width says so.
 */
@Composable
@ReadOnlyComposable
fun currentFormFactor(): FormFactor = currentWindowFrame().formFactor

/**
 * A comfortable horizontal gutter for the current window.
 *
 * A short window also gets the compact gutter: on a 16:9 phone in landscape the
 * extra 12dp per side comes directly out of the vertical space the content
 * needs.
 */
@Composable
@ReadOnlyComposable
fun comfortableGutter(): Dp =
	if (currentWindowFrame().isCompact) Layout.GutterCompact else Layout.GutterWide

/**
 * Whether a floating action button is the right affordance.
 *
 * Only a compact-width window uses the FAB. Medium single-pane windows
 * (landscape phones, square foldables) are too short for a floating button to
 * hover over a list without covering a row, so they keep Create in the header.
 */
@Composable
@ReadOnlyComposable
fun prefersFabForPrimaryAction(): Boolean = currentWindowFrame().usesFab
