package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import com.inhuman.core.ai.bilibili.BilibiliUp
import com.inhuman.core.ai.bilibili.BilibiliUpOrder
import kotlinx.serialization.json.JsonObject

/**
 * One uploader's public page: who they are, their counters, whether they are
 * live, their recent videos and their collections.
 *
 * ## Why this exists as a tool and not as a link
 *
 * "What else has this person made" is the most common follow-up to a video
 * question, and the answer is public: the uploader page needs no account, which
 * was verified against the live endpoints before this tool was written. The mid
 * comes from `bili_search` (`kind=user`, or the uploader id printed on a video
 * result) or from `bili_video`.
 *
 * ## Why the page is one tool instead of four
 *
 * Bilibili splits an uploader into four endpoints. A model that had to call
 * `bili_up_info`, `bili_up_videos`, `bili_up_series` and `bili_up_dynamics` would
 * routinely call two and stop. The four are one page, so they are one call --
 * bounded to five requests by the client, and the video list is paginated
 * because a whole channel does not belong in one result.
 *
 * ## Why dynamics are a bonus and not a promise
 *
 * The dynamic feed is the one part that can be empty or shaped differently per
 * post type (text, images, repost, shared video). When it yields nothing the
 * rest of the page still answers; when it yields text it is shown briefly. The
 * tool does not fail over a section that does not exist.
 */
class BilibiliUpTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_up"

	override val mutates = false

	override val description = """
		Read a Bilibili uploader's public page: name, bio, level, follower and video counts,
		whether they are streaming now, their recent videos and their collections/series.

		Pass the uploader's numeric mid (from `bili_search` with kind=user, from a video
		result's uploader id, or from `bili_video`). Use `page` to read further into the
		video list and `order` to sort by newest or most played. This is public content and
		needs no account; it does not read the account's own history, favourites or feed.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"mid" to mapOf(
				"type" to "integer",
				"description" to "The uploader's numeric id, for example 517327498.",
			),
			"page" to mapOf(
				"type" to "integer",
				"description" to "Which page of the video list, starting at 1. Defaults to 1.",
			),
			"order" to mapOf(
				"type" to "string",
				"enum" to listOf("latest", "popular"),
				"description" to "Newest first (default) or most played first.",
			),
		),
		"required" to listOf("mid"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val mid = BilibiliToolSupport.long(arguments, "mid")
		if (mid <= 0) {
			return ToolResult.error("missing or invalid 'mid': expected a numeric uploader id")
		}
		val page = BilibiliToolSupport.int(arguments, "page", 1, 1, MAX_PAGE)
		val order = when (BilibiliToolSupport.string(arguments, "order")?.lowercase()) {
			null, "latest" -> BilibiliUpOrder.LATEST
			"popular" -> BilibiliUpOrder.POPULAR
			else -> return ToolResult.error("'order' must be 'latest' or 'popular'")
		}
		return try {
			ToolResult.ok(format(service.uploader(mid, page, order)))
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili uploader read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(up: BilibiliUp): String = buildString {
		appendLine("${up.mid} — ${BilibiliToolSupport.oneLine(up.name, TITLE_CHARS)}")
		appendLine("url: ${BilibiliToolSupport.spaceUrl(up.mid)}")
		if (up.official.isNotBlank()) {
			appendLine("official: ${BilibiliToolSupport.oneLine(up.official, OFFICIAL_CHARS)}")
		}
		if (up.sign.isNotBlank()) {
			appendLine("bio: ${BilibiliToolSupport.oneLine(up.sign, BIO_CHARS)}")
		}
		appendLine(
			"level: ${up.level}" +
				" · ${BilibiliToolSupport.number(up.fans)} followers" +
				" · ${BilibiliToolSupport.number(up.following)} following" +
				" · ${BilibiliToolSupport.number(up.videos)} videos",
		)
		if (up.live) {
			appendLine(
				"live now: ${BilibiliToolSupport.oneLine(up.liveTitle, TITLE_CHARS)}" +
					if (up.roomId > 0) " · ${BilibiliToolSupport.liveUrl(up.roomId)}" else "",
			)
		} else if (up.roomId > 0) {
			appendLine("live: offline (room ${up.roomId})")
		}
		if (up.recentVideos.isNotEmpty()) {
			appendLine()
			appendLine("recent videos (${if (up.page > 1) "page ${up.page}" else "newest first"}):")
			up.recentVideos.take(MAX_VIDEOS).forEachIndexed { index, video ->
				appendLine(
					"${index + 1}. ${video.bvid} — " +
						BilibiliToolSupport.oneLine(video.title, VIDEO_TITLE_CHARS),
				)
				appendLine(
					"   ${BilibiliToolSupport.number(video.play)} plays" +
						" · ${BilibiliToolSupport.duration(video.durationSeconds)}" +
						" · ${BilibiliToolSupport.date(video.createdSeconds)}",
				)
			}
			if (up.recentVideos.size > MAX_VIDEOS) {
				appendLine("… ${up.recentVideos.size - MAX_VIDEOS} more not shown")
			}
			if (up.totalVideos > MAX_VIDEOS) {
				appendLine(
					"${BilibiliToolSupport.number(up.totalVideos.toLong())} videos in total; " +
						"page ${up.page + 1} reads the next page.",
				)
			}
		}
		if (up.series.isNotEmpty()) {
			appendLine()
			appendLine("collections and series:")
			up.series.take(MAX_SERIES).forEach { series ->
				appendLine(
					"- ${if (series.isCollection) "collection" else "series"}: " +
						BilibiliToolSupport.oneLine(series.title, TITLE_CHARS) +
						" (${series.total} video(s))" +
						if (series.description.isNotBlank()) {
							" — ${BilibiliToolSupport.oneLine(series.description, SERIES_DESC_CHARS)}"
						} else {
							""
						},
				)
			}
		}
		if (up.dynamics.isNotEmpty()) {
			appendLine()
			appendLine("recent posts:")
			up.dynamics.take(MAX_DYNAMICS).forEach { post ->
				appendLine(
					"- [${post.kind}] ${BilibiliToolSupport.dateTime(post.createdAtSeconds)}: " +
						BilibiliToolSupport.oneLine(post.text, DYNAMIC_CHARS),
				)
			}
		}
	}

	companion object {
		const val MAX_PAGE = 10
		private const val MAX_VIDEOS = 10
		private const val MAX_SERIES = 5
		private const val MAX_DYNAMICS = 3
		private const val TITLE_CHARS = 220
		private const val VIDEO_TITLE_CHARS = 100
		private const val OFFICIAL_CHARS = 120
		private const val BIO_CHARS = 200
		private const val SERIES_DESC_CHARS = 80
		private const val DYNAMIC_CHARS = 200
	}
}
