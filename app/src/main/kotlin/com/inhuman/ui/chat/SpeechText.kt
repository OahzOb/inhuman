package com.inhuman.ui.chat

/**
 * Turn an assistant reply from Markdown into text worth hearing.
 *
 * ## Why this exists
 *
 * The reply on screen is Markdown: headings, bullets, emphasis, links, fenced
 * code and tables. Handing that source to a speech service reads the syntax
 * aloud ("hash hash heading", "asterisk asterisk") and bills characters that
 * are not words. Mature chat clients read the prose; this is the same intent in
 * one pure function, which is also why it can be tested without a device.
 *
 * Code and tables are announced once instead of read token by token: a phone
 * user listening to an answer does not need an identifier-by-identifier
 * reading of a code block, and a table read row by row is unintelligible.
 */
object SpeechText {

	fun fromMarkdown(markdown: String): String {
		if (markdown.isBlank()) return ""
		val input = markdown.replace("\r\n", "\n").replace('\r', '\n')
		val out = StringBuilder()
		var inFence = false
		var fenceAnnounced = false
		var inTable = false

		for (rawLine in input.lineSequence()) {
			val line = rawLine.trim()
			if (line.startsWith("```") || line.startsWith("~~~")) {
				if (!inFence) {
					inFence = true
					if (!fenceAnnounced) {
						out.append("代码块。\n")
						fenceAnnounced = true
					}
				} else {
					inFence = false
				}
				continue
			}
			if (inFence) continue

			if (line.isEmpty()) {
				out.append('\n')
				continue
			}
			if (line.startsWith("|")) {
				if (!inTable) {
					out.append("表格。\n")
					inTable = true
				}
				continue
			}
			inTable = false
			if (HORIZONTAL_RULE.matches(line)) continue

			var body = HEADING.replace(line, "")
			body = QUOTE.replace(body, "")
			body = BULLET.replace(body, "")
			body = IMAGE.replace(body) { it.groupValues[1] }
			body = LINK.replace(body, "$1")
			body = INLINE_CODE.replace(body, "$1")
			body = BOLD_STAR.replace(body, "$1")
			body = BOLD_UNDERSCORE.replace(body, "$1")
			body = ITALIC_STAR.replace(body, "$1")
			body = HTML_TAG.replace(body, "")
			out.append(body).append('\n')
		}

		return out.toString()
			.replace(Regex("\\n{3,}"), "\n\n")
			.trim()
	}

	private val HORIZONTAL_RULE = Regex("^[-*_]\\s*[-*_]\\s*[-*_\\s]*$")
	private val HEADING = Regex("^#{1,6}\\s+")
	private val QUOTE = Regex("^>\\s?")
	private val BULLET = Regex("^(?:[-*+]|\\d+[.)])\\s+")
	private val IMAGE = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
	private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
	private val INLINE_CODE = Regex("`{1,3}([^`]*)`{1,3}")
	private val BOLD_STAR = Regex("\\*\\*([^*]+)\\*\\*")
	private val BOLD_UNDERSCORE = Regex("__([^_]+)__")
	private val ITALIC_STAR = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
	private val HTML_TAG = Regex("<[^>]+>")
}
