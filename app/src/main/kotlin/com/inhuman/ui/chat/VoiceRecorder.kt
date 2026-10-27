package com.inhuman.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Records a short WAV in memory for the composer.
 *
 * No file is created: the only copy is the PCM buffer plus its WAV header, and
 * [stop] returns the bytes to the caller. The transcription call uses them and
 * then they are dropped. That is the product rule — a recording never enters the
 * workspace, the transcript or the backup — expressed by not having a path here.
 *
 * The 120-second cap is enforced while reading, not by a timer that can fire
 * after the buffer grew: at 16 kHz mono 16-bit the maximum is under 4 MB.
 */
class VoiceRecorder(private val context: Context) {

	fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
		context,
		Manifest.permission.RECORD_AUDIO,
	) == PackageManager.PERMISSION_GRANTED

	@Volatile private var recording = false
	private var audio: AudioRecord? = null
	private var worker: Thread? = null
	private val buffer = ByteArrayOutputStream()
	private var startedAt = 0L

	/** True while the microphone is open. */
	val isRecording: Boolean get() = recording

	/** Start; returns false when the device has no usable microphone. */
	fun start(onLimit: () -> Unit): Boolean {
		if (recording) return false
		if (!hasPermission()) return false
		val sampleRate = WavPcm.SAMPLE_RATE
		val minimum = AudioRecord.getMinBufferSize(
			sampleRate,
			AudioFormat.CHANNEL_IN_MONO,
			AudioFormat.ENCODING_PCM_16BIT,
		)
		if (minimum <= 0) return false
		val record = try {
			AudioRecord(
				MediaRecorder.AudioSource.VOICE_RECOGNITION,
				sampleRate,
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				maxOf(minimum, sampleRate / 2),
			)
		} catch (_: Exception) {
			return false
		}
		if (record.state != AudioRecord.STATE_INITIALIZED) {
			record.release()
			return false
		}
		buffer.reset()
		audio = record
		startedAt = System.currentTimeMillis()
		recording = true
		try {
			record.startRecording()
		} catch (_: Exception) {
			recording = false
			record.release()
			audio = null
			return false
		}
		worker = thread(name = "voice-recorder", isDaemon = true) {
			val chunk = ByteArray(maxOf(minimum, sampleRate / 2))
			try {
				while (recording) {
					val read = record.read(chunk, 0, chunk.size)
					if (read <= 0) break
					synchronized(buffer) {
						if (buffer.size() < MAX_BYTES) buffer.write(chunk, 0, read)
					}
					if (System.currentTimeMillis() - startedAt >= MAX_MILLIS) {
						recording = false
						onLimit()
						break
					}
				}
			} catch (_: Exception) {
				// A read failure is treated as the end of the recording; `stop`
				// still returns the bytes captured so far.
			}
		}
		return true
	}

	/** Stop and return a complete WAV, or null when nothing was captured. */
	fun stop(): ByteArray? {
		if (!recording && audio == null) return null
		recording = false
		runCatching { audio?.stop() }
		worker?.let { runCatching { it.join(750) } }
		worker = null
		runCatching { audio?.release() }
		audio = null
		val pcm = synchronized(buffer) { buffer.toByteArray() }
		buffer.reset()
		if (pcm.isEmpty()) return null
		return WavPcm.wrap(pcm)
	}

	/** Stop and discard. Safe to call when not recording. */
	fun cancel() {
		if (!recording && audio == null) return
		recording = false
		runCatching { audio?.stop() }
		worker?.let { runCatching { it.join(500) } }
		worker = null
		runCatching { audio?.release() }
		audio = null
		synchronized(buffer) { buffer.reset() }
	}

	companion object {
		const val MAX_MILLIS = 120_000L
		const val MAX_BYTES = 16_000 * 2 * 120
	}
}