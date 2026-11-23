package com.inhuman.ui.chat

/**
 * Sentence-level captions for a synthesized reply.
 *
 * ## What this can and cannot know
 *
 * The speech endpoints return audio bytes and a duration, not word timestamps.
 * So the mapping below is an estimate: split the text into sentence-sized
 * captions and give each one a slice of the known duration proportional to its
 * visible character count. TTS rate is roughly constant, so the notification's
 * caption follows the audio closely enough to read along; it is not a forced
 * alignment and must not be described as one.
 *
 * Kept pure and tested because there is no way to observe the notification at
 * every instant from `adb`, and an off-by-one in the ranges is invisible by eye
 * on a 20-second sample.
 */
object SpeechCaptions {

	/** One caption and the slice of playback it is expected to cover. */
	data class Segment(
		val text: String,
		val startMs: Int,
		val endMs: Int,
	)

	/** A sentence is kept readable; this is the longest caption we hand a notification. */
	private const val MAX_SEGMENT_CHARS = 90

	/**
	 * Split [text] into captions and distribute [durationMs] over them.
	 *
	 * Returns an empty list when there is no text or no duration; callers then
	 * fall back to the plain time line.
	 */
	fun segments(text: String, durationMs: Int): List<Segment> {
		if (text.isBlank() || durationMs <= 0) return emptyList()
		val pieces = split(text)
		if (pieces.isEmpty()) return emptyList()
		val weights = pieces.map { visibleLength(it).toDouble().coerceAtLeast(1.0) }
		val totalWeight = weights.sum().coerceAtLeast(1.0)
		var consumedWeight = 0.0
		var consumedMs = 0
		return pieces.mapIndexed { index, piece ->
			val start = consumedMs
			consumedWeight += weights[index]
			val end = if (index == pieces.lastIndex) {
				durationMs
			} else {
				(consumedWeight / totalWeight * durationMs).toInt().coerceAtLeast(start)
			}
			consumedMs = end
			Segment(piece, start, end)
		}
	}

	/** The caption covering [positionMs], with the last caption held to the end. */
	fun segmentAt(segments: List<Segment>, positionMs: Int): Segment? {
		if (segments.isEmpty()) return null
		val position = positionMs.coerceAtLeast(0)
		return segments.firstOrNull { position in it.startMs until it.endMs }
			?: segments.last()
	}

	private fun split(text: String): List<String> {
		val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
		val sentences = mutableListOf<String>()
		val current = StringBuilder()
		fun flush() {
			val trimmed = current.toString().trim()
			if (trimmed.isNotEmpty()) sentences += trimmed
			current.clear()
		}
		for (char in normalized) {
			current.append(char)
			if (char == '\n' || char in SENTENCE_END) flush()
		}
		flush()

		// One long paragraph is one long notification line; cut the extra-long
		// sentences at a readable size, preferring a space when there is one.
		val bounded = mutableListOf<String>()
		for (sentence in sentences) {
			if (sentence.length <= MAX_SEGMENT_CHARS) {
				bounded += sentence
				continue
			}
			var rest = sentence
			while (rest.length > MAX_SEGMENT_CHARS) {
				val window = rest.take(MAX_SEGMENT_CHARS)
				val cut = window.lastIndexOf(' ').takeIf { it > MAX_SEGMENT_CHARS / 2 }
					?: MAX_SEGMENT_CHARS
				bounded += rest.take(cut).trim()
				rest = rest.drop(cut).trim()
			}
			if (rest.isNotEmpty()) bounded += rest
		}
		return bounded.filter { it.isNotBlank() }
	}

	private fun visibleLength(text: String): Int =
		text.count { !it.isWhitespace() }

	private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', ';', '；', '…')
}
