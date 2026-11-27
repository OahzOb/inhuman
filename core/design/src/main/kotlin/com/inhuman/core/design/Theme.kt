package com.inhuman.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Small named constants keep the two schemes from drifting at the one place
// where Material's defaults would reintroduce a generic purple. The slots in
// `PaletteColors` and `SurfaceColors` are the only ones the user may move;
// these stay put.
private val ColorLightTertiary = Color(0xFF94621F)
private val ColorDarkTertiaryContainer = Color(0xFF3A2A16)
private val ColorLightTertiaryContainer = Color(0xFFE8D7BD)
private val ColorDarkErrorContainer = Color(0xFF4A1E1A)
private val ColorLightErrorContainer = Color(0xFFF3D6D0)
private val ColorLightOnErrorContainer = Color(0xFF5A1D16)

private fun darkScheme(p: PaletteColors, s: SurfaceColors) = darkColorScheme(
	primary = p.darkPrimary,
	onPrimary = p.onDarkPrimary,
	primaryContainer = p.darkPrimaryContainer,
	onPrimaryContainer = s.darkEmphasis,

	secondary = p.darkSecondary,
	onSecondary = s.darkBackground,
	secondaryContainer = p.darkSecondaryContainer,
	onSecondaryContainer = s.darkEmphasis,
	tertiary = Warn,
	onTertiary = s.darkBackground,
	tertiaryContainer = ColorDarkTertiaryContainer,
	onTertiaryContainer = Warn,

	background = s.darkBackground,
	onBackground = s.darkBodyText,
	surface = s.darkSurface,
	onSurface = s.darkEmphasis,
	surfaceVariant = s.darkRaised,
	onSurfaceVariant = s.darkSecondaryText,
	surfaceTint = Color.Transparent,
	surfaceDim = s.darkBackground,
	surfaceBright = s.darkRaised,
	surfaceContainerLowest = s.darkBackground,
	surfaceContainerLow = s.darkBackground,
	surfaceContainer = s.darkSurface,
	surfaceContainerHigh = s.darkRaised,
	surfaceContainerHighest = s.darkOutline,
	// A hairline colour, not a text colour: a divider is about 1.3:1 against
	// the surface in every family. Secondary text and glyphs use
	// `onSurfaceVariant` (about 7:1). Nothing should hand `outline` to a
	// `Text(color = ...)` -- an audit found twelve places that did, and
	// placeholders at 1.3:1 read as disabled.
	outline = s.darkOutline,
	outlineVariant = s.darkOutline,
	inverseSurface = s.darkEmphasis,
	inverseOnSurface = s.darkBackground,
	inversePrimary = p.darkPrimaryContainer,

	error = Danger,
	onError = s.darkBackground,
	errorContainer = ColorDarkErrorContainer,
	onErrorContainer = Danger,
)

private fun lightScheme(p: PaletteColors, s: SurfaceColors) = lightColorScheme(
	primary = p.lightPrimary,
	onPrimary = s.lightBackground,
	primaryContainer = s.lightRaised,
	onPrimaryContainer = s.lightInk,

	secondary = p.lightSecondary,
	onSecondary = s.lightBackground,
	secondaryContainer = s.lightRaised,
	onSecondaryContainer = s.lightInk,
	tertiary = ColorLightTertiary,
	onTertiary = s.lightBackground,
	tertiaryContainer = ColorLightTertiaryContainer,
	onTertiaryContainer = s.lightInk,

	background = s.lightBackground,
	onBackground = s.lightInk,
	surface = s.lightSurface,
	onSurface = s.lightInk,
	surfaceVariant = s.lightRaised,
	onSurfaceVariant = s.lightMuted,
	surfaceTint = Color.Transparent,
	surfaceDim = s.lightSurface,
	surfaceBright = s.lightBackground,
	surfaceContainerLowest = s.lightBackground,
	surfaceContainerLow = s.lightBackground,
	surfaceContainer = s.lightSurface,
	surfaceContainerHigh = s.lightRaised,
	surfaceContainerHighest = s.lightRaised,
	// Hairline only: the raised neutral is about 1.3:1 on the page background.
	// `onSurfaceVariant` is the secondary text colour (see the dark note).
	outline = s.lightRaised,
	outlineVariant = s.lightRaised,
	inverseSurface = s.lightInk,
	inverseOnSurface = s.lightBackground,
	inversePrimary = p.darkPrimaryContainer,

	error = DangerDeep,
	onError = s.lightBackground,
	errorContainer = ColorLightErrorContainer,
	onErrorContainer = ColorLightOnErrorContainer,
)

private val InhumanShapes = Shapes(
	extraSmall = RoundedCornerShape(3.dp),
	small = RoundedCornerShape(6.dp),
	medium = RoundedCornerShape(10.dp),
	large = RoundedCornerShape(16.dp),
	extraLarge = RoundedCornerShape(22.dp),
)

/**
 * The app theme.
 *
 * [darkTheme] is the *system* value: it is a parameter rather than read
 * internally so a screenshot test or a preview can force a scheme without
 * touching the platform setting. [mode] decides whether that value is used;
 * a preview that passes `darkTheme = false` still works with the default
 * `ThemeMode.System`, while an explicit `ThemeMode.Light/Dark` ignores it.
 *
 * [palette] and [surface] both default to the original look, so an install
 * that has never chosen either looks exactly as it did before these choices
 * existed. They are independent: the accent moves the signal slots and the
 * surface family moves the neutrals.
 */
@Composable
fun InhumanTheme(
	darkTheme: Boolean = isSystemInDarkTheme(),
	palette: ThemePalette = ThemePalette.Ember,
	mode: ThemeMode = ThemeMode.System,
	surface: SurfaceFamily = SurfaceFamily.Warm,
	content: @Composable () -> Unit,
) {
	val accents = paletteColors(palette)
	val neutrals = surfaceColors(surface)
	MaterialTheme(
		colorScheme = if (mode.usesDark(darkTheme)) {
			darkScheme(accents, neutrals)
		} else {
			lightScheme(accents, neutrals)
		},
		typography = InhumanTypography,
		shapes = InhumanShapes,
		content = content,
	)
}
