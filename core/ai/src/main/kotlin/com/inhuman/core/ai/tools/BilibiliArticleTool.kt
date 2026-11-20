package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliArticle
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import kotlinx.serialization.json.JsonObject

/**
 * Read one Bilibili column (专栏) article as plain text.
 *
 * ## Why the body is capped like `web_fetch`
 *
 * A long article is tens of thousands of tokens, and this result is context for
 * the rest of the conversation. The cap is a truncation that announces itself;
 * a model that reads a clean ending assumes it read the whole article, and that
 * assumption is what the footer exists to prevent. The same ceiling is used by
 * `web_fetch` for the same reason.
 *
 * ## Why only column ids
 *
 * Bilibili also has a newer "opus" article shape with a different id and a
 * different response. Accepting it half-way would produce empty bodies that
 * look like empty articles; the tool refuses the shape and says so instead.
 */
class BilibiliArticleTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_article"

	override val mutates = false

	override val description = """
		Read one Bilibili column article (专栏) as plain text, with its title, author, date,
		stats and tags.

		Pass the cv id (for example cv34975835, or just 34975835). The body is truncated when
		it is very long and the result says so; if the ending looks cut off, say that rather
		than assuming the article ended there. Anonymous read: no account, no key.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"id" to mapOf(
				"type" to "string",
				"description" to "The column's cv id, as cv34975835 or 34975835. Not a URL.",
			),
		),
		"required" to listOf("id"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val cvId = BilibiliToolSupport.cvId(arguments)
			?: return ToolResult.error(
				"missing or invalid 'id': expected a column cv id such as cv34975835",
			)
		return try {
			val article = service.article(cvId)
			if (article.body.isBlank()) {
				ToolResult.error(
					"the column cv$cvId has no readable text body; it may contain only images",
				)
			} else {
				ToolResult.ok(format(article))
			}
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili article read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(article: BilibiliArticle): String = buildString {
		appendLine("cv${article.cvId} — ${BilibiliToolSupport.oneLine(article.title, TITLE_CHARS)}")
		appendLine(
			"author: ${BilibiliToolSupport.oneLine(article.author, AUTHOR_CHARS)}" +
				" · published: ${BilibiliToolSupport.date(article.publishTimeSeconds)}" +
				if (article.words > 0) " · ${BilibiliToolSupport.number(article.words)} words" else "",
		)
		appendLine(
			"stats: ${BilibiliToolSupport.number(article.stats.view)} views" +
				" · ${BilibiliToolSupport.number(article.stats.like)} likes" +
				" · ${BilibiliToolSupport.number(article.stats.coin)} coins" +
				" · ${BilibiliToolSupport.number(article.stats.favorite)} favourites" +
				" · ${BilibiliToolSupport.number(article.stats.reply)} comments",
		)
		if (article.tags.isNotEmpty()) {
			appendLine("tags: ${article.tags.joinToString(", ") { BilibiliToolSupport.oneLine(it, TAG_CHARS) }}")
		}
		appendLine()
		appendLine("body:")
		appendLine(article.body.take(MAX_BODY_CHARS).trimEnd())
		if (article.body.length > MAX_BODY_CHARS) {
			appendLine()
			appendLine("[truncated at $MAX_BODY_CHARS characters; the column is longer]")
		}
	}

	companion object {
		/**
		 * The same ceiling `web_fetch` uses: about 5k tokens, enough for an
		 * ordinary article and small enough that a conversation survives it.
		 */
		const val MAX_BODY_CHARS = 20_000
		private const val TITLE_CHARS = 200
		private const val AUTHOR_CHARS = 60
		private const val TAG_CHARS = 40
	}
}
