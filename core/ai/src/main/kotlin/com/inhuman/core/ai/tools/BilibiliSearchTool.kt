package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import com.inhuman.core.ai.bilibili.BilibiliSearchDuration
import com.inhuman.core.ai.bilibili.BilibiliSearchFilters
import com.inhuman.core.ai.bilibili.BilibiliSearchHit
import com.inhuman.core.ai.bilibili.BilibiliSearchKind
import kotlinx.serialization.json.JsonObject

/**
 * Search Bilibili for videos, column articles, uploaders or shows, anonymously.
 *
 * ## Why the kinds are one parameter instead of four tools
 *
 * All four are the same endpoint with one differing field, the same output
 * shape and the same failure modes. Four tools would quadruple the rows in every
 * picker and make the model choose between names that differ by one word; one
 * tool with a `kind` argument is a choice it already makes on other tools.
 *
 * ## Why results are formatted rather than returned as JSON
 *
 * The result is context. JSON punctuation for four numbers costs tokens and
 * hides the one thing the model needs to cite (the BV/cv id). The layout is
 * also what the cap is measured against: a result list the model cannot see the
 * end of is worse than a shorter list that says how many were omitted.
 *
 * ## Why the filters are parameters and not a second tool
 *
 * Bilibili's search accepts the same query with a duration bucket, a date range
 * and a partition id. They narrow one search; a separate "advanced search" name
 * would teach the model that narrowing is a different operation. Each filter is
 * optional and its absence sends nothing.
 */
class BilibiliSearchTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_search"

	/** Read-only, remote-content, no account. Replaying it costs a request. */
	override val mutates = false

	override val description = """
		Search Bilibili for videos, column articles, uploaders or shows and get titles, ids,
		authors, stats and dates.

		Use `bili_video` with a returned BV id to read a video's details, `bili_comments` for
		its comments, `bili_article` with a returned cv id to read a column's text, `bili_up`
		with a returned mid to open an uploader's page, and `bili_bangumi` with a returned
		season id to open a show. The search itself returns no page content. It is anonymous:
		no Bilibili account is involved, and the app makes the request, not you.

		Optional filters for videos: `duration`, `published_after` / `published_before`
		(yyyy-MM-dd) and `partition` (a Bilibili partition id such as 188 for tech and
		digital). Only set them when the user's question needs them.

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
				"enum" to listOf("video", "article", "user", "bangumi"),
				"description" to "What to search for: videos (default), column articles, " +
					"uploaders, or shows (anime/documentaries/series).",
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
			"duration" to mapOf(
				"type" to "string",
				"enum" to listOf("under_10", "10_30", "30_60", "over_60"),
				"description" to "Length filter for videos, in minutes. Omit for any length.",
			),
			"published_after" to mapOf(
				"type" to "string",
				"description" to "Only results published on or after this date (yyyy-MM-dd).",
			),
			"published_before" to mapOf(
				"type" to "string",
				"description" to "Only results published on or before this date (yyyy-MM-dd).",
			),
			"partition" to mapOf(
				"type" to "integer",
				"description" to "Bilibili search partition id (tids), for example 188 tech and " +
					"digital, 36 knowledge, 160 life, 4 games, 211 food.",
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
			"user" -> BilibiliSearchKind.USER
			"bangumi" -> BilibiliSearchKind.BANGUMI
			else -> return ToolResult.error(
				"'kind' must be one of 'video', 'article', 'user' or 'bangumi'",
			)
		}
		val sort = when (BilibiliToolSupport.string(arguments, "order")?.lowercase()) {
			null, "default" -> "totalrank"
			"latest" -> "pubdate"
			"popular" -> "click"
			else -> return ToolResult.error("'order' must be 'default', 'latest' or 'popular'")
		}
		val duration = when (BilibiliToolSupport.string(arguments, "duration")?.lowercase()) {
			null -> null
			"under_10" -> BilibiliSearchDuration.UNDER_TEN
			"10_30" -> BilibiliSearchDuration.TEN_TO_THIRTY
			"30_60" -> BilibiliSearchDuration.THIRTY_TO_SIXTY
			"over_60" -> BilibiliSearchDuration.OVER_SIXTY
			else -> return ToolResult.error(
				"'duration' must be 'under_10', '10_30', '30_60' or 'over_60'",
			)
		}
		val afterValue = BilibiliToolSupport.string(arguments, "published_after")
		val beforeValue = BilibiliToolSupport.string(arguments, "published_before")
		val after = BilibiliToolSupport.dateArgument(afterValue)
		val before = BilibiliToolSupport.dateArgument(beforeValue)
		if (afterValue != null && after == null) {
			return ToolResult.error("'published_after' must be a yyyy-MM-dd date")
		}
		if (beforeValue != null && before == null) {
			return ToolResult.error("'published_before' must be a yyyy-MM-dd date")
		}
		val limit = BilibiliToolSupport.int(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
		val page = BilibiliToolSupport.int(arguments, "page", 1, 1, MAX_PAGE)
		val partition = BilibiliToolSupport.long(arguments, "partition").toInt()
			.takeIf { it > 0 }

		val filters = BilibiliSearchFilters(
			duration = duration,
			publishedAfterSeconds = after ?: 0L,
			publishedBeforeSeconds = before ?: 0L,
			partitionId = partition ?: 0,
		)
		// The dates are shown as the caller wrote them, not reformatted from the
		// epoch: the epoch is Shanghai midnight, and formatting it in the
		// phone's zone would print the previous day to a phone set to UTC.
		val filterNote = describeFilters(duration, afterValue, beforeValue, partition)
		return try {
			val hits = service.search(keyword, kind, sort, page, limit, filters)
			if (hits.isEmpty()) {
				ToolResult.ok(
					"No Bilibili ${kind.label} results for \"$keyword\" (page $page)" +
						filterNote + ". Nothing was hidden; the search itself returned none.",
				)
			} else {
				ToolResult.ok(format(keyword, kind, page, filterNote, hits))
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
		filterNote: String,
		hits: List<BilibiliSearchHit>,
	): String = buildString {
		append("Bilibili search \"$keyword\" (${kind.label}) — ${hits.size} result(s), page $page")
		appendLine(filterNote)
		hits.forEachIndexed { index, hit ->
			appendLine()
			when (hit) {
				is BilibiliSearchHit.Video -> {
					appendLine("${index + 1}. ${BilibiliToolSupport.oneLine(hit.title, TITLE_CHARS)}")
					appendLine(
						"   ${hit.bvid} · ${BilibiliToolSupport.oneLine(hit.author, AUTHOR_CHARS)}" +
							" (mid ${hit.ownerMid})" +
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
						"   cv${hit.cvId} · ${BilibiliToolSupport.oneLine(hit.author, AUTHOR_CHARS)}" +
							" · ${BilibiliToolSupport.number(hit.view)} views" +
							" · ${BilibiliToolSupport.number(hit.like)} likes" +
							" · ${BilibiliToolSupport.number(hit.replyCount)} comments" +
							" · ${BilibiliToolSupport.date(hit.pubdateSeconds)}",
					)
					if (hit.description.isNotBlank()) {
						appendLine("   ${BilibiliToolSupport.oneLine(hit.description, SNIPPET_CHARS)}")
					}
				}

				is BilibiliSearchHit.User -> {
					appendLine("${index + 1}. ${BilibiliToolSupport.oneLine(hit.name, TITLE_CHARS)}")
					appendLine(
						"   mid ${hit.mid}" +
							" · ${BilibiliToolSupport.number(hit.fans)} followers" +
							" · ${BilibiliToolSupport.number(hit.videos)} videos" +
							" · Lv${hit.level}" +
							if (hit.live) " · live now" else "",
					)
					if (hit.official.isNotBlank()) {
						appendLine("   ${BilibiliToolSupport.oneLine(hit.official, SNIPPET_CHARS)}")
					}
					if (hit.sign.isNotBlank()) {
						appendLine("   ${BilibiliToolSupport.oneLine(hit.sign, SNIPPET_CHARS)}")
					}
				}

				is BilibiliSearchHit.Bangumi -> {
					appendLine("${index + 1}. ${BilibiliToolSupport.oneLine(hit.title, TITLE_CHARS)}")
					appendLine(
						"   ss${hit.seasonId}" +
							" · ${hit.status.ifBlank { "length unknown" }}" +
							" · ${if (hit.score > 0) "score ${hit.score}" else "unscored"}" +
							" · ${hit.episodes} episode(s)" +
							" · ${BilibiliToolSupport.date(hit.publishSeconds)}",
					)
					if (hit.styles.isNotEmpty()) {
						appendLine("   genres: ${hit.styles.joinToString(", ")}")
					}
					if (hit.description.isNotBlank()) {
						appendLine("   ${BilibiliToolSupport.oneLine(hit.description, SNIPPET_CHARS)}")
					}
				}
			}
		}
		appendLine()
		appendLine(
			when (kind) {
				BilibiliSearchKind.VIDEO ->
					"Use the BV ids with bili_video / bili_comments / bili_transcript."

				BilibiliSearchKind.ARTICLE ->
					"Use the cv ids with bili_article (or bili_comments with cv_id)."

				BilibiliSearchKind.USER ->
					"Use the mids with bili_up."

				BilibiliSearchKind.BANGUMI ->
					"Use the season ids with bili_bangumi."
			},
		)
	}

	/** One short line naming the narrowing that was actually applied. */
	private fun describeFilters(
		duration: BilibiliSearchDuration?,
		after: String?,
		before: String?,
		partition: Int?,
	): String = buildList {
		duration?.let { add("length ${it.label}") }
		after?.let { add("from $it") }
		before?.let { add("to $it") }
		partition?.let { add("partition $it") }
	}.let { parts -> if (parts.isEmpty()) "" else " · filters: ${parts.joinToString(", ")}" }

	companion object {
		const val DEFAULT_LIMIT = 5
		const val MAX_LIMIT = 10
		const val MAX_PAGE = 5
		private const val TITLE_CHARS = 220
		private const val AUTHOR_CHARS = 60
		private const val SNIPPET_CHARS = 160
	}
}
