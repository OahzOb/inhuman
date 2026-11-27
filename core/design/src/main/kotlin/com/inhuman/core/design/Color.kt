package com.inhuman.core.design

import androidx.compose.ui.graphics.Color

/**
 * A deliberately small palette.
 *
 * A chat app is mostly text on a quiet background, so the accent is spent in
 * very few places: the primary action, a selected row, the send control, and a
 * live status dot. The neutrals are warm rather than blue-grey; blue-grey reads
 * as a stock developer tool, while a warm graphite/paper pair gives the same
 * restraint a little more character.
 *
 * The semantic colours (warn/danger/ok) are shared by everything.
 * [PaletteColors] moves the signal colours; [SurfaceColors] moves the neutral
 * graphite/paper pair underneath them. Both are opt-in, and the original warm
 * pair is the default, so an install that has never touched Settings looks
 * exactly as it did before either choice existed. Each slot of each family is
 * checked against the same contrast contract in `PaletteContrastTest`.
 */

// Dark scheme -- the working default. Graphite with a warm bone foreground.
val Ink900 = Color(0xFF0E0F0E)   // app background
val Ink850 = Color(0xFF151715)   // surface
val Ink800 = Color(0xFF1D201D)   // raised surface
val Ink700 = Color(0xFF30342E)   // dividers, inactive outlines
val Ink500 = Color(0xFF777B70)   // disabled text
val Ink300 = Color(0xFFA9AB9F)   // secondary text
val Ink200 = Color(0xFFC9C6BA)   // body text
val Ink50 = Color(0xFFF2EEE3)    // emphasis

// Signal accent. Burnt orange, not a generic blue. The light scheme uses a
// deeper step so links and the primary button still pass a contrast check on
// warm paper.
val Accent = Color(0xFFFF875B)
val AccentDeep = Color(0xFFB74720)
val AccentDim = Color(0xFF4A2418)

// Semantic, for tool results and errors. These do not move with the accent.
val Warn = Color(0xFFE3B567)
val Danger = Color(0xFFF27B6B)
val DangerDeep = Color(0xFFA83226)
val Ok = Color(0xFFA8C98B)

// Light scheme -- warm paper, ink, and the same signal accent.
val Paper100 = Color(0xFFF3EFE5)   // app background
val Paper200 = Color(0xFFEAE4D8)   // surface
val Paper300 = Color(0xFFD8CFBF)   // raised surface / outline
val PaperInk = Color(0xFF1B1A17)
// Secondary text on Paper100 *and* Paper200. The old value (0xFF6F685D) was
// 4.35:1 on the dialog surface -- below AA for body text -- while passing on
// the page background; every dialog uses the surface. This step is ~5.8:1.
val PaperMuted = Color(0xFF5E574D)

/**
 * The neutral pair a surface family supplies: what "graphite" and "paper"
 * mean for that family.
 *
 * These are the same Material roles [Theme.kt] always filled by hand rather
 * than taking from Material defaults -- defaults reintroduced a lavender chip
 * and a purple dialog. Changing a family changes all of them together, so a
 * text colour can never end up paired with a surface from another family.
 */
data class SurfaceColors(
	val darkBackground: Color,
	val darkSurface: Color,
	val darkRaised: Color,
	val darkOutline: Color,
	val darkDisabled: Color,
	val darkSecondaryText: Color,
	val darkBodyText: Color,
	val darkEmphasis: Color,
	val lightBackground: Color,
	val lightSurface: Color,
	val lightRaised: Color,
	val lightInk: Color,
	val lightMuted: Color,
)

private val WarmSurface = SurfaceColors(
	darkBackground = Ink900,
	darkSurface = Ink850,
	darkRaised = Ink800,
	darkOutline = Ink700,
	darkDisabled = Ink500,
	darkSecondaryText = Ink300,
	darkBodyText = Ink200,
	darkEmphasis = Ink50,
	lightBackground = Paper100,
	lightSurface = Paper200,
	lightRaised = Paper300,
	lightInk = PaperInk,
	lightMuted = PaperMuted,
)

// Cool graphite and cool paper. The neutrals are blue-grey here; the signal
// accents stay exactly the same, so this is a change of ground, not of voice.
private val SlateSurface = SurfaceColors(
	darkBackground = Color(0xFF0D1116),
	darkSurface = Color(0xFF151B22),
	darkRaised = Color(0xFF1E2630),
	darkOutline = Color(0xFF303B47),
	darkDisabled = Color(0xFF7B8794),
	darkSecondaryText = Color(0xFFA8B4C2),
	darkBodyText = Color(0xFFC6D0DA),
	darkEmphasis = Color(0xFFEFF3F7),
	lightBackground = Color(0xFFF0F3F6),
	lightSurface = Color(0xFFE5EBF1),
	lightRaised = Color(0xFFD3DDE7),
	lightInk = Color(0xFF151D26),
	lightMuted = Color(0xFF536173),
)

// Brown ink and parchment. Distinct from the original warm pair, whose dark
// neutral is slightly green-grey rather than brown; both stay quiet enough to
// keep body text as the loudest thing on screen.
private val SepiaSurface = SurfaceColors(
	darkBackground = Color(0xFF15110C),
	darkSurface = Color(0xFF1E1812),
	darkRaised = Color(0xFF292119),
	darkOutline = Color(0xFF3A3025),
	darkDisabled = Color(0xFF8A7E6C),
	darkSecondaryText = Color(0xFFB8AB96),
	darkBodyText = Color(0xFFD6C9B4),
	darkEmphasis = Color(0xFFF7F0E2),
	lightBackground = Color(0xFFF7F1E4),
	lightSurface = Color(0xFFEFE6D4),
	lightRaised = Color(0xFFDED1BB),
	lightInk = Color(0xFF211B13),
	lightMuted = Color(0xFF645A49),
)

fun surfaceColors(family: SurfaceFamily): SurfaceColors = when (family) {
	SurfaceFamily.Warm -> WarmSurface
	SurfaceFamily.Slate -> SlateSurface
	SurfaceFamily.Sepia -> SepiaSurface
}

// The small set of slots a theme palette changes. Everything else comes from
// the surface family, which is what keeps the accents siblings rather than
// five different visual languages.
data class PaletteColors(
	val darkPrimary: Color,
	val lightPrimary: Color,
	val onDarkPrimary: Color,
	val darkPrimaryContainer: Color,
	val darkSecondary: Color,
	val lightSecondary: Color,
	val darkSecondaryContainer: Color,
)

private val OnDarkPrimary = Color(0xFF24110A)
private val SecondaryLightTeal = Color(0xFF3E665C)

private val EmberColors = PaletteColors(
	darkPrimary = Accent,
	lightPrimary = AccentDeep,
	onDarkPrimary = OnDarkPrimary,
	darkPrimaryContainer = AccentDim,
	darkSecondary = Ok,
	lightSecondary = SecondaryLightTeal,
	// The original dark scheme used the accent dim for the secondary container.
	// Keeping it is what makes choosing Ember a no-op for existing installs.
	darkSecondaryContainer = AccentDim,
)

private val MossColors = PaletteColors(
	darkPrimary = Color(0xFF7FAE9C),
	lightPrimary = Color(0xFF2F6A5D),
	onDarkPrimary = Color(0xFF0A1E18),
	darkPrimaryContainer = Color(0xFF16352E),
	// Moss must not use Ok as its secondary: two greens for different meanings
	// would make the status dot ambiguous. The muted blue is distinct.
	darkSecondary = Color(0xFF8FA9C4),
	lightSecondary = Color(0xFF3E5C78),
	darkSecondaryContainer = Color(0xFF1E3540),
)

private val IndigoColors = PaletteColors(
	darkPrimary = Color(0xFF93A9DB),
	lightPrimary = Color(0xFF3F5B8C),
	onDarkPrimary = Color(0xFF0B1426),
	darkPrimaryContainer = Color(0xFF1D2B49),
	darkSecondary = Ok,
	lightSecondary = SecondaryLightTeal,
	darkSecondaryContainer = Color(0xFF1D3328),
)

private val PlumColors = PaletteColors(
	darkPrimary = Color(0xFFC3A4CE),
	lightPrimary = Color(0xFF6E4C86),
	onDarkPrimary = Color(0xFF1B1024),
	darkPrimaryContainer = Color(0xFF33203F),
	darkSecondary = Ok,
	lightSecondary = SecondaryLightTeal,
	darkSecondaryContainer = Color(0xFF1D3328),
)

fun paletteColors(palette: ThemePalette): PaletteColors = when (palette) {
	ThemePalette.Ember -> EmberColors
	ThemePalette.Moss -> MossColors
	ThemePalette.Indigo -> IndigoColors
	ThemePalette.Plum -> PlumColors
}
