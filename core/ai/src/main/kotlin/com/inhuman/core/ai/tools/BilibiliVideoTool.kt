package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import kotlinx.serialization.json.JsonObject

/**
 * Read one Bilibili video's public metadata.
 *
 * ## What "read a video" means here
 *
 * Metadata, not the video. There is no stream, no audio and no subtitle read:
 * an anonymous caller cannot fetch subtitles, so a tool that implied it could
 * would be a promise the endpoint does not keep. The description is included
 * because it is where uploaders put the summary, the sources and the mentions;
 * the stats are included because "2.3k comments" is how a reader decides
 * whether the comments are worth opening.
 */
class BilibiliVideoTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_video"

	override val mutates = false

	override val description = """
		Read one Bilibili video's public details: title, uploader, publish date, duration,
		parts, category, play/like/coin/favourite/comment counts and description.

		This does not fetch the video stream or subtitles; it reads the page metadata an
		anonymous visitor can see. Use it before `bili_comments` when the question is
		"what is this video about", and use the returned BV id everywhere else.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"bvid" to mapOf(
				"type" to "string",
				"description" to "The video's BV id, for example BV1das46aEKM.",
			),
		),
		"required" to listOf("bvid"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val bvid = BilibiliToolSupport.bvid(arguments)
			?: return ToolResult.error(
				"missing or invalid 'bvid': expected a BV id such as BV1das46aEKM",
			)
		return try {
			ToolResult.ok(format(service.video(bvid)))
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili video read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(video: com.inhuman.core.ai.bilibili.BilibiliVideoInfo): String = buildString {
		appendLine("${video.bvid} — ${BilibiliToolSupport.oneLine(video.title, TITLE_CHARS)}")
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
	}

	companion object {
		private const val TITLE_CHARS = 220
		private const val AUTHOR_CHARS = 60
		private const val PART_CHARS = 80
		private const val MAX_PARTS = 10
		private const val DESCRIPTION_CHARS = 2_000
	}
}
