package com.inhuman.ui.common

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.inhuman.core.common.diag.Diag

/**
 * Play a generated audio file from the conversation workspace in-process.
 *
 * Generated TTS audio is an ordinary workspace file, so playback goes through
 * the same DocumentsProvider URI as the Files screen; the app never hands the
 * player a private path. Keeping the player here (instead of launching a
 * viewer) is what makes the speech action finish where it started: the user
 * taps Speak and hears the answer, without a chooser.
 *
 * The caller owns the instance: `onStarted` / `onFinished` / `onFailed` are
 * called on the MediaPlayer's thread (the composition thread, since the player
 * is created there), and `release()` must run when the owner is disposed.
 */
class WorkspaceAudioPlayer(context: Context) {

	private val appContext = context.applicationContext
	private var player: MediaPlayer? = null
	private var asset: AssetFileDescriptor? = null

	/** Fired when `prepareAsync` finished and playback actually started. */
	var onStarted: (() -> Unit)? = null

	/** Fired once the duration is known; -1 or 0 means the player could not report it. */
	var onDurationKnown: ((Int) -> Unit)? = null

	/** Fired when playback reached the end. */
	var onFinished: (() -> Unit)? = null

	/** Fired when the player failed; the UI should say so once. */
	var onFailed: (() -> Unit)? = null

	/**
	 * Start playing [relativePath] in [workspaceId], stopping whatever this
	 * player was already doing.
	 *
	 * Returns false when the file could not even be opened; later failures are
	 * reported through [onFailed].
	 */
	fun play(workspaceId: String, relativePath: String): Boolean {
		releasePlayer()
		val uri = workspaceFileUri(appContext, workspaceId, relativePath)
		val descriptor = runCatching {
			appContext.contentResolver.openAssetFileDescriptor(uri, "r")
		}.onFailure { failure ->
			Diag.w("speech", "could not open $relativePath: ${failure.javaClass.simpleName}")
		}.getOrNull()
		if (descriptor == null) {
			Diag.w("speech", "no descriptor for $workspaceId/$relativePath")
			return false
		}

		val media = MediaPlayer()
		return try {
			media.setAudioAttributes(
				AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_MEDIA)
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build(),
			)
			// The descriptor form rather than setDataSource(context, uri):
			// the DocumentsProvider opens a real file, and the descriptor is
			// seekable, which MediaPlayer needs for a WAV. A provider may
			// report an unknown length, in which case the two-argument form
			// must be used rather than passing -1 as a length.
			if (descriptor.length >= 0) {
				media.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
			} else {
				media.setDataSource(descriptor.fileDescriptor)
			}
			media.setOnPreparedListener {
				it.start()
				onStarted?.invoke()
				onDurationKnown?.invoke(runCatching { it.duration }.getOrDefault(0))
			}
			media.setOnCompletionListener { finish(failed = false) }
			media.setOnErrorListener { _, what, extra ->
				Diag.w("speech", "playback failed what=$what extra=$extra")
				finish(failed = true)
				true
			}
			player = media
			asset = descriptor
			media.prepareAsync()
			true
		} catch (failure: Exception) {
			Diag.w("speech", "playback could not be prepared: ${failure.javaClass.simpleName}")
			runCatching { media.release() }
			runCatching { descriptor.close() }
			false
		}
	}

	/** Stop the current playback. No completion callback is fired. */
	fun stop() {
		releasePlayer()
	}

	/** Pause at the current position; true when there was audio to pause. */
	fun pause(): Boolean {
		val media = player ?: return false
		if (!runCatching { media.isPlaying }.getOrDefault(false)) return false
		return runCatching {
			media.pause()
			true
		}.getOrDefault(false)
	}

	/** Resume a paused player; true when there was audio to resume. */
	fun resume(): Boolean {
		val media = player ?: return false
		return runCatching {
			media.start()
			true
		}.getOrDefault(false)
	}

	/** Current position, for the media session's playback state. */
	fun positionMs(): Int =
		player?.let { runCatching { it.currentPosition }.getOrDefault(0) } ?: 0

	/** Total duration, or 0 while it is not known yet. */
	fun durationMs(): Int {
		val duration = player?.let { runCatching { it.duration }.getOrDefault(-1) } ?: -1
		return duration.coerceAtLeast(0)
	}

	/** Seek inside the prepared file; true when there was audio to seek. */
	fun seekTo(positionMs: Int): Boolean {
		val media = player ?: return false
		return runCatching {
			media.seekTo(positionMs.coerceAtLeast(0))
			true
		}.getOrDefault(false)
	}

	/** Stop and release; call from `onDispose`. */
	fun release() {
		releasePlayer()
	}

	private fun finish(failed: Boolean) {
		releasePlayer()
		if (failed) onFailed?.invoke() else onFinished?.invoke()
	}

	private fun releasePlayer() {
		player?.let { media ->
			runCatching { media.stop() }
			runCatching { media.release() }
		}
		player = null
		runCatching { asset?.close() }
		asset = null
	}
}
