package com.inhuman.core.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palettes and surface families are data, so their contrast contract can
 * be checked on a plain JVM. This matters here: two contrast defects were
 * found by hand in the original palette (a secondary text step at 4.35:1 on
 * the dialog surface, and an outline at 1.3:1 reused as placeholder text),
 * and adding three more of each without a check would multiply the places
 * that can drift by twelve.
 */
class PaletteContrastTest {

	private fun channel(value: Float): Double {
		val s = value.toDouble()
		return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
	}

	private fun luminance(color: Color): Double =
		0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

	private fun contrast(a: Color, b: Color): Double {
		val hi = maxOf(luminance(a), luminance(b))
		val lo = minOf(luminance(a), luminance(b))
		return (hi + 0.05) / (lo + 0.05)
	}

	private fun readable(label: String, foreground: Color, background: Color) {
		val ratio = contrast(foreground, background)
		assertTrue("$label is $ratio:1, below AA body text (4.5:1)", ratio >= 4.5)
	}

	@Test
	fun everyPaletteOnEverySurfaceKeepsEveryTextSlotReadable() {
		SurfaceFamily.entries.forEach { family ->
			val s = surfaceColors(family)
			ThemePalette.entries.forEach { palette ->
				val p = paletteColors(palette)
				val where = "${palette.name}/${family.name}"

				// Dark scheme, keyed by the slots in `darkScheme`.
				readable("$where dark body", s.darkBodyText, s.darkBackground)
				readable("$where dark emphasis", s.darkEmphasis, s.darkSurface)
				readable("$where dark secondary", s.darkSecondaryText, s.darkSurface)
				readable("$where dark onPrimary", p.onDarkPrimary, p.darkPrimary)
				readable("$where dark onPrimaryContainer", s.darkEmphasis, p.darkPrimaryContainer)
				readable("$where dark onSecondary", s.darkBackground, p.darkSecondary)
				readable("$where dark onSecondaryContainer", s.darkEmphasis, p.darkSecondaryContainer)
				readable("$where dark onTertiary", s.darkBackground, Warn)
				readable("$where dark onTertiaryContainer", Warn, Color(0xFF3A2A16))
				readable("$where dark onError", s.darkBackground, Danger)

				// Light scheme. PaperMuted's 5.8:1 on Paper200 is the value that
				// replaced the earlier 4.35:1, so this pair is deliberately checked
				// for every family, not just the default one.
				readable("$where light body", s.lightInk, s.lightBackground)
				readable("$where light surface", s.lightInk, s.lightSurface)
				readable("$where light secondary", s.lightMuted, s.lightSurface)
				readable("$where light onPrimary", s.lightBackground, p.lightPrimary)
				readable("$where light onPrimaryContainer", s.lightInk, s.lightRaised)
				readable("$where light onSecondary", s.lightBackground, p.lightSecondary)
				readable("$where light onTertiary", s.lightBackground, Color(0xFF94621F))
				readable("$where light onTertiaryContainer", s.lightInk, Color(0xFFE8D7BD))
				readable("$where light onError", s.lightBackground, DangerDeep)
				readable("$where light onErrorContainer", Color(0xFF5A1D16), Color(0xFFF3D6D0))
			}
		}
	}

	@Test
	fun outlinesStayHairlinesRatherThanTurningIntoTextColours() {
		// The outline slots are ~1.3:1 by design. Someone "fixing" that number
		// would make every divider loud; the rule is that text uses the
		// onSurfaceVariant slots instead.
		SurfaceFamily.entries.forEach { family ->
			val s = surfaceColors(family)
			assertTrue("${family.name} dark outline", contrast(s.darkOutline, s.darkSurface) < 2.0)
			assertTrue("${family.name} light outline", contrast(s.lightRaised, s.lightBackground) < 2.0)
		}
	}

	@Test
	fun defaultsAreTheOriginalLook() {
		val warm = surfaceColors(SurfaceFamily.Warm)
		assertEquals(Ink900, warm.darkBackground)
		assertEquals(Ink850, warm.darkSurface)
		assertEquals(Ink700, warm.darkOutline)
		assertEquals(Ink200, warm.darkBodyText)
		assertEquals(Paper100, warm.lightBackground)
		assertEquals(Paper300, warm.lightRaised)
		assertEquals(PaperMuted, warm.lightMuted)
	}

	@Test
	fun storedPreferencesFallBackToTheOriginalLook() {
		assertEquals(ThemePalette.Ember, ThemePalette.fromStored(null))
		assertEquals(ThemePalette.Ember, ThemePalette.fromStored("not-a-palette"))
		assertEquals(ThemePalette.Moss, ThemePalette.fromStored("moss"))
		assertEquals(ThemeMode.System, ThemeMode.fromStored(null))
		assertEquals(ThemeMode.Dark, ThemeMode.fromStored("dark"))
		assertEquals(true, ThemeMode.Dark.usesDark(false))
		assertEquals(false, ThemeMode.Light.usesDark(true))
		assertEquals(true, ThemeMode.System.usesDark(true))
		assertEquals(SurfaceFamily.Warm, SurfaceFamily.fromStored(null))
		assertEquals(SurfaceFamily.Warm, SurfaceFamily.fromStored("not-a-surface"))
		assertEquals(SurfaceFamily.Slate, SurfaceFamily.fromStored("slate"))
	}
}
