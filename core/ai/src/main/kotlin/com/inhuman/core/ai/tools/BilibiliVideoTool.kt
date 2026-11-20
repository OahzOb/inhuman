package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import com.inhuman.core.ai.bilibili.BilibiliVideoInfo
import com.inhuman.core.ai.bilibili.BilibiliVideoSummary
import kotlinx.serialization.json.JsonObject

/**
 * Read one Bilibili video's public metadata.
 *
 * ## What "read a video" means here
 *
 * Metadata, not the video. There is no stream, no audio and no subtitle read
 * here: an anonymous caller cannot fetch subtitles, so a tool that implied it
 * could would be a promise the endpoint does not keep. The description is
 * included because it is where uploaders put the summary, the sources and the
 * mentions; the stats are included because "2.3k comments" is how a reader
 * decides whether the comments are worth opening.
 *
 * The result names the video's public URL and, when the page advertises a
 * subtitle track the anonymous caller cannot read, says so: "has subtitles, read
 * them with `bili_transcript` (needs the linked login)" is a different answer
 * from "no subtitles", and the model cannot tell them apart without the hint.
 *
 * ## Why related videos are optional
 *
 * They are a second request. A model reading a video to answer a question does
 * not need them, and paying for that request on every read would make the
 * cheapest tool expensive; `related` asks for them when the user is looking for
 * what to watch next.
 */
class BilibiliVideoTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_video"

	override val mutates = false

	override val description = """
		Read one Bilibili video's public details: title, uploader, publish date, duration,
		parts, category, play/like/coin/favourite/comment counts, description and whether it
		has subtitles.

		This does not fetch the video stream, the audio or the subtitles; it reads the page
		metadata an anonymous visitor can see. Use it before `bili_comments` or
		`bili_transcript` when the question is "what is this video about", and use the returned
		BV id everywhere else.

		Set `related` to true only when the user is looking for something else to watch.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"bvid" to mapOf(
				"type" to "string",
				"description" to "The video's BV id, for example BV1das46aEKM.",
			),
			"related" to mapOf(
				"type" to "boolean",
				"description" to "Also read Bilibili's related-video list (one extra request).",
			),
		),
		"required" to listOf("bvid"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val bvid = BilibiliToolSupport.bvid(arguments)
			?: return ToolResult.error(
				"missing or invalid 'bvid': expected a BV id such as BV1das46aEKM",
			)
		val withRelated = (arguments["related"] as? kotlinx.serialization.json.JsonPrimitive)
			?.content == "true"
		return try {
			val video = service.video(bvid)
			val related = if (withRelated) {
				runCatching { service.related(bvid, MAX_RELATED) }.getOrDefault(emptyList())
			} else {
				emptyList()
			}
			ToolResult.ok(format(video, related))
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili video read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(
		video: BilibiliVideoInfo,
		related: List<BilibiliVideoSummary>,
	): String = buildString {
		appendLine("${video.bvid} — ${BilibiliToolSupport.oneLine(video.title, TITLE_CHARS)}")
		appendLine("url: ${BilibiliToolSupport.videoUrl(video.bvid)}")
		appendLine(
			"uploader: ${BilibiliToolSupport.oneLine(video.ownerName, AUTHOR_CHARS)}" +
				" (mid ${video.ownerMid})",
		)
		appendLine(
			"published: ${BilibiliToolSupport.date(video.pubdateSeconds)}" +
				" · length: ${BilibiliToolSupport.duration(video.durationSeconds)}" +
				" · parts: ${video.pages.size.coerceAtLeast(1)}",
		)
		if (video.category.isNotBlank()) appendLine("category: ${video.category}")
		appendLine(
			"stats: ${BilibiliToolSupport.number(video.stats.view)} views" +
				" · ${BilibiliToolSupport.number(video.stats.like)} likes" +
				" · ${BilibiliToolSupport.number(video.stats.coin)} coins" +
				" · ${BilibiliToolSupport.number(video.stats.favorite)} favourites" +
				" · ${BilibiliToolSupport.number(video.stats.reply)} comments" +
				" · ${BilibiliToolSupport.number(video.stats.danmaku)} danmaku" +
				" · ${BilibiliToolSupport.number(video.stats.share)} shares",
		)
		appendLine(
			if (video.subtitleTracks > 0) {
				"subtitles: ${video.subtitleTracks} track(s) advertised by the page; reading " +
					"them needs `bili_transcript` and the linked Bilibili login"
			} else {
				"subtitles: none advertised by the page"
			},
		)
		if (video.pages.size > 1) {
			appendLine("parts:")
			video.pages.take(MAX_PARTS).forEach { page ->
				appendLine(
					"  P${page.page}. ${BilibiliToolSupport.oneLine(page.title, PART_CHARS)}" +
						" (${BilibiliToolSupport.duration(page.durationSeconds)})",
				)
			}
			if (video.pages.size > MAX_PARTS) {
				appendLine("  … ${video.pages.size - MAX_PARTS} more parts not shown")
			}
		}
		appendLine()
		val description = video.description.trim()
		if (description.isEmpty()) {
			appendLine("description: (none)")
		} else {
			appendLine("description:")
			appendLine(description.take(DESCRIPTION_CHARS).trimEnd())
			if (description.length > DESCRIPTION_CHARS) {
				appendLine("[description truncated at $DESCRIPTION_CHARS characters]")
			}
		}
		if (related.isNotEmpty()) {
			appendLine()
			appendLine("related videos (Bilibili's own recommendations):")
			related.forEachIndexed { index, item ->
				appendLine(
					"${index + 1}. ${item.bvid} — " +
						BilibiliToolSupport.oneLine(item.title, RELATED_TITLE_CHARS),
				)
				appendLine(
					"   ${BilibiliToolSupport.oneLine(item.author, AUTHOR_CHARS)}" +
						" · ${BilibiliToolSupport.number(item.play)} plays" +
						" · ${BilibiliToolSupport.duration(item.durationSeconds)}" +
						" · ${BilibiliToolSupport.date(item.pubdateSeconds)}",
				)
			}
		}
	}

	companion object {
		private const val TITLE_CHARS = 220
		private const val AUTHOR_CHARS = 60
		private const val PART_CHARS = 80
		private const val RELATED_TITLE_CHARS = 120
		private const val MAX_PARTS = 10
		private const val MAX_RELATED = 5
		private const val DESCRIPTION_CHARS = 2_000
	}
}
