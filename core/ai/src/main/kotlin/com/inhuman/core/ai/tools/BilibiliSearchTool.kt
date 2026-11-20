package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import com.inhuman.core.ai.bilibili.BilibiliSearchHit
import com.inhuman.core.ai.bilibili.BilibiliSearchKind
import kotlinx.serialization.json.JsonObject

/**
 * Search Bilibili videos or column articles, anonymously.
 *
 * ## Why the kind is a parameter instead of two tools
 *
 * Both searches are the same endpoint with one differing field, the same output
 * shape and the same failure modes. Two tools would double the rows in every
 * picker and make the model choose between two names that differ by one word;
 * one tool with a `kind` argument is a choice it already makes on other tools.
 *
 * ## Why results are formatted rather than returned as JSON
 *
 * The result is context. JSON punctuation for four numbers costs tokens and
 * hides the one thing the model needs to cite (the BV/cv id). The layout is
 * also what the cap is measured against: a result list the model cannot see the
 * end of is worse than a shorter list that says how many were omitted.
 */
class BilibiliSearchTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_search"

	/** Read-only, remote-content, no account. Replaying it costs a request. */
	override val mutates = false

	override val description = """
		Search Bilibili for videos or column articles and get titles, ids, authors, stats and dates.

		Use `bili_video` with a returned BV id to read a video's details, `bili_comments` for its
		comments, and `bili_article` with a returned cv id to read a column's text. The search
		itself returns no page content. It is anonymous: no Bilibili account is involved, and the
		app makes the request, not you.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"keyword" to mapOf(
				"type" to "string",
				"description" to "What to search for on Bilibili, up to 100 characters.",
			),
			"kind" to mapOf(
				"type" to "string",
				"enum" to listOf("video", "article"),
				"description" to "What to search for: videos (default) or column articles.",
			),
			"order" to mapOf(
				"type" to "string",
				"enum" to listOf("default", "latest", "popular"),
				"description" to "Sort order: relevance (default), newest first, or most popular.",
			),
			"limit" to mapOf(
				"type" to "integer",
				"description" to "How many results to return, 1 to $MAX_LIMIT. Defaults to 5.",
			),
			"page" to mapOf(
				"type" to "integer",
				"description" to "Search result page, 1 to $MAX_PAGE. Defaults to 1.",
			),
		),
		"required" to listOf("keyword"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val keyword = BilibiliToolSupport.keyword(arguments)
			?: return ToolResult.error("missing required argument 'keyword'")
		val kind = when (BilibiliToolSupport.string(arguments, "kind")?.lowercase()) {
			null, "video" -> BilibiliSearchKind.VIDEO
			"article" -> BilibiliSearchKind.ARTICLE
			else -> return ToolResult.error("'kind' must be either 'video' or 'article'")
		}
		val sort = when (BilibiliToolSupport.string(arguments, "order")?.lowercase()) {
			null, "default" -> "totalrank"
			"latest" -> "pubdate"
			"popular" -> "click"
			else -> return ToolResult.error("'order' must be 'default', 'latest' or 'popular'")
		}
		val limit = BilibiliToolSupport.int(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
		val page = BilibiliToolSupport.int(arguments, "page", 1, 1, MAX_PAGE)

		return try {
			val hits = service.search(keyword, kind, sort, page, limit)
			if (hits.isEmpty()) {
				ToolResult.ok("No Bilibili ${kind.label} results for \"$keyword\" (page $page).")
			} else {
				ToolResult.ok(format(keyword, kind, page, hits))
			}
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili search failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(
		keyword: String,
		kind: BilibiliSearchKind,
		page: Int,
		hits: List<BilibiliSearchHit>,
	): String = buildString {
		appendLine(
			"Bilibili search \"$keyword\" (${kind.label}) — ${hits.size} result(s), page $page",
		)
		hits.forEachIndexed { index, hit ->
			appendLine()
			when (hit) {
				is BilibiliSearchHit.Video -> {
					appendLine("${index + 1}. ${BilibiliToolSupport.oneLine(hit.title, TITLE_CHARS)}")
					appendLine(
						"   ${hit.bvid} · ${BilibiliToolSupport.oneLine(hit.author, AUTHOR_CHARS)}" +
							" · ${BilibiliToolSupport.number(hit.play)} plays" +
							" · ${BilibiliToolSupport.number(hit.commentCount)} comments" +
							" · ${hit.duration.ifBlank { "unknown length" }}" +
							" · ${BilibiliToolSupport.date(hit.pubdateSeconds)}",
					)
					if (hit.description.isNotBlank()) {
						appendLine("   ${BilibiliToolSupport.oneLine(hit.description, SNIPPET_CHARS)}")
					}
				}
				is BilibiliSearchHit.Article -> {
					appendLine("${index + 1}. ${BilibiliToolSupport.oneLine(hit.title, TITLE_CHARS)}")
					appendLine(
						"   cv${hit.cvId}" +
							" · ${BilibiliToolSupport.oneLine(hit.author, AUTHOR_CHARS)}" +
							" · ${BilibiliToolSupport.number(hit.view)} views" +
							" · ${BilibiliToolSupport.number(hit.like)} likes" +
							" · ${BilibiliToolSupport.number(hit.replyCount)} comments" +
							" · ${BilibiliToolSupport.date(hit.pubdateSeconds)}",
					)
					if (hit.description.isNotBlank()) {
						appendLine("   ${BilibiliToolSupport.oneLine(hit.description, SNIPPET_CHARS)}")
					}
				}
			}
		}
		appendLine()
		appendLine("Use the ids above with bili_video / bili_comments / bili_article.")
	}

	companion object {
		const val DEFAULT_LIMIT = 5
		const val MAX_LIMIT = 10
		const val MAX_PAGE = 5
		private const val TITLE_CHARS = 220
		private const val AUTHOR_CHARS = 60
		private const val SNIPPET_CHARS = 160
	}
}
