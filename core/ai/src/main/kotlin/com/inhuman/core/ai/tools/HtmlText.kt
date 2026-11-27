package com.inhuman.core.ai.tools

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream

/**
 * Turns a fetched HTML page into readable text.
 *
 * ## Why extraction lives here rather than in a provider
 *
 * Every local fetch path needs the same answer, and "what a page says" is the
 * product promise; a provider that returned raw HTML would put markup, hidden
 * nodes and script bodies into the model's context. This object is pure and
 * fixture-testable, which is also why the WebView renderer can hand its DOM to
 * the same rules instead of inventing a second extraction.
 *
 * ## What is deliberately discarded
 *
 * `script`, `style`, `noscript`, `iframe`, `svg`, `canvas`, `template`, `form`,
 * `nav`, `footer`, `header` and `aside` are removed before reading: they are
 * either not visible or not the page's content, and script/style bodies are a
 * prompt-injection surface with no reading value. What remains is text.
 *
 * The marker trick is how paragraph boundaries survive: jsoup's `text()` would
 * collapse the whole page into one line, so block boundaries are marked before
 * `wholeText()` and turned into newlines afterwards.
 */
object HtmlText {

	data class Extracted(
		val title: String,
		val text: String,
	)

	fun extract(html: String, baseUri: String = ""): Extracted =
		extract(Jsoup.parse(html, baseUri))

	fun extract(bytes: ByteArray, contentType: String?, baseUri: String): Extracted {
		val charset = charsetOf(contentType)
		val document = if (charset != null) {
			Jsoup.parse(ByteArrayInputStream(bytes), charset, baseUri)
		} else {
			// jsoup sniffs `<meta charset>` when no charset is given, which is
			// what most Chinese news sites rely on.
			Jsoup.parse(ByteArrayInputStream(bytes), null, baseUri)
		}
		return extract(document)
	}

	/** One line of text from a fragment, for search-result snippets. */
	fun text(html: String): String = Jsoup.parse(html).text().trim()

	internal fun extract(document: Document): Extracted {
		document.select(
			"script, style, noscript, iframe, svg, canvas, template, form, " +
				"nav, footer, header, aside",
		).remove()

		val root = document.selectFirst("article")
			?: document.selectFirst("main")
			?: document.selectFirst("[role=main]")
			?: document.body()

		val title = document.title().trim()
			.ifBlank { root.selectFirst("h1")?.text()?.trim().orEmpty() }
		return Extracted(title = title, text = renderText(root))
	}

	internal fun renderText(root: Element): String {
		val clone = root.clone()
		clone.select("br").forEach { it.after(BLOCK_MARKER) }
		clone.select(BLOCK_TAGS.joinToString(", ")).forEach { it.append(BLOCK_MARKER) }
		val raw = clone.wholeText().replace(BLOCK_MARKER, "\n")
		val lines = raw.lines()
			.map { line -> line.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
			.filter { it.isNotEmpty() }
		return lines.joinToString("\n")
			.replace(Regex("\n{3,}"), "\n\n")
			.trim()
	}

	/** `charset=` from a Content-Type header, if it names a usable charset. */
	fun charsetOf(contentType: String?): String? {
		val header = contentType ?: return null
		val index = header.lowercase().indexOf("charset=")
		if (index < 0) return null
		val value = header.substring(index + "charset=".length)
			.substringBefore(';')
			.trim()
			.trim('"', '\'')
		return value.takeIf { it.isNotBlank() }
	}

	private const val BLOCK_MARKER = "\u0001"

	private val BLOCK_TAGS = listOf(
		"p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
		"blockquote", "pre", "section", "article", "ul", "ol", "table",
		"thead", "tbody", "tfoot", "hr", "dd", "dt", "figcaption",
	)
}
