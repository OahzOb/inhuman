package com.inhuman.core.ai.bilibili

/**
 * Turns Bilibili's two HTML shapes into plain text.
 *
 * Search results put `<em class="keyword">` around the match inside an
 * otherwise plain title. Column articles return a full HTML body. Both are
 * *untrusted third-party text* — it becomes a tool result, so it must arrive as
 * data the model reads, never as markup the UI might render.
 *
 * ## Why a hand-written converter rather than an HTML parser
 *
 * Pulling `jsoup` (or any parser) into a plain-JVM core module for this one
 * shape is a dependency and a supply-chain surface for a job that is "drop
 * tags, keep text, keep paragraphs apart". The converter is deliberately
 * forgiving: a truncated document from a fixture, an unclosed tag, a stray
 * `&` — the output should still be readable rather than throw. A parser is
 * better at validity; this job is recovering text from something that may be
 * invalid.
 */
internal object BilibiliHtml {

	private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>")
	private val FIG_CAPTION = Regex("(?is)<figcaption\\b[^>]*>.*?</figcaption>")

	/** `<br>` is a line break, not a paragraph. */
	private val LINE_BREAK = Regex("(?i)<br\\s*/?>")
	private val BLOCK_END = Regex("(?i)</(p|div|h[1-6]|li|blockquote|figure|tr)>")
	private val LIST_ITEM = Regex("(?i)<li\\b[^>]*>")
	private val ANY_TAG = Regex("(?s)<[^>]+>")

	private val TABS_AND_RETURNS = Regex("[\\t\\u000B\\f\\r]")
	private val SPACES_AROUND_NEWLINE = Regex(" *\\n *")
	private val EXTRA_BLANK_LINES = Regex("\\n{3,}")
	private val WHITESPACE = Regex("\\s+")
	private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")

	/** A title or one-line description: no tags, one line, no entities. */
	fun stripTags(html: String): String {
		val withoutTags = html.replace(ANY_TAG, "")
		return decodeEntities(withoutTags).replace(WHITESPACE, " ").trim()
	}

	/** A column body: paragraphs and list markers kept, structure flattened. */
	fun toText(html: String): String {
		var text = html
		text = SCRIPT_OR_STYLE.replace(text, " ")
		text = FIG_CAPTION.replace(text, " ")
		text = LINE_BREAK.replace(text, "\n")
		text = BLOCK_END.replace(text, "\n")
		text = LIST_ITEM.replace(text, "\n- ")
		text = ANY_TAG.replace(text, "")
		text = decodeEntities(text)
		text = TABS_AND_RETURNS.replace(text, " ")
		text = SPACES_AROUND_NEWLINE.replace(text, "\n")
		text = EXTRA_BLANK_LINES.replace(text, "\n\n")
		return text.trim()
	}

	private fun decodeEntities(text: String): String {
		val numeric = NUMERIC_ENTITY.replace(text) { match ->
			val hex = match.groupValues[1].isNotEmpty()
			val raw = match.groupValues[2]
			val code = raw.toLongOrNull(if (hex) 16 else 10)
			if (code == null || code <= 0 || code > 0x10FFFF) {
				match.value
			} else {
				String(Character.toChars(code.toInt()))
			}
		}
		return numeric
			.replace("&nbsp;", " ")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.replace("&apos;", "'")
			// Last, so an encoded entity (`&amp;lt;`) stays text instead of
			// being decoded twice into a tag-like `<`.
			.replace("&amp;", "&")
	}
}
