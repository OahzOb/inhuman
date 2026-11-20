package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliRankingCategory
import com.inhuman.core.ai.bilibili.BilibiliReadService
import com.inhuman.core.ai.bilibili.BilibiliTrendingItem
import com.inhuman.core.ai.bilibili.BilibiliTrendingKind
import kotlinx.serialization.json.JsonObject

/**
 * Bilibili's own discovery lists: the ranking, the popular feed, or the hot
 * searches.
 *
 * ## Why one tool and not three
 *
 * All three answer "what is big right now"; choosing between them is a
 * parameter, not a second operation. The result shape differs (videos versus
 * keywords) but the question is one, and a model that has to pick between
 * `bili_ranking`, `bili_popular` and `bili_hot_search` pays for a naming
 * decision on every call.
 *
 * ## Why the categories are a fixed enum
 *
 * The ranking's `rid` values are not the search's `tids` values -- the same
 * number means "tech and digital" to one and "knowledge" to the other -- so a
 * friendly name mapped against the wrong endpoint would be a wrong label rather
 * than a missing filter. Only buckets that answered `code 0` with a real list
 * are listed; the anime-season and variety buckets answer `-400` and are
 * deliberately absent.
 *
 * ## What the hot search list is not
 *
 * It is keywords, not videos: there is no id to open, and it changes hourly.
 * Presenting it as content would be wrong, so the result calls them search
 * phrases.
 */
class BilibiliTrendingTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_trending"

	override val mutates = false

	override val description = """
		Read what is big on Bilibili right now: the ranking, the popular feed, or the current
		hot search phrases.

		Use it when the user asks what is trending or popular, without naming a specific
		video. `kind` selects which list; `category` narrows the ranking only (popular and
		hot searches are site-wide). Anonymous read: no account, no key. Video entries carry
		a BV id, so `bili_video`, `bili_comments` or `bili_transcript` can follow up.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"kind" to mapOf(
				"type" to "string",
				"enum" to listOf("ranking", "popular", "hot_search"),
				"description" to "Which list: ranking (default), popular feed, or hot searches.",
			),
			"category" to mapOf(
				"type" to "string",
				"enum" to BilibiliRankingCategory.entries.map { it.name.lowercase() },
				"description" to "Ranking category; defaults to all. Ignored for popular and " +
					"hot_search.",
			),
			"limit" to mapOf(
				"type" to "integer",
				"description" to "How many entries to return, 1 to $MAX_LIMIT. Defaults to $DEFAULT_LIMIT.",
			),
		),
		"required" to emptyList<String>(),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val kind = when (BilibiliToolSupport.string(arguments, "kind")?.lowercase()) {
			null, "ranking" -> BilibiliTrendingKind.RANKING
			"popular" -> BilibiliTrendingKind.POPULAR
			"hot_search" -> BilibiliTrendingKind.HOT_SEARCH
			else -> return ToolResult.error("'kind' must be 'ranking', 'popular' or 'hot_search'")
		}
		val category = when (val raw = BilibiliToolSupport.string(arguments, "category")?.lowercase()) {
			null -> BilibiliRankingCategory.ALL
			else -> BilibiliRankingCategory.entries.firstOrNull { it.name.lowercase() == raw }
				?: return ToolResult.error(
					"'category' must be one of " +
						BilibiliRankingCategory.entries.joinToString(", ") { it.name.lowercase() },
				)
		}
		val limit = BilibiliToolSupport.int(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
		return try {
			val items = service.trending(kind, limit, category)
			if (items.isEmpty()) {
				ToolResult.ok("Bilibili ${kind.label} returned nothing right now.")
			} else {
				ToolResult.ok(format(kind, category, items))
			}
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili trending read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(
		kind: BilibiliTrendingKind,
		category: BilibiliRankingCategory,
		items: List<BilibiliTrendingItem>,
	): String = buildString {
		append(
			"Bilibili ${kind.label}" +
				if (kind == BilibiliTrendingKind.RANKING && category != BilibiliRankingCategory.ALL) {
					" (${category.label})"
				} else {
					""
				},
		)
		appendLine(" — ${items.size} shown")
		items.forEachIndexed { index, item ->
			appendLine()
			when (item) {
				is BilibiliTrendingItem.Video -> {
					appendLine("${index + 1}. ${item.bvid} — " +
						BilibiliToolSupport.oneLine(item.title, TITLE_CHARS))
					appendLine(
						"   ${BilibiliToolSupport.oneLine(item.author, AUTHOR_CHARS)}" +
							" · ${BilibiliToolSupport.number(item.play)} plays" +
							if (item.category.isNotBlank()) " · ${item.category}" else "" +
								" · ${BilibiliToolSupport.date(item.pubdateSeconds)}",
					)
				}

				is BilibiliTrendingItem.Keyword ->
					appendLine("${index + 1}. ${BilibiliToolSupport.oneLine(item.keyword, TITLE_CHARS)}")
			}
		}
		appendLine()
		if (items.firstOrNull() is BilibiliTrendingItem.Keyword) {
			appendLine(
				"Hot searches are phrases people are searching for, not videos. " +
					"Use `bili_search` on a phrase to find actual videos.",
			)
		} else {
			appendLine("Use the BV ids with bili_video / bili_comments / bili_transcript.")
		}
	}

	companion object {
		const val DEFAULT_LIMIT = 10
		const val MAX_LIMIT = 20
		private const val TITLE_CHARS = 200
		private const val AUTHOR_CHARS = 60
	}
}
