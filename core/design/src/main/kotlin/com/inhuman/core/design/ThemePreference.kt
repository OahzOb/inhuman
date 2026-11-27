package com.inhuman.core.design

/**
 * An opt-in accent, not a new visual language.
 *
 * A palette moves only the signal colours; the neutral ground underneath it
 * is chosen separately with [SurfaceFamily]. Shapes and the type scale never
 * move. That is the deliberate limit: the app is a working tool with one
 * accent and one ground at a time, and "more colours" means a handful of
 * choices, not a theme editor.
 *
 * This is presentation state. No tool, approval, capability or workspace rule
 * reads it, and it never enters a request or a transcript.
 */
enum class ThemePalette {
	/** The original burnt orange. The default, so existing installs do not move. */
	Ember,
	/** Sage/teal, with a muted blue for the secondary signal. */
	Moss,
	/** A dusty indigo. */
	Indigo,
	/** Dusty violet. */
	Plum,
	;

	companion object {
		fun fromStored(value: String?): ThemePalette =
			entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Ember
	}
}

/**
 * The neutral ground: what "graphite" and "paper" are made of.
 *
 * [Warm] is the original pair and the default. The other two change every
 * surface, outline and text neutral together, so no slot can come from a
 * different family. This is still presentation state: no tool, policy,
 * request or transcript reads it.
 */
enum class SurfaceFamily {
	Warm,
	Slate,
	Sepia,
	;

	companion object {
		fun fromStored(value: String?): SurfaceFamily =
			entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Warm
	}
}

/**
 * Whether the chosen palette is painted on a dark or a light base.
 *
 * [System] keeps the old behaviour: Compose follows the platform's night mode.
 * The two overrides are a reading preference, the same kind of thing as
 * `LayoutPreference`, not an accessibility service or a capability flag.
 */
enum class ThemeMode {
	System,
	Light,
	Dark,
	;

	companion object {
		fun fromStored(value: String?): ThemeMode =
			entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: System
	}

	/** [systemDark] is the value `isSystemInDarkTheme()` would return. */
	fun usesDark(systemDark: Boolean): Boolean = when (this) {
		System -> systemDark
		Light -> false
		Dark -> true
	}
}
