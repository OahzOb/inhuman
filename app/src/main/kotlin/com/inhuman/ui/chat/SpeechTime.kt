package com.inhuman.ui.chat

import java.util.Locale

/**
 * Clock labels for the speech player.
 *
 * Pure and tested because the same formatting feeds two surfaces that cannot be
 * compared by eye: the in-app progress row and the notification's "where it is"
 * line. A one-minute reply must read `0:42`, not `42` or `0:00:42`.
 */
object SpeechTime {

	fun format(millis: Int): String {
		val seconds = (millis.coerceAtLeast(0) / 1000)
		val minutes = seconds / 60
		val remainder = seconds % 60
		return String.format(Locale.ROOT, "%d:%02d", minutes, remainder)
	}
}
