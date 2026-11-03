package com.inhuman.ui.chat

import java.util.Base64

/**
 * Where the math image URLs live.
 *
 * `.invalid` is reserved by RFC 2606, so an accidental fetch can never succeed;
 * the custom image transformer intercepts every link with this prefix before an
 * image loader sees it. The formula travels in a URL-safe Base64 path segment
 * rather than a query string: the Markdown parser is allowed to percent-decode
 * a destination, and a decoded `&` or `)` would break the query or the image
 * syntax itself.
 */
internal const val MATH_URL_PREFIX = "https://inhuman.invalid/math/"

/**
 * A formula longer than this is shown as literal text instead of being handed to
 * the layout engine. The model writes the formula, so this is a work guard, not
 * a trust boundary; JLaTeXMath layout of a pathological 50 KB expression would
 * be a visible stall.
 */
internal const val MAX_MATH_CHARS = 2000

internal data class MathRef(
	val latex: String,
	val display: Boolean,
)

internal fun mathImageUrl(latex: String, display: Boolean): String =
	MATH_URL_PREFIX + (if (display) "d/" else "i/") +
		Base64.getUrlEncoder().withoutPadding()
			.encodeToString(latex.toByteArray(Charsets.UTF_8))

internal fun parseMathUrl(url: String): MathRef? {
	if (!url.startsWith(MATH_URL_PREFIX)) return null
	val rest = url.removePrefix(MATH_URL_PREFIX)
	val display = when {
		rest.startsWith("d/") -> true
		rest.startsWith("i/") -> false
		else -> return null
	}
	val encoded = rest.substring(2)
	if (encoded.isEmpty()) return null
	val latex = runCatching {
		String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
	}.getOrNull() ?: return null
	if (latex.isBlank() || latex.length > MAX_MATH_CHARS) return null
	return MathRef(latex, display)
}

/**
 * Rewrite LaTeX into image links the Markdown renderer already knows how to lay
 * out.
 *
 * ## Why image links
 *
 * The renderer has no math node. It does have an image node with a pluggable
 * transformer, and an inline image is a real inline placeholder in a paragraph:
 * text on either side wraps around it, and a formula alone in its paragraph
 * becomes a block. Reaching the same result with a separate formula composable
 * would mean splitting the Markdown, and splitting a paragraph loses the
 * formatting around it.
 *
 * ## Why [finished] exists
 *
 * A streaming snapshot can end in the middle of `$x^2`. Turning that prefix into
 * an image would be wrong, and leaving it as text would make the final answer
 * differ from what was emitted. So an unfinished construct is *withheld* -- the
 * output simply stops before it -- and only [finished] flushes it as literal
 * text. The output for a longer snapshot always starts with the output for a
 * shorter one, which is exactly what the append-only streaming parser needs;
 * `LatexMarkdownTest` asserts that property over every prefix of a corpus.
 *
 * Code fences, inline code and `\$` are copied through untouched. Inline math is
 * scoped to one line, and a `$` the heuristic suspects is currency is left
 * alone.
 */
internal object LatexMarkdown {

	private data class Fence(val char: Char, val length: Int)

	fun transform(text: String, finished: Boolean): String {
		// Most replies contain no maths, and this runs over the *whole*
		// accumulated answer for every streaming snapshot. Without this guard
		// the identity case still allocated a copy of the reply and walked it
		// character by character, 25 times a second (STREAM_PUBLISH_INTERVAL_MS
		// is 40), on the main thread. With no `$` there is nothing to rewrite:
		// every branch below copies its input verbatim.
		if ('$' !in text) return text
		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val atLineStart = i == 0 || text[i - 1] == '\n'
			if (atLineStart) {
				val fence = fenceAt(text, i)
				if (fence != null) {
					val end = fenceEnd(text, i, fence)
					if (end == null) {
						// An unclosed fence is copied whole. Its interior is code
						// either way, so more input cannot change this prefix.
						out.append(text, i, text.length)
						return out.toString()
					}
					out.append(text, i, end)
					i = end
					continue
				}
			}

			when (val c = text[i]) {
				'`' -> {
					val run = runLength(text, i, '`')
					val close = findCodeSpanClose(text, i + run, run)
					if (close < 0) {
						// No closing run on this line. At the end of a snapshot a
						// closing backtick may still be one token away, and until
						// it arrives the `$` inside must not become an image;
						// withhold the run. Once a line break is present the run
						// cannot close on its own line and is literal.
						val lineEnd = text.indexOf('\n', i + run).let { if (it < 0) text.length else it }
						if (lineEnd == text.length && !finished) return out.toString()
						out.append(text, i, i + run)
						i += run
					} else {
						out.append(text, i, close + run)
						i = close + run
					}
				}

				'\\' -> {
					val take = if (i + 1 < text.length) 2 else 1
					out.append(text, i, i + take)
					i += take
				}

				'$' -> {
					if (i + 1 < text.length && text[i + 1] == '$') {
						val close = text.indexOf("$$", i + 2)
						if (close < 0) {
							if (!finished) return out.toString()
							out.append(text, i, text.length)
							return out.toString()
						}
						val latex = text.substring(i + 2, close)
						if (renderable(latex, display = true)) {
							out.append("\n\n![math](")
								.append(mathImageUrl(latex.trim(), display = true))
								.append(")\n\n")
						} else {
							out.append(text, i, close + 2)
						}
						i = close + 2
					} else {
						val lineEnd = text.indexOf('\n', i + 1).let { if (it < 0) text.length else it }
						val next = text.getOrNull(i + 1)
						if (next == null) {
							// A dollar at the very end of this snapshot may be the
							// opening of a formula one token away. Withholding it
							// keeps the streaming output prefix-stable; `finished`
							// emits it literally.
							if (!finished) return out.toString()
							out.append(c)
							i += 1
							continue
						}
						if (next.isWhitespace()) {
							// A formula cannot begin with whitespace, so this is a
							// literal dollar and the decision is final.
							out.append(c)
							i += 1
							continue
						}
						val close = inlineClose(text, i + 1, lineEnd)
						if (close < 0) {
							if (lineEnd < text.length) {
								// A line break ends the opportunity to close; the
								// dollar is literal and the line can be emitted.
								out.append(text, i, lineEnd + 1)
								i = lineEnd + 1
							} else {
								if (!finished) return out.toString()
								out.append(text, i, text.length)
								return out.toString()
							}
						} else {
							val latex = text.substring(i + 1, close)
							if (renderable(latex, display = false)) {
								out.append("![math](")
									.append(mathImageUrl(latex.trim(), display = false))
									.append(")")
							} else {
								out.append(text, i, close + 1)
							}
							i = close + 1
						}
					}
				}

				else -> {
					out.append(c)
					i += 1
				}
			}
		}
		return out.toString()
	}

	private fun renderable(latex: String, display: Boolean): Boolean {
		val trimmed = latex.trim()
		if (trimmed.isEmpty() || trimmed.length > MAX_MATH_CHARS) return false
		return display || '\n' !in trimmed
	}

	/** A fence opener at [start]: up to three spaces, then at least three ` or ~. */
	private fun fenceAt(text: String, start: Int): Fence? {
		var i = start
		var spaces = 0
		while (i < text.length && text[i] == ' ' && spaces < 3) {
			i += 1
			spaces += 1
		}
		val char = text.getOrNull(i) ?: return null
		if (char != '`' && char != '~') return null
		val run = runLength(text, i, char)
		if (run < 3) return null
		return Fence(char, run)
	}

	/**
	 * The index just past the closing fence, or null when the fence never closes.
	 *
	 * A closing fence must be at line start (up to three spaces), use the same
	 * character, and be at least as long as the opener.
	 */
	private fun fenceEnd(text: String, start: Int, fence: Fence): Int? {
		var line = text.indexOf('\n', start)
		while (line >= 0 && line + 1 < text.length) {
			val next = line + 1
			var i = next
			var spaces = 0
			while (i < text.length && text[i] == ' ' && spaces < 3) {
				i += 1
				spaces += 1
			}
			if (text.getOrNull(i) == fence.char) {
				val run = runLength(text, i, fence.char)
				if (run >= fence.length) {
					val end = text.indexOf('\n', i + run)
					return if (end < 0) text.length else end + 1
				}
			}
			line = text.indexOf('\n', next)
		}
		return null
	}

	private fun runLength(text: String, start: Int, char: Char): Int {
		var i = start
		while (i < text.length && text[i] == char) i += 1
		return i - start
	}

	/** The start of a closing backtick run of exactly [length] on this line. */
	private fun findCodeSpanClose(text: String, from: Int, length: Int): Int {
		var i = from
		while (i < text.length && text[i] != '\n') {
			if (text[i] == '`') {
				val run = runLength(text, i, '`')
				if (run == length) return i
				i += run
			} else {
				i += 1
			}
		}
		return -1
	}

	/** The first closing `$` that looks like a math delimiter, not currency. */
	private fun inlineClose(text: String, from: Int, lineEnd: Int): Int {
		var i = from
		while (i < lineEnd) {
			when (text[i]) {
				'\\' -> i += 2
				'$' -> {
					val previous = text[i - 1]
					val next = text.getOrNull(i + 1)
					// `$5 and $10` must not become the formula "5 and ". A
					// closing delimiter preceded by whitespace, or followed by a
					// digit, is currency-shaped; leave the dollar alone.
					if (!previous.isWhitespace() && next?.isDigit() != true) return i
					i += 1
				}
				else -> i += 1
			}
		}
		return -1
	}
}