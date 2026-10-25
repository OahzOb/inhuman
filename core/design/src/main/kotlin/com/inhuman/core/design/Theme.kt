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
// where Material's defaults would reintroduce a generic purple.
private val ColorOnAccent = Color(0xFF24110A)
private val ColorSecondaryLight = Color(0xFF3E665C)
private val ColorTertiaryLight = Color(0xFF94621F)
private val ColorDarkSecondaryContainer = Color(0xFF4A2418)
private val ColorDarkTertiaryContainer = Color(0xFF3A2A16)
private val ColorDarkErrorContainer = Color(0xFF4A1E1A)
private val ColorLightTertiaryContainer = Color(0xFFE8D7BD)
private val ColorLightErrorContainer = Color(0xFFF3D6D0)
private val ColorLightOnErrorContainer = Color(0xFF5A1D16)

private val DarkScheme = darkColorScheme(
	primary = Accent,
	onPrimary = ColorOnAccent,
	primaryContainer = AccentDim,
	onPrimaryContainer = Ink50,

	secondary = Ok,
	onSecondary = Ink900,
	secondaryContainer = ColorDarkSecondaryContainer,
	onSecondaryContainer = Ink50,
	tertiary = Warn,
	onTertiary = Ink900,
	tertiaryContainer = ColorDarkTertiaryContainer,
	onTertiaryContainer = Warn,

	background = Ink900,
	onBackground = Ink200,
	surface = Ink850,
	onSurface = Ink50,
	surfaceVariant = Ink800,
	onSurfaceVariant = Ink300,
	surfaceTint = Color.Transparent,
	surfaceDim = Ink900,
	surfaceBright = Ink800,
	surfaceContainerLowest = Ink900,
	surfaceContainerLow = Ink900,
	surfaceContainer = Ink850,
	surfaceContainerHigh = Ink800,
	surfaceContainerHighest = Ink700,
	// A hairline colour, not a text colour: Ink700 on Ink850 is about 1.3:1.
	// Secondary text and glyphs use `onSurfaceVariant` (Ink300, about 7:1).
	// Nothing should hand `outline` to a `Text(color = ...)` -- an audit found
	// twelve places that did, and placeholders at 1.3:1 read as disabled.
	outline = Ink700,
	outlineVariant = Ink700,
	inverseSurface = Ink50,
	inverseOnSurface = Ink900,
	inversePrimary = AccentDim,

	error = Danger,
	onError = Ink900,
	errorContainer = ColorDarkErrorContainer,
	onErrorContainer = Danger,
)

private val LightScheme = lightColorScheme(
	primary = AccentDeep,
	onPrimary = Paper100,
	primaryContainer = Paper300,
	onPrimaryContainer = PaperInk,

	secondary = ColorSecondaryLight,
	onSecondary = Paper100,
	secondaryContainer = Paper300,
	onSecondaryContainer = PaperInk,
	tertiary = ColorTertiaryLight,
	onTertiary = Paper100,
	tertiaryContainer = ColorLightTertiaryContainer,
	onTertiaryContainer = PaperInk,

	background = Paper100,
	onBackground = PaperInk,
	surface = Paper200,
	onSurface = PaperInk,
	surfaceVariant = Paper300,
	onSurfaceVariant = PaperMuted,
	surfaceTint = Color.Transparent,
	surfaceDim = Paper200,
	surfaceBright = Paper100,
	surfaceContainerLowest = Paper100,
	surfaceContainerLow = Paper100,
	surfaceContainer = Paper200,
	surfaceContainerHigh = Paper300,
	surfaceContainerHighest = Paper300,
	// Hairline only: Paper300 on Paper100 is about 1.3:1. `onSurfaceVariant` is
	// the secondary text colour (see the note in the dark scheme).
	outline = Paper300,
	outlineVariant = Paper300,
	inverseSurface = PaperInk,
	inverseOnSurface = Paper100,
	inversePrimary = AccentDim,

	error = DangerDeep,
	onError = Paper100,
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
 * [darkTheme] is a parameter rather than read internally so a screenshot test
 * or a preview can force a scheme without touching the system setting.
 */
@Composable
fun InhumanTheme(
	darkTheme: Boolean = isSystemInDarkTheme(),
	content: @Composable () -> Unit,
) {
	MaterialTheme(
		colorScheme = if (darkTheme) DarkScheme else LightScheme,
		typography = InhumanTypography,
		shapes = InhumanShapes,
		content = content,
	)
}
