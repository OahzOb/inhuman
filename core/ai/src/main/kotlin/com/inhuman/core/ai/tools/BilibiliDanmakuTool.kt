package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliDanmaku
import com.inhuman.core.ai.bilibili.BilibiliDanmakuLine
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import kotlinx.serialization.json.JsonObject

/**
 * A sample of a video's danmaku (弹幕), with the times they appeared.
 *
 * ## What this is honest about
 *
 * Bilibili's segment endpoints answer with a bounded subset per six-minute
 * window, and the anonymous surface returns fewer than the video's own counter.
 * The result therefore always states how many windows exist and which ones were
 * read, and says "sample" instead of implying an archive. A model that treats
 * thirty sampled lines as the comment section would overstate what viewers
 * think; a model told it is a sample will say so.
 *
 * ## Why sample instead of dumping
 *
 * A popular video has thousands of danmaku per window and almost all of them
 * are one-word reactions. What a reader wants is the flavour and the moments
 * where the reaction spikes, so the tool spreads a fixed budget across the
 * windows it read and drops repeated text. The timestamps are the useful part:
 * "at 12:34 the crowd lost it" is a claim the model can make and the user can
 * verify.
 *
 * ## Why the cost is bounded
 *
 * One call reads at most three windows (plus two metadata requests), because
 * each window is a request and a two-hour stream would otherwise turn a read
 * tool into a crawler -- exactly what the app's pacing exists to avoid.
 */
class BilibiliDanmakuTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_danmaku"

	override val mutates = false

	override val description = """
		Read a sample of a Bilibili video's danmaku (the scrolling comments on the video), with
		the time each appeared.

		Use it to answer "how did viewers react", "what are people saying at 12:30" or "where
		is the hype". Bilibili returns a bounded subset per six-minute window, so the result is
		a sample: it states how many windows the video has and which ones were read. `from`
		and `to` (seconds) select a window; without them the first, middle and last windows of
		the video are sampled. Anonymous read: no account, no key.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"bvid" to mapOf(
				"type" to "string",
				"description" to "The video's BV id, for example BV1das46aEKM.",
			),
			"page" to mapOf(
				"type" to "integer",
				"description" to "Which part of a multi-part video, starting at 1. Defaults to 1.",
			),
			"from" to mapOf(
				"type" to "integer",
				"description" to "Start of the window, in seconds. Omit to sample the whole video.",
			),
			"to" to mapOf(
				"type" to "integer",
				"description" to "End of the window, in seconds. Omit to sample the whole video.",
			),
		),
		"required" to listOf("bvid"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val bvid = BilibiliToolSupport.bvid(arguments)
			?: return ToolResult.error(
				"missing or invalid 'bvid': expected a BV id such as BV1das46aEKM",
			)
		val page = BilibiliToolSupport.int(arguments, "page", 1, 1, MAX_PAGE)
		val from = BilibiliToolSupport.long(arguments, "from").coerceAtLeast(0)
		val to = BilibiliToolSupport.long(arguments, "to").coerceAtLeast(0)
		if (to > 0 && from > 0 && to <= from) {
			return ToolResult.error("'to' must be after 'from'")
		}
		return try {
			ToolResult.ok(format(bvid, page, service.danmaku(bvid, page, from, to)))
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili danmaku read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(bvid: String, page: Int, danmaku: BilibiliDanmaku): String = buildString {
		appendLine(
			"$bvid — danmaku sample" + if (page > 1) " · part $page" else "",
		)
		appendLine(BilibiliToolSupport.videoUrl(bvid, page))
		appendLine(
			"danmaku on the video: ${BilibiliToolSupport.number(danmaku.totalCount)}" +
				" · windows of ${BilibiliToolSupport.clock(danmaku.segmentSeconds)}" +
				" · windows: ${danmaku.segmentCount}" +
				" · sampled: ${danmaku.sampled.joinToString(", ") { it.index.toString() }}",
		)
		appendLine(
			"Bilibili returns a subset of each window, so this is a sample, not every danmaku.",
		)
		if (danmaku.sampled.all { it.lines.isEmpty() }) {
			appendLine()
			appendLine(
				"The sampled windows returned no danmaku. The video may have its danmaku " +
					"turned off or cleared; say so rather than describing audience reaction.",
			)
			return@buildString
		}
		val seen = HashSet<String>()
		var shown = 0
		for (segment in danmaku.sampled) {
			if (segment.lines.isEmpty()) continue
			appendLine()
			appendLine(
				"[${BilibiliToolSupport.clock(segment.fromSeconds)}–" +
					"${BilibiliToolSupport.clock(segment.toSeconds)} · " +
					"${BilibiliToolSupport.number(segment.entries.toLong())} returned]",
			)
			for (line in sample(segment.lines)) {
				if (shown >= MAX_LINES) break
				// Repetition is the norm in danmaku; the same joke twice costs a
				// line and adds nothing to "what is the crowd saying".
				if (!seen.add(line.text)) continue
				appendLine("[${BilibiliToolSupport.clock(line.atSeconds)}] ${line.text}")
				shown++
			}
			if (shown >= MAX_LINES) break
		}
		appendLine()
		appendLine(
			"Danmaku are audience reactions, not facts or part of the video's speech. " +
				"The timestamps are where each comment appeared.",
		)
	}

	/**
	 * A fixed budget spread over the window's lines.
	 *
	 * Sorting first matters: the endpoint does not promise an order, and a
	 * "first N" sample of an unordered list would cluster on whatever arrived
	 * first rather than on the beginning of the video.
	 */
	private fun sample(lines: List<BilibiliDanmakuLine>): List<BilibiliDanmakuLine> {
		if (lines.size <= PER_WINDOW) return lines.sortedBy { it.atSeconds }
		val sorted = lines.sortedBy { it.atSeconds }
		val stride = sorted.size.toDouble() / PER_WINDOW
		return (0 until PER_WINDOW).map { index ->
			sorted[(index * stride).toInt().coerceIn(0, sorted.lastIndex)]
		}
	}

	companion object {
		const val MAX_PAGE = 100
		private const val PER_WINDOW = 12
		private const val MAX_LINES = 36
	}
}
