package com.inhuman.ui.common

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.inhuman.core.ai.speech.Wav
import com.inhuman.core.common.diag.Diag

/**
 * What each audio layer saw when a generated file would not play.
 *
 * ## Why this exists
 *
 * `MediaPlayer` reports one opaque code (`playback failed what=-38 extra=0` was
 * the observed one), and that code is a `status_t` from whichever layer gave up
 * -- the extractor, the codec, or the audio sink. From the log alone it is
 * indistinguishable which, and the file itself is on the user's phone. This
 * probe walks the same layers the player walks and records a verdict per layer:
 * the header's own claims, what `MediaMetadataRetriever` can read, what
 * `MediaExtractor` builds as a track, whether a decoder exists for that track,
 * and whether an `AudioTrack` accepts the PCM.
 *
 * ## What it does not do
 *
 * It never plays anything audible (the scratch track's volume is zero) and it
 * records no audio content: only dimensions, codec names and status values,
 * which is the same rule the rest of [Diag] follows. It runs on the caller's
 * thread, so [probe] must be called off the main one -- the player starts it on
 * a short-lived thread from its failure path.
 */
object AudioDiagnostics {

	/** Read no more than this from the front of the file for the header. */
	private const val HEADER_BYTES = 1_024

	/** How much PCM the scratch track is offered, in milliseconds. */
	private const val WRITE_MILLIS = 200

	/**
	 * `MediaFormat` has no public constant for this key, so the string is the
	 * only stable name for it.
	 */
	private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"

	fun probe(context: Context, uri: Uri, relativePath: String) {
		val name = relativePath.substringAfterLast('/')
		val facts = readFacts(context, uri, name)
		probeRetriever(context, uri)
		probeExtractor(context, uri)
		probeAudioTrack(context, uri, facts)
	}

	private fun readFacts(context: Context, uri: Uri, name: String): Wav.Facts? {
		val total = audioFileSize(context, uri)
		if (total == null) {
			Diag.w("audio", "probe file=$name no descriptor")
			return null
		}
		val header = readBytes(context, uri, 0L, minOf(HEADER_BYTES.toLong(), total).toInt())
		val facts = Wav.facts(header)
		Diag.i(
			"audio",
			"probe file=$name bytes=$total wav=${facts?.describe(total) ?: "none"}",
		)
		return facts
	}

	private fun probeRetriever(context: Context, uri: Uri) {
		val retriever = MediaMetadataRetriever()
		try {
			retriever.setDataSource(context, uri)
			val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
			val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
			val audio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
			Diag.i("audio", "probe retriever duration=$duration mime=$mime audio=$audio")
		} catch (failure: Exception) {
			Diag.w("audio", "probe retriever failed ${failure.javaClass.simpleName}: ${failure.message}")
		} finally {
			runCatching { retriever.release() }
		}
	}

	private fun probeExtractor(context: Context, uri: Uri) {
		val extractor = MediaExtractor()
		try {
			val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
				?: throw IllegalStateException("no descriptor")
			descriptor.use {
				extractor.setDataSource(it.fileDescriptor, 0, it.statSize)
			}
			val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
			val tracks = (0 until extractor.trackCount).joinToString("; ") { index ->
				val format = extractor.getTrackFormat(index)
				val mime = format.getString(MediaFormat.KEY_MIME) ?: "?"
				val sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
				val channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
				val bits = format.intOrNull(KEY_BITS_PER_SAMPLE)
				val decoder = runCatching { codecs.findDecoderForFormat(format) }.getOrNull()
				"track$index mime=$mime rate=$sampleRate ch=$channels bits=$bits " +
					"decoder=${decoder ?: "none"}"
			}
			Diag.i("audio", "probe extractor tracks=${extractor.trackCount} $tracks")
		} catch (failure: Exception) {
			Diag.w("audio", "probe extractor failed ${failure.javaClass.simpleName}: ${failure.message}")
		} finally {
			runCatching { extractor.release() }
		}
	}

	/**
	 * Offer the first fraction of a second to a muted `AudioTrack`.
	 *
	 * This is the layer `MediaPlayer` cannot be asked about directly: if the
	 * extractor and a decoder both look fine but the sink refuses the format,
	 * the failure is the device's audio path rather than the file.
	 */
	private fun probeAudioTrack(context: Context, uri: Uri, facts: Wav.Facts?) {
		if (facts == null) {
			Diag.i("audio", "probe audiotrack skipped: no wav header")
			return
		}
		if (facts.bitsPerSample != 16 || facts.channels !in 1..2) {
			Diag.i(
				"audio",
				"probe audiotrack skipped: bits=${facts.bitsPerSample} ch=${facts.channels}",
			)
			return
		}
		val channelMask = if (facts.channels == 1) {
			AudioFormat.CHANNEL_OUT_MONO
		} else {
			AudioFormat.CHANNEL_OUT_STEREO
		}
		val minBuffer = AudioTrack.getMinBufferSize(
			facts.sampleRate,
			channelMask,
			AudioFormat.ENCODING_PCM_16BIT,
		)
		Diag.i(
			"audio",
			"probe audiotrack rate=${facts.sampleRate} ch=${facts.channels} minBuffer=$minBuffer",
		)
		if (minBuffer <= 0) {
			Diag.w("audio", "probe audiotrack: the device offers no buffer for this format")
			return
		}

		val track = runCatching {
			AudioTrack.Builder()
				.setAudioAttributes(
					AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_MEDIA)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build(),
				)
				.setAudioFormat(
					AudioFormat.Builder()
						.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
						.setSampleRate(facts.sampleRate)
						.setChannelMask(channelMask)
						.build(),
				)
				.setBufferSizeInBytes(maxOf(minBuffer * 2, 4_096))
				.setTransferMode(AudioTrack.MODE_STREAM)
				.build()
		}.getOrElse { failure ->
			Diag.w("audio", "probe audiotrack build failed: ${failure.javaClass.simpleName}")
			return
		}

		try {
			// Zero volume: this is a capability check, not a playback. The user
			// is about to hear the toast, not a second copy of the sentence.
			track.setVolume(0f)
			track.play()
			val bytes = (facts.sampleRate * facts.blockAlign * WRITE_MILLIS / 1_000)
				.coerceAtMost(16_384)
			val payload = readPayload(context, uri, facts, bytes)
			val written = track.write(payload, 0, payload.size)
			Diag.i(
				"audio",
				"probe audiotrack state=${track.state} write=$written wanted=${payload.size}",
			)
		} catch (failure: Exception) {
			Diag.w("audio", "probe audiotrack write failed ${failure.javaClass.simpleName}")
		} finally {
			runCatching { track.stop() }
			runCatching { track.release() }
		}
	}

	// --- file plumbing --------------------------------------------------------

	private fun readPayload(context: Context, uri: Uri, facts: Wav.Facts, bytes: Int): ByteArray {
		val total = audioFileSize(context, uri) ?: return ByteArray(0)
		val payload = facts.payloadBytes(total) ?: return ByteArray(0)
		val want = minOf(bytes.toLong(), payload).toInt()
		return readBytes(context, uri, facts.dataOffset.toLong(), want)
	}

	private fun readBytes(context: Context, uri: Uri, offset: Long, count: Int): ByteArray {
		if (count <= 0) return ByteArray(0)
		val stream = openAudioStream(context, uri) ?: return ByteArray(0)
		return stream.use {
			runCatching {
				it.channel.position(offset)
				val buffer = ByteArray(count)
				var read = 0
				while (read < count) {
					val got = it.read(buffer, read, count - read)
					if (got <= 0) break
					read += got
				}
				if (read == count) buffer else buffer.copyOf(read)
			}.getOrDefault(ByteArray(0))
		}
	}

	private fun MediaFormat.intOrNull(key: String): Int? =
		if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}


