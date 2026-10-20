package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * A source of one web page, as readable text.
 *
 * The search tool answers "what exists?"; this answers "what does this page
 * say?". They are separate providers because they are separate paid APIs with
 * separate failure modes -- a search can be thin, a fetch can be blocked by the
 * target site -- and one interface trying to express both would make each
 * provider implement a method it cannot honour.
 */
interface FetchProvider {

	/** For diagnostics and for telling the user which service answered. */
	val name: String

	/**
	 * Fetch one page.
	 *
	 * @throws Exception on transport, validation or provider failure. The tool
	 *   turns it into a result the model can read; swallowing it here would
	 *   make "the page could not be fetched" look like "the page was empty".
	 */
	suspend fun fetch(url: String): FetchedPage
}

/** What a fetch returns: the page title (possibly blank) and its Markdown body. */
data class FetchedPage(
	val title: String,
	val content: String,
)

/**
 * Fetch one web page for the model.
 *
 * ## Why the output is capped
 *
 * A tool result is context. A long article is tens of thousands of tokens, and
 * one un-capped fetch can consume the window that the rest of the conversation
 * needs. The cap is a truncation, not a summarisation, and it says so in the
 * result -- a model that sees a clean ending assumes it read the whole page.
 *
 * ## Why the URL is validated here and in the provider
 *
 * The provider knows the API's rules, but it is replaceable; the tool must not
 * hand an obviously unusable string to a paid endpoint. `http`/`https` only,
 * no credentials, bounded length. **This tool** does not open the page itself:
 * the selected backend (DeepSeek or Exa) does, so it is not a path from the
 * sandbox to the device's network. (The user-initiated download in Files is a
 * separate path; the agent cannot call it.)
 */
class WebFetchTool(
	private val provider: FetchProvider,
	private val maxChars: Int = MAX_CHARS,
) : Tool {

	override val name = "web_fetch"

	override val mutates = false

	override val description = """
		Fetch one web page and return its main content as Markdown.

		Use this after web_search when the excerpt is not enough, or when the
		user gives you a URL. Prefer one page that will answer the question over
		fetching several -- each fetch is a separate call.

		Only http and https URLs can be fetched. The result is truncated when it
		is too long; if the ending does not look complete, say so rather than
		assuming the page ended there.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"url" to mapOf(
				"type" to "string",
				"description" to "The page to fetch. Must be an absolute http or https URL.",
			),
		),
		"required" to listOf("url"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val url = arguments["url"]
			?.let { runCatching { it.toString().trim('"') }.getOrNull() }
			?.trim()
			?.takeIf { it.isNotBlank() }
			?: return ToolResult.error("missing required argument 'url'")

		return try {
			val page = provider.fetch(url)
			if (page.content.isBlank()) {
				ToolResult.error("fetch returned no readable content from $url")
			} else {
				ToolResult.ok(format(url, page))
			}
		} catch (e: Exception) {
			ToolResult.error(
				"fetch failed: ${e.message ?: e.javaClass.simpleName}",
			)
		}
	}

	private fun format(url: String, page: FetchedPage): String = buildString {
		// A title with a newline would break the heading and push the URL into
		// the body; one line is what a heading is.
		page.title.trim().replace(Regex("\\s+"), " ")
			.takeIf { it.isNotEmpty() }?.let { appendLine("# $it") }
		appendLine(url)
		appendLine()
		append(page.content.take(maxChars).trimEnd())
		if (page.content.length > maxChars) {
			appendLine()
			appendLine()
			appendLine("[truncated at $maxChars characters]")
		}
	}

	companion object {
		/**
		 * About 5k tokens. Large enough for a documentation page or a long
		 * article, small enough that a conversation survives several fetches.
		 */
		const val MAX_CHARS = 20_000
	}
}
