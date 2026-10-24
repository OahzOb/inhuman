package com.inhuman.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The desktop UI language.
 *
 * A code rather than an enum so the stored value is stable if a language is
 * added later. English is the fallback; every call site supplies both strings
 * with [s] instead of a key table, which keeps the two translations next to
 * each other and makes a missing one a compile error rather than a blank label.
 */
val LocalDesktopLanguage = staticCompositionLocalOf { "en" }

/** Called by the language switch in Settings. */
val LocalSetDesktopLanguage = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun s(english: String, chinese: String): String =
	if (LocalDesktopLanguage.current == "zh") chinese else english

fun languageName(code: String): String = when (code) {
	"zh" -> "中文"
	else -> "EN"
}
