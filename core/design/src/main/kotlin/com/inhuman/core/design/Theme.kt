package com.inhuman.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
	primary = Accent,
	onPrimary = Ink900,
	primaryContainer = AccentDim,
	onPrimaryContainer = Ink50,

	background = Ink900,
	onBackground = Ink200,
	surface = Ink850,
	onSurface = Ink50,
	surfaceVariant = Ink800,
	onSurfaceVariant = Ink200,
	outline = Ink700,
	outlineVariant = Ink700,

	error = Danger,
	onError = Ink900,
	tertiary = Warn,
)

private val LightScheme = lightColorScheme(
	primary = AccentDim,
	onPrimary = Paper100,
	background = Paper100,
	onBackground = PaperInk,
	surface = Paper200,
	onSurface = PaperInk,
	surfaceVariant = Paper300,
	onSurfaceVariant = PaperInk,
	outline = Paper300,
	error = Danger,
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
		content = content,
	)
}
