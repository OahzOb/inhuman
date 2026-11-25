package com.inhuman.ui.common

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.inhuman.core.ai.speech.Wav
import com.inhuman.core.common.diag.Diag
import java.io.File

/**
 * Play a generated audio file from the conversation workspace in-process.
 *
 * Generated TTS audio is an ordinary workspace file, so playback goes through
 * the same DocumentsProvider URI as the Files screen; the app never hands the
 * player a private path. Keeping the player here (instead of launching a
 * viewer) is what makes the speech action finish where it started: the user
 * taps Speak and hears the answer, without a chooser.
 *
 * ## Why the header is inspected before the player sees it
 *
 * A streamed TTS response writes placeholder sizes into its WAV header, and
 * `MediaPlayer` fails on the ones it cannot reconcile with the real file
 * (`playback failed what=-38` was observed on Android 17 for a file the same
 * player accepted from an older device). The header is therefore read first
 * and, when it disagrees with the payload, the audio is copied behind a
 * canonical header into the app cache and played from there. The workspace file
 * is not modified: it is a cache of a paid request and the next tap must not
 * bill again.
 *
 * When playback still fails, [AudioDiagnostics] runs once and writes what each
 * layer saw. A failure that cannot be explained from the log is a failure that
 * costs another round trip with the user.
 *
 * The caller owns the instance: `onStarted` / `onFinished` / `onFailed` are
 * called on the MediaPlayer's thread (the composition thread, since the player
 * is created there), and `release()` must run when the owner is disposed.
 */
class WorkspaceAudioPlayer(context: Context) {

	private val appContext = context.applicationContext
	private var player: MediaPlayer? = null
	private var asset: AssetFileDescriptor? = null
	private var repaired: File? = null

	/** Fired when `prepareAsync` finished and playback actually started. */
	var onStarted: (() -> Unit)? = null

	/** Fired once the duration is known; -1 or 0 means the player could not report it. */
	var onDurationKnown: ((Int) -> Unit)? = null

	/** Fired when playback reached the end. */
	var onFinished: (() -> Unit)? = null

	/** Fired when the player failed; the UI should say so once. */
	var onFailed: (() -> Unit)? = null

	/** The file being played, kept for the failure probe. */
	private var currentWorkspaceId: String? = null
	private var currentPath: String? = null

	/**
	 * Start playing [relativePath] in [workspaceId], stopping whatever this
	 * player was already doing.
	 *
	 * Returns false when the file could not even be opened; later failures are
	 * reported through [onFailed].
	 */
	fun play(workspaceId: String, relativePath: String): Boolean {
		releasePlayer()
		currentWorkspaceId = workspaceId
		currentPath = relativePath
		val uri = workspaceFileUri(appContext, workspaceId, relativePath)

		// The header is read before the player sees the file: a streamed
		// response states sizes that do not match its own payload, and a player
		// that believes them refuses the file. A failure in this inspection
		// must not take playback down with it -- the ordinary path is still
		// worth trying.
		val copy = runCatching { repairedCopy(uri, relativePath) }.getOrElse { failure ->
			Diag.w("audio", "header check failed: ${failure.javaClass.simpleName}")
			null
		}
		if (copy == null) return playProvider(uri)
		repaired = copy
		return playFile(copy.absolutePath)
	}

	/**
	 * The cache copy with a canonical header, or null when none is needed.
	 *
	 * Null has two meanings on purpose: the file's header already agrees with
	 * its payload, or its format fields are too damaged to repair. Both are
	 * "hand it to the player as it is", and the player is the thing that decides
	 * whether that works.
	 */
	private fun repairedCopy(uri: Uri, relativePath: String): File? {
		val total = audioFileSize(appContext, uri)
		if (total == null) {
			Diag.w("audio", "no size for $relativePath")
			return null
		}
		val probe = readHeader(uri, total, relativePath)
		val repair = probe.facts?.let { Wav.repair(it, probe.totalBytes) } ?: return null
		// The copy runs on the calling thread, which is the composition's: a
		// read-aloud is normally under a megabyte, but a long reply is not, and
		// a multi-second main-thread copy is an ANR. Above the ceiling the file
		// is handed over as it is; the failure probe then says so.
		if (repair.payloadBytes > MAX_REPAIR_BYTES) {
			Diag.w(
				"audio",
				"not repaired: $relativePath payload=${repair.payloadBytes} " +
					"over ${MAX_REPAIR_BYTES}",
			)
			return null
		}
		val copy = writeRepaired(uri, repair, relativePath)
		if (copy == null) {
			Diag.w("audio", "repair failed for $relativePath; trying the original")
			return null
		}
		Diag.i(
			"audio",
			"repair file=$relativePath payload=${repair.payloadBytes} " +
				"pad=${repair.pad} bytes=${copy.length()}",
		)
		return copy
	}

	// --- the two source paths -------------------------------------------------

	/** The ordinary path: the provider descriptor, exactly as the file is. */
	private fun playProvider(uri: Uri): Boolean {
		val descriptor = runCatching {
			appContext.contentResolver.openAssetFileDescriptor(uri, "r")
		}.onFailure { failure ->
			Diag.w("audio", "could not open: ${failure.javaClass.simpleName}")
		}.getOrNull()
		if (descriptor == null) {
			Diag.w("audio", "no descriptor for ${currentPath ?: "?"}")
			launchProbe()
			return false
		}
		var media: MediaPlayer? = null
		return try {
			val created = preparePlayer().also { media = it }
			// The descriptor form rather than setDataSource(context, uri):
			// the DocumentsProvider opens a real file, and the descriptor is
			// seekable, which MediaPlayer needs for a WAV. A provider may
			// report an unknown length, in which case the two-argument form
			// must be used rather than passing -1 as a length.
			if (descriptor.length >= 0) {
				created.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
			} else {
				created.setDataSource(descriptor.fileDescriptor)
			}
			player = created
			asset = descriptor
			created.prepareAsync()
			true
		} catch (failure: Exception) {
			Diag.w("audio", "could not be prepared: ${failure.javaClass.simpleName}")
			launchProbe()
			runCatching { media?.release() }
			runCatching { descriptor.close() }
			false
		}
	}

	/** A canonical copy in the cache, played by path. */
	private fun playFile(path: String): Boolean {
		var media: MediaPlayer? = null
		return try {
			val created = preparePlayer().also { media = it }
			created.setDataSource(path)
			player = created
			created.prepareAsync()
			true
		} catch (failure: Exception) {
			Diag.w("audio", "could not be prepared: ${failure.javaClass.simpleName}")
			launchProbe()
			runCatching { media?.release() }
			false
		}
	}

	private fun preparePlayer(): MediaPlayer {
		val media = MediaPlayer()
		media.setAudioAttributes(
			AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_MEDIA)
				.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
				.build(),
		)
		media.setOnPreparedListener {
			it.start()
			onStarted?.invoke()
			onDurationKnown?.invoke(runCatching { it.duration }.getOrDefault(0))
		}
		media.setOnCompletionListener { finish(failed = false) }
		media.setOnErrorListener { _, what, extra ->
			Diag.w("audio", "playback failed what=$what extra=$extra")
			finish(failed = true)
			true
		}
		return media
	}

	// --- header read and repair ----------------------------------------------

	private data class HeaderProbe(
		val facts: Wav.Facts?,
		val totalBytes: Long,
	)

	/**
	 * Read enough of the file to parse its header.
	 *
	 * 1 KiB covers the chunk layouts that occur in practice (`fmt ` is 16, 18
	 * or 40 bytes, and `data` follows it); a file with a bigger leading chunk
	 * simply reports no facts and is played as it is.
	 */
	private fun readHeader(uri: Uri, total: Long, relativePath: String): HeaderProbe {
		val want = minOf(HEADER_BYTES, total).toInt()
		val header = openAudioStream(appContext, uri)?.use { stream -> readFully(stream, want) }
			?: ByteArray(0)
		val facts = Wav.facts(header)
		if (facts == null) {
			Diag.d("audio", "source file=$relativePath bytes=$total not-wav")
		} else {
			Diag.i("audio", "source file=$relativePath ${facts.describe(total)}")
		}
		return HeaderProbe(facts, total)
	}

	/**
	 * Copy the payload behind a canonical header into the app cache.
	 *
	 * Streamed rather than buffered: a long read-aloud can be tens of megabytes,
	 * and this runs on the main thread for the fraction of a second an open
	 * takes. The descriptor is seeked to the payload instead of reading through
	 * the header, so the copy is one pass over the audio.
	 */
	private fun writeRepaired(
		uri: Uri,
		repair: Wav.Repair,
		relativePath: String,
	): File? {
		val stream = openAudioStream(appContext, uri) ?: return null
		return stream.use {
			runCatching {
				val dir = File(appContext.cacheDir, "audio-playback").apply { mkdirs() }
				val target = File(dir, "playback-${Integer.toHexString(relativePath.hashCode())}.wav")
				target.outputStream().use { output ->
					output.write(repair.header)
					stream.channel.position(repair.dataOffset)
					var remaining = repair.payloadBytes
					val buffer = ByteArray(COPY_BUFFER)
					while (remaining > 0) {
						val want = minOf(remaining, buffer.size.toLong()).toInt()
						val count = stream.read(buffer, 0, want)
						if (count <= 0) break
						output.write(buffer, 0, count)
						remaining -= count
					}
					repeat(repair.pad) { output.write(0) }
				}
				target
			}.onFailure { failure ->
				Diag.w("audio", "could not repair $relativePath: ${failure.javaClass.simpleName}")
			}.getOrNull()
		}
	}

	/** Read [count] bytes, or fewer when the file ends first. */
	private fun readFully(stream: java.io.InputStream, count: Int): ByteArray {
		val buffer = ByteArray(count)
		var read = 0
		while (read < count) {
			val got = stream.read(buffer, read, count - read)
			if (got <= 0) break
			read += got
		}
		return if (read == count) buffer else buffer.copyOf(read)
	}

	// --- controls -------------------------------------------------------------

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
		if (failed) {
			launchProbe()
			onFailed?.invoke()
		} else {
			onFinished?.invoke()
		}
	}

	/**
	 * Run the failure probe off the caller's thread and off the player.
	 *
	 * Not inline in the error callback: the callback arrives on the main
	 * thread, and the probe opens descriptors, queries extractors and writes to
	 * an AudioTrack. A short-lived thread keeps that off the frame.
	 */
	private fun launchProbe() {
		val workspaceId = currentWorkspaceId ?: return
		val relativePath = currentPath ?: return
		val uri = workspaceFileUri(appContext, workspaceId, relativePath)
		Thread({
			AudioDiagnostics.probe(appContext, uri, relativePath)
		}, "audio-probe").start()
	}

	private fun releasePlayer() {
		player?.let { media ->
			runCatching { media.stop() }
			runCatching { media.release() }
		}
		player = null
		runCatching { asset?.close() }
		asset = null
		// The repaired copy exists only for this playback; the workspace file is
		// the record and is never touched.
		repaired?.let { file -> runCatching { file.delete() } }
		repaired = null
	}

	private companion object {
		/** Enough for `RIFF` + a 40-byte `fmt ` + `data` and their headers. */
		const val HEADER_BYTES = 1_024L

		/**
		 * Largest payload this player will copy behind a canonical header.
		 *
		 * ~16 MiB is about five minutes of 24 kHz speech, and the copy takes
		 * tens of milliseconds. A file beyond it is played as it is rather than
		 * blocking the composition for a second.
		 */
		const val MAX_REPAIR_BYTES = 16L shl 20

		const val COPY_BUFFER = 64 * 1024
	}
}

