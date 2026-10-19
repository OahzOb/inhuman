package com.inhuman.ui.chat

/** The last fence line left open, with its zero-based line index. */
internal data class OpenFence(val marker: String, val lineIndex: Int)

/**
 * The fence still open at the end of [text], or null.
 *
 * This is a reading aid, not a Markdown parser: it only needs to answer "did
 * the model leave a block open" for the banner above an answer. A fence line
 * with an info string opens; only a line made entirely of the same marker, at
 * least as long as the opening one, closes. Fences inside an open block are
 * content, which is the case a parity-only version gets wrong.
 */
internal fun openFence(text: String): OpenFence? {
	var open: OpenFence? = null
	text.lineSequence().forEachIndexed { index, line ->
		val trimmedStart = line.trimStart()
		val marker = when {
			trimmedStart.startsWith("```") -> "```"
			trimmedStart.startsWith("~~~") -> "~~~"
			else -> null
		} ?: return@forEachIndexed
		val current = open
		if (current == null) {
			open = OpenFence(marker, index)
			return@forEachIndexed
		}
		val body = line.trim()
		if (body.length >= current.marker.length && body.all { it == current.marker[0] }) {
			open = null
		}
	}
	return open
}

/**
 * Remove the unmatched opening fence line, for display only.
 *
 * In the failure this exists for, the model writes one opening fence too many
 * and then continues with headings and a table. Appending a closing fence at
 * the end would leave that content inside the block, which is exactly the
 * complaint; dropping the stray opening line lets the rest render. The stored
 * message is never changed.
 */
internal fun withoutOpenFence(text: String): String? {
	val open = openFence(text) ?: return null
	val lines = text.lines().toMutableList()
	if (open.lineIndex !in lines.indices) return null
	lines.removeAt(open.lineIndex)
	return lines.joinToString("\n")
}
