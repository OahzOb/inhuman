package com.inhuman.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography with two deliberate departures from the Material defaults.
 *
 * Body text is 16sp/26sp rather than 14sp/20sp: this is a reading app, and the
 * Material default line height is tuned for labels and lists rather than
 * paragraphs. Metadata and section labels are monospace and letterspaced, which
 * gives the interface its "instrument" voice without changing the reading face.
 */
val InhumanTypography = Typography().let { base ->
	base.copy(
		displaySmall = TextStyle(
			fontFamily = FontFamily.SansSerif,
			fontWeight = FontWeight.SemiBold,
			fontSize = 36.sp,
			lineHeight = 42.sp,
			letterSpacing = (-0.8).sp,
		),
		headlineMedium = TextStyle(
			fontFamily = FontFamily.SansSerif,
			fontWeight = FontWeight.SemiBold,
			fontSize = 28.sp,
			lineHeight = 34.sp,
			letterSpacing = (-0.4).sp,
		),
		titleLarge = TextStyle(
			fontFamily = FontFamily.SansSerif,
			fontWeight = FontWeight.SemiBold,
			fontSize = 20.sp,
			lineHeight = 26.sp,
			letterSpacing = (-0.2).sp,
		),
		titleMedium = TextStyle(
			fontFamily = FontFamily.SansSerif,
			fontWeight = FontWeight.SemiBold,
			fontSize = 16.sp,
			lineHeight = 22.sp,
			letterSpacing = 0.sp,
		),
		bodyLarge = TextStyle(
			fontFamily = FontFamily.SansSerif,
			fontWeight = FontWeight.Normal,
			fontSize = 16.sp,
			lineHeight = 26.sp,
			letterSpacing = 0.1.sp,
		),
		bodyMedium = TextStyle(
			fontFamily = FontFamily.SansSerif,
			fontWeight = FontWeight.Normal,
			fontSize = 14.sp,
			lineHeight = 21.sp,
			letterSpacing = 0.1.sp,
		),
		bodySmall = TextStyle(
			fontFamily = FontFamily.SansSerif,
			fontWeight = FontWeight.Normal,
			fontSize = 12.sp,
			lineHeight = 17.sp,
			letterSpacing = 0.1.sp,
		),
		labelLarge = TextStyle(
			fontFamily = FontFamily.SansSerif,
			fontWeight = FontWeight.Medium,
			fontSize = 13.sp,
			lineHeight = 18.sp,
			letterSpacing = 0.2.sp,
		),
		labelMedium = TextStyle(
			fontFamily = FontFamily.Monospace,
			fontWeight = FontWeight.Medium,
			fontSize = 11.sp,
			lineHeight = 14.sp,
			letterSpacing = 1.1.sp,
		),
		labelSmall = TextStyle(
			fontFamily = FontFamily.Monospace,
			fontWeight = FontWeight.Normal,
			fontSize = 10.sp,
			lineHeight = 13.sp,
			letterSpacing = 1.0.sp,
		),
	)
}
