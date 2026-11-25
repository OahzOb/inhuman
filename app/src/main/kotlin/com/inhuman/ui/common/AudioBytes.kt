package com.inhuman.ui.common

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * Read a generated workspace file without handing its path to anyone.
 *
 * ## Why the descriptor is wrapped rather than used raw
 *
 * `FileInputStream(pfd.fileDescriptor)` closes the underlying descriptor when
 * it is closed, and the `ParcelFileDescriptor` that owns it does not know: a
 * second close then throws, which would turn "the audio would not play" into a
 * crash in the composition that called it. `AutoCloseInputStream` closes the
 * owner instead, so there is exactly one close and no ownership question.
 */

/** The size the provider reports, or null when the file cannot be opened. */
internal fun audioFileSize(context: Context, uri: Uri): Long? =
	runCatching {
		context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
	}.getOrNull()?.takeIf { it > 0 }

/**
 * A seekable stream over the file, or null when the provider refuses it.
 *
 * The descriptor form is used so the stream's channel can be positioned at the
 * WAV payload instead of reading through the header.
 */
internal fun openAudioStream(context: Context, uri: Uri): FileInputStream? =
	runCatching {
		context.contentResolver.openFileDescriptor(uri, "r")?.let { descriptor ->
			ParcelFileDescriptor.AutoCloseInputStream(descriptor)
		}
	}.getOrNull()
