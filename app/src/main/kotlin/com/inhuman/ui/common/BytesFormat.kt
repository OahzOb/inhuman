package com.inhuman.ui.common

import java.util.Locale

/**
 * A byte count as one short label.
 *
 * ## Why this is one function and not six
 *
 * The app had six of these: the file list, the file view model, the workspace
 * summary, the log-size line, the chat attachment chip and the image-limit
 * message. Five divided by 1024 and called the result "MB", one called it
 * "MiB", and two used `String.format` with no locale -- so a device with a
 * comma decimal separator showed "1,5 MB" while the rest of the app said "1.5".
 *
 * The division is binary, so the labels are binary too. "MB" for 1024² is the
 * convention that makes a 256 MiB quota read as "256 MB" and a 1 TB drive read
 * as "931 GB"; saying MiB costs one syllable and stops the app from being the
 * thing that is wrong.
 *
 * `Locale.US` is deliberate: these are numbers in a technical readout, not
 * prose, and they sit next to mono metadata. The project already formats token
 * counts with integer arithmetic for the same reason -- see
 * `ContextUsage.kt` and `docs/07-gotchas.md` B11.
 */
fun formatBytes(bytes: Long): String = when {
	bytes < 1024 -> "$bytes B"
	bytes < 1024L * 1024 -> "${oneDecimal(bytes / 1024.0)} KiB"
	bytes < 1024L * 1024 * 1024 -> "${oneDecimal(bytes / (1024.0 * 1024.0))} MiB"
	else -> "${oneDecimal(bytes / (1024.0 * 1024.0 * 1024.0))} GiB"
}

private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)