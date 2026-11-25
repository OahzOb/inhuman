package com.inhuman.core.ai.speech

/**
 * The facts a WAV header states, and the repair that turns a streamed header
 * into one a player can trust.
 *
 * ## Why this is not just "read the sizes"
 *
 * The speech endpoints stream the audio as they synthesize it, so the header is
 * written before the payload is known: `RIFF` and `data` are routinely filled
 * with `0xFFFFFFFF`. A player that trusts those fields reads a file that claims
 * to be enormous, or one with no samples at all -- Android's extractor refuses
 * the first shape outright (`Prepare failed: status=0x1`, observed) and treats
 * the second as a zero-length stream. The payload in both cases is complete.
 *
 * So the sizes are recomputed from what is actually on disk rather than
 * believed. Only the two size fields and the chunk layout change; the sample
 * bytes and the `fmt ` chunk are copied verbatim, so this is a header rewrite
 * and never a re-encode.
 *
 * ## The one judgement call
 *
 * A `data` size of 0 is ambiguous: it can mean "empty" or "not known yet". A
 * file with a payload after the header cannot be empty, so 0-with-bytes means
 * "to the end", the same as `0xFFFFFFFF`. That rule is in [Facts.payloadBytes]
 * and is the reason a streamed file with a zeroed size plays at all.
 */
object Wav {

	const val FORMAT_PCM = 1
	const val FORMAT_FLOAT = 3
	const val FORMAT_EXTENSIBLE = 0xFFFE

	/** Bytes that must be present before a chunk walk can start. */
	private const val MIN_HEADER = 44

	/**
	 * What one header says.
	 *
	 * [header] is the bytes the facts were parsed from, kept so a repair can
	 * copy the `fmt ` chunk verbatim instead of reconstructing it (an
	 * `EXTENSIBLE` chunk carries a sub-format GUID and a channel mask that a
	 * naive rebuild would lose).
	 */
	data class Facts(
		val riffSize: Int,
		val formatTag: Int,
		val channels: Int,
		val sampleRate: Int,
		val bitsPerSample: Int,
		val dataOffset: Int,
		val dataSize: Int,
		val fmtChunk: ByteArray,
		val header: ByteArray,
	) {

		/** Bytes per sample frame, or 0 when the fields do not make sense. */
		val blockAlign: Int
			get() = if (channels > 0 && bitsPerSample > 0) channels * (bitsPerSample / 8) else 0

		/** Whether the sample format is one an audio player can decode. */
		val playableFormat: Boolean
			get() = formatTag == FORMAT_PCM || formatTag == FORMAT_FLOAT || formatTag == FORMAT_EXTENSIBLE

		/**
		 * Whether the format fields could describe real audio.
		 *
		 * Deliberately loose: a 32 kHz 6-channel file is unusual but valid. The
		 * point is to catch a header that was never filled in (zeros), not to
		 * enforce this app's own preferences.
		 */
		val plausible: Boolean
			get() = channels in 1..8 &&
				sampleRate in 8_000..192_000 &&
				bitsPerSample in 8..32 &&
				blockAlign > 0

		/**
		 * Payload bytes the file actually holds, or null when it holds none.
		 *
		 * A declared size of 0 or a negative one means "unknown, read to the
		 * end"; a size larger than the file is a header that overstates, so the
		 * file is clamped rather than rejected.
		 */
		fun payloadBytes(totalBytes: Long): Long? {
			val available = totalBytes - dataOffset
			if (available <= 0) return null
			return when {
				dataSize <= 0 -> available
				dataSize.toLong() > available -> available
				else -> dataSize.toLong()
			}
		}

		/** Milliseconds of audio, from the header and the real payload length. */
		fun durationMillis(totalBytes: Long): Long? {
			val frame = blockAlign
			val payload = payloadBytes(totalBytes) ?: return null
			if (frame <= 0 || sampleRate <= 0) return null
			return payload * 1_000 / (sampleRate.toLong() * frame)
		}

		/** One line for the diagnostic log; no audio content, only dimensions. */
		fun describe(totalBytes: Long): String =
			"riff=$riffSize fmt=$formatTag ch=$channels rate=$sampleRate bits=$bitsPerSample " +
				"block=$blockAlign dataOff=$dataOffset dataSize=$dataSize bytes=$totalBytes " +
				"payload=${payloadBytes(totalBytes) ?: "?"}"
	}

	/**
	 * The header to write and the payload range to copy after it.
	 *
	 * [payloadBytes] is the number of sample bytes to copy; [pad] is 1 when the
	 * payload has an odd length (a WAV chunk must be even, and the pad byte is
	 * not part of the data chunk).
	 */
	data class Repair(
		val header: ByteArray,
		val dataOffset: Long,
		val payloadBytes: Long,
		val pad: Int,
	)

	/** Parse a WAV header from its first bytes, or null when it is not one. */
	fun facts(header: ByteArray): Facts? {
		if (header.size < MIN_HEADER) return null
		if (ascii(header, 0, 4) != "RIFF" || ascii(header, 8, 4) != "WAVE") return null

		val riffSize = leInt(header, 4)
		var position = 12
		var fmtChunk: ByteArray? = null
		var formatTag = 0
		var channels = 0
		var sampleRate = 0
		var bitsPerSample = 0

		while (position + 8 <= header.size) {
			val id = ascii(header, position, 4) ?: return null
			val size = leInt(header, position + 4)
			val body = position + 8

			when (id) {
				"fmt " -> {
					// A chunk that runs past the bytes we read cannot be copied
					// verbatim, and copying it partially would produce a header
					// whose fmt is truncated -- the exact bug this file exists
					// to remove.
					if (size < 16 || body + size > header.size) return null
					formatTag = leShort(header, body)
					channels = leShort(header, body + 2)
					sampleRate = leInt(header, body + 4)
					bitsPerSample = leShort(header, body + 14)
					// Keep the pad byte when the chunk is odd, so the rebuilt
					// layout stays chunk-aligned.
					val end = body + size + (size and 1)
					fmtChunk = header.copyOfRange(position, minOf(end, header.size))
				}

				"data" -> {
					val fmt = fmtChunk ?: return null
					// A negative size is the streamed marker and is meaningful
					// here; it is not rejected the way a negative chunk size
					// elsewhere in the file is.
					return Facts(
						riffSize = riffSize,
						formatTag = formatTag,
						channels = channels,
						sampleRate = sampleRate,
						bitsPerSample = bitsPerSample,
						dataOffset = body,
						dataSize = size,
						fmtChunk = fmt,
						header = header,
					)
				}
			}
			if (size < 0) return null
			position = body + size + (size and 1)
		}
		return null
	}

	/**
	 * The canonical header for this file, or null when there is nothing to fix.
	 *
	 * Null means "play it as it is": either the file is already canonical, or
	 * its format fields are too damaged to rebuild around (a header that says
	 * 0 channels cannot be repaired from the header alone, and guessing is
	 * worse than reporting the file as unplayable).
	 */
	fun repair(facts: Facts, totalBytes: Long): Repair? {
		if (!facts.plausible || !facts.playableFormat) return null
		val payload = facts.payloadBytes(totalBytes) ?: return null
		val pad = (payload and 1L).toInt()
		val dataOffset = 12 + facts.fmtChunk.size + 8
		val riffSize = 4 + facts.fmtChunk.size + 8 + payload.toInt() + pad

		// Already canonical: the two sizes agree with the real file and the
		// payload is the last thing in it. Note the pad byte is counted in the
		// file and in the RIFF size, but not in the data chunk's own size.
		val canonical = facts.riffSize.toLong() == totalBytes - 8 &&
			facts.dataSize.toLong() == payload &&
			facts.dataOffset.toLong() == dataOffset.toLong() &&
			totalBytes == dataOffset + payload + pad
		if (canonical) return null

		val header = ByteArray(dataOffset)
		asciiInto(header, 0, "RIFF")
		intInto(header, 4, riffSize)
		asciiInto(header, 8, "WAVE")
		facts.fmtChunk.copyInto(header, 12)
		asciiInto(header, 12 + facts.fmtChunk.size, "data")
		intInto(header, 12 + facts.fmtChunk.size + 4, payload.toInt())
		return Repair(header, facts.dataOffset.toLong(), payload, pad)
	}

	/**
	 * The whole file repaired, or null when it was already canonical.
	 *
	 * Used where the bytes are already in memory (synthesis); the player uses
	 * [repair] and streams the payload instead, because a long read-aloud can
	 * be tens of megabytes.
	 */
	fun canonicalize(bytes: ByteArray): ByteArray? {
		val facts = facts(bytes) ?: return null
		val repair = repair(facts, bytes.size.toLong()) ?: return null
		val payload = repair.payloadBytes.toInt()
		val out = ByteArray(repair.header.size + payload + repair.pad)
		repair.header.copyInto(out)
		val from = repair.dataOffset.toInt()
		bytes.copyInto(out, repair.header.size, from, from + payload)
		return out
	}

	// --- little-endian plumbing ----------------------------------------------

	private fun ascii(bytes: ByteArray, offset: Int, length: Int): String? {
		if (offset < 0 || offset + length > bytes.size) return null
		val chars = CharArray(length) { index ->
			val value = bytes[offset + index].toInt() and 0xff
			if (value !in 0x20..0x7e) return null
			value.toChar()
		}
		return String(chars)
	}

	private fun leShort(bytes: ByteArray, offset: Int): Int {
		if (offset < 0 || offset + 2 > bytes.size) return 0
		return (bytes[offset].toInt() and 0xff) or
			((bytes[offset + 1].toInt() and 0xff) shl 8)
	}

	private fun leInt(bytes: ByteArray, offset: Int): Int {
		if (offset < 0 || offset + 4 > bytes.size) return 0
		return (bytes[offset].toInt() and 0xff) or
			((bytes[offset + 1].toInt() and 0xff) shl 8) or
			((bytes[offset + 2].toInt() and 0xff) shl 16) or
			((bytes[offset + 3].toInt() and 0xff) shl 24)
	}

	private fun asciiInto(bytes: ByteArray, offset: Int, value: String) {
		for (index in value.indices) bytes[offset + index] = value[index].code.toByte()
	}

	private fun intInto(bytes: ByteArray, offset: Int, value: Int) {
		if (offset < 0 || offset + 4 > bytes.size) return
		for (index in 0..3) bytes[offset + index] = ((value ushr (8 * index)) and 0xff).toByte()
	}
}
