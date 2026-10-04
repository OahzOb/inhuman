package com.inhuman.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography with one deliberate departure from the Material defaults: body
 * text is 16sp/26sp rather than 14sp/20sp.
 *
 * This is a reading app. Assistant replies are long, and the Material default
 * line height is tuned for labels and lists rather than paragraphs.
 */
val InhumanTypography = Typography().let { base ->
	base.copy(
		bodyLarge = TextStyle(
			fontFamily = FontFamily.Default,
			fontWeight = FontWeight.Normal,
			fontSize = 16.sp,
			lineHeight = 26.sp,
			letterSpacing = 0.2.sp,
		),
		bodyMedium = TextStyle(
			fontFamily = FontFamily.Default,
			fontWeight = FontWeight.Normal,
			fontSize = 14.sp,
			lineHeight = 21.sp,
			letterSpacing = 0.2.sp,
		),
		titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
		labelLarge = base.labelLarge.copy(letterSpacing = 0.4.sp),
	)
}
