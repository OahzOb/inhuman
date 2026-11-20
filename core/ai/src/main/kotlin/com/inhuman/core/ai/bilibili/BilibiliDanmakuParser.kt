package com.inhuman.core.ai.bilibili

/**
 * A minimal protobuf reader for the danmaku segment endpoints.
 *
 * ## Why not a protobuf dependency
 *
 * The whole wire surface used here is two messages: the segment configuration
 * (`dm/web/view`) and the repeated comments inside one segment (`seg.so`). A
 * generator and a runtime for that would be a build dependency and an APK
 * liability, while the reader below is a varint loop that can be tested with a
 * handful of byte arrays. The format is stable, length-delimited and documented
 * by Bilibili's own published `.proto`; what changes is which fields exist, and
 * unknown fields are skipped by design.
 *
 * ## Why the reader is total
 *
 * A truncated or re-encoded response must degrade, not throw in the middle of a
 * tool call: a segment that yields no danmaku is "Bilibili returned nothing",
 * which the tool says plainly. Running off the end of the buffer ends parsing
 * and returns what was read.
 */
internal object BilibiliDanmakuParser {

	/** The number of six-minute windows and their length, from `dm/web/view`. */
	data class SegmentConfig(
		val segmentMillis: Long,
		val segmentCount: Int,
	)

	fun parseSegmentConfig(bytes: ByteArray): SegmentConfig? {
		val reader = Reader(bytes)
		var segmentMillis = 0L
		var segmentCount = 0
		while (true) {
			val (field, wire) = reader.nextField() ?: break
			if (field == 4 && wire == WIRE_LENGTH) {
				val nested = Reader(reader.readBytes() ?: break)
				while (true) {
					val (innerField, innerWire) = nested.nextField() ?: break
					if (innerWire != WIRE_VARINT) {
						if (!nested.skip(innerWire)) break
						continue
					}
					val value = nested.readVarint() ?: break
					when (innerField) {
						1 -> segmentMillis = value
						2 -> segmentCount = value.toInt()
					}
				}
			} else if (!reader.skip(wire)) {
				break
			}
		}
		// Sanity: a segment is minutes, not microseconds, and a video does not
		// have thousands of them. A wrong parse must read as "unknown" rather
		// than make the tool divide by a 1970-style number.
		if (segmentMillis <= 0 || segmentMillis > 3_600_000L) return null
		if (segmentCount <= 0 || segmentCount > 1_000) return null
		return SegmentConfig(segmentMillis = segmentMillis, segmentCount = segmentCount)
	}

	/**
	 * The danmaku lines of one segment.
	 *
	 * Only the fields a reader can see are kept: the start time, the mode and
	 * the text. Advanced and scripted danmaku (mode 7+) are dropped because
	 * their payload is not plain text and would be noise. The endpoint returns a
	 * bounded sample per segment; this reader does not claim otherwise.
	 */
	fun parseSegment(bytes: ByteArray): List<BilibiliDanmakuLine> {
		val lines = ArrayList<BilibiliDanmakuLine>()
		val reader = Reader(bytes)
		while (true) {
			val (field, wire) = reader.nextField() ?: break
			if (field != 1 || wire != WIRE_LENGTH) {
				if (!reader.skip(wire)) break
				continue
			}
			val entry = reader.readBytes() ?: break
			val line = parseEntry(entry) ?: continue
			lines.add(line)
		}
		return lines
	}

	private fun parseEntry(bytes: ByteArray): BilibiliDanmakuLine? {
		val reader = Reader(bytes)
		var progressMillis = -1L
		var mode = 0
		var text: String? = null
		while (true) {
			val (field, wire) = reader.nextField() ?: break
			when {
				field == 2 && wire == WIRE_VARINT ->
					progressMillis = reader.readVarint() ?: -1L
				field == 3 && wire == WIRE_VARINT ->
					mode = (reader.readVarint() ?: 0L).toInt()
				field == 7 && wire == WIRE_LENGTH ->
					text = reader.readBytes()?.let(::decodeText)
				else -> if (!reader.skip(wire)) break
			}
		}
		val content = text?.trim()?.replace('\n', ' ') ?: return null
		if (content.isEmpty() || content.length > MAX_TEXT_CHARS) return null
		// Modes 1-3 scroll, 4-5 are bottom/top, 6 reverses. Anything above is
		// an advanced/code danmaku whose payload is not a readable comment.
		if (mode !in 1..6) return null
		if (progressMillis < 0) return null
		return BilibiliDanmakuLine(atSeconds = progressMillis / 1000L, text = content)
	}

	/** Bilibili sends UTF-8; invalid bytes become the replacement character. */
	private fun decodeText(bytes: ByteArray): String =
		try {
			String(bytes, Charsets.UTF_8)
		} catch (_: Exception) {
			""
		}

	private const val WIRE_VARINT = 0
	private const val WIRE_FIXED64 = 1
	private const val WIRE_LENGTH = 2
	private const val WIRE_FIXED32 = 5
	private const val MAX_TEXT_CHARS = 120

	/** A cursor over one message. Every read returns null at the buffer's end. */
	private class Reader(private val bytes: ByteArray) {
		private var index = 0

		fun nextField(): Pair<Int, Int>? {
			if (index >= bytes.size) return null
			val key = readVarint() ?: return null
			val field = (key ushr 3).toInt()
			val wire = (key and 0x7L).toInt()
			if (field <= 0) return null
			return field to wire
		}

		fun readVarint(): Long? {
			var result = 0L
			var shift = 0
			while (shift < 64) {
				if (index >= bytes.size) return null
				val byte = bytes[index++].toInt() and 0xFF
				result = result or ((byte and 0x7F).toLong() shl shift)
				if (byte and 0x80 == 0) return result
				shift += 7
			}
			return null
		}

		fun readBytes(): ByteArray? {
			val length = readVarint() ?: return null
			if (length < 0 || length > bytes.size - index) return null
			val start = index
			index += length.toInt()
			return bytes.copyOfRange(start, index)
		}

		fun skip(wire: Int): Boolean = when (wire) {
			WIRE_VARINT -> readVarint() != null
			WIRE_FIXED64 -> skipFixed(8)
			WIRE_LENGTH -> readBytes() != null
			WIRE_FIXED32 -> skipFixed(4)
			// Group wire types (3/4) do not appear in these messages; stopping is
			// the safe answer for an unknown encoding.
			else -> false
		}

		private fun skipFixed(count: Int): Boolean {
			if (bytes.size - index < count) return false
			index += count
			return true
		}
	}
}
