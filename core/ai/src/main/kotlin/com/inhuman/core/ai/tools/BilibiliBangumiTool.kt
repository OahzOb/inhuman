package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliBangumi
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import kotlinx.serialization.json.JsonObject

/**
 * One season of a Bilibili show: anime, documentary, series or variety.
 *
 * ## Why the ids can be either an `ss` or an `ep`
 *
 * A user can arrive with either: a search result gives a season id, a shared
 * episode gives an episode id, and the endpoint resolves one to the other. The
 * tool accepts both rather than making the model learn which one it has.
 *
 * ## Why the episode list is shown
 *
 * "How many episodes, what are they called, when does the next one air" is the
 * question this tool is for. Summary, score, genres and episode rows together
 * are what a reader deciding whether to watch needs; a title and a cover URL
 * would not answer any of it, and the cover is not readable here anyway.
 *
 * ## What this does not do
 *
 * It does not play the show and does not read its subtitles. Show subtitles are
 * a different endpoint from a normal video's, and they are not part of this
 * read path; the result says the episode's BV id so the ordinary video tools
 * can be tried when the episode is an ordinary upload.
 */
class BilibiliBangumiTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_bangumi"

	override val mutates = false

	override val description = """
		Read one Bilibili show's details: title, score, genres, summary, episode count and the
		episode list with air dates.

		Pass either `season_id` (ss43164 or 43164) from `bili_search` with kind=bangumi, or
		`ep_id` (ep693247 or 693247) from a shared episode link. This reads public metadata
		only: it does not play the show or read its subtitles. Anonymous read: no account.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"season_id" to mapOf(
				"type" to "string",
				"description" to "The show's ss id, as ss43164 or 43164.",
			),
			"ep_id" to mapOf(
				"type" to "string",
				"description" to "An episode's ep id, as ep693247 or 693247. Either id is enough.",
			),
		),
		"required" to emptyList<String>(),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val seasonId = parseId(BilibiliToolSupport.string(arguments, "season_id"), "ss")
		val epId = parseId(BilibiliToolSupport.string(arguments, "ep_id"), "ep")
		if (seasonId == null && epId == null) {
			return ToolResult.error(
				"pass either 'season_id' (ss43164) or 'ep_id' (ep693247)",
			)
		}
		return try {
			ToolResult.ok(format(service.bangumi(seasonId ?: 0L, epId ?: 0L)))
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili show read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(show: BilibiliBangumi): String = buildString {
		appendLine("ss${show.seasonId} — ${BilibiliToolSupport.oneLine(show.title, TITLE_CHARS)}")
		appendLine("url: ${BilibiliToolSupport.bangumiUrl(show.seasonId)}")
		if (show.jpTitle.isNotBlank()) {
			appendLine("original title: ${BilibiliToolSupport.oneLine(show.jpTitle, TITLE_CHARS)}")
		}
		appendLine(
			"episodes: ${show.totalEpisodes}" +
				if (show.status.isNotBlank()) " · ${show.status}" else "",
		)
		if (show.score > 0) {
			appendLine(
				"score: ${show.score}" +
					if (show.scoreCount > 0) {
						" from ${BilibiliToolSupport.number(show.scoreCount)} ratings"
					} else {
						""
					},
			)
		}
		if (show.styles.isNotEmpty()) {
			appendLine("genres: ${show.styles.joinToString(", ")}")
		}
		if (show.publishText.isNotBlank()) {
			appendLine("publish: ${show.publishText}")
		}
		if (show.summary.isNotBlank()) {
			appendLine()
			appendLine("summary:")
			appendLine(BilibiliToolSupport.oneLine(show.summary, SUMMARY_CHARS))
		}
		if (show.episodes.isNotEmpty()) {
			appendLine()
			appendLine("episodes:")
			show.episodes.take(MAX_EPISODES).forEach { episode ->
				appendLine(
					"- ep${episode.epId} · ${BilibiliToolSupport.oneLine(episode.title, EPISODE_CHARS)}" +
						if (episode.longTitle.isNotBlank()) {
							" ${BilibiliToolSupport.oneLine(episode.longTitle, EPISODE_CHARS)}"
						} else {
							""
						} +
						if (episode.pubTimeSeconds > 0) {
							" · ${BilibiliToolSupport.date(episode.pubTimeSeconds)}"
						} else {
							""
						} +
						if (episode.durationSeconds > 0) {
							" · ${BilibiliToolSupport.duration(episode.durationSeconds)}"
						} else {
							""
						},
				)
			}
			if (show.episodes.size > MAX_EPISODES) {
				appendLine(
					"… ${show.episodes.size - MAX_EPISODES} more episodes not shown; " +
						"the full list is on ${BilibiliToolSupport.bangumiUrl(show.seasonId)}",
				)
			}
		}
	}

	/**
	 * `ss43164`, `43164`, `SS43164` all read as the same id; anything else is
	 * absent rather than guessed. The prefix is checked case-insensitively
	 * because a model copies "ss" from a result line and sometimes uppercases it.
	 */
	private fun parseId(value: String?, prefix: String): Long? {
		if (value.isNullOrBlank()) return null
		val trimmed = value.trim()
		val digits = if (trimmed.startsWith(prefix, ignoreCase = true)) {
			trimmed.substring(prefix.length)
		} else {
			trimmed
		}
		return digits.toLongOrNull()?.takeIf { it > 0 }
	}

	companion object {
		private const val TITLE_CHARS = 200
		private const val SUMMARY_CHARS = 1_200
		private const val EPISODE_CHARS = 80
		private const val MAX_EPISODES = 24
	}
}
