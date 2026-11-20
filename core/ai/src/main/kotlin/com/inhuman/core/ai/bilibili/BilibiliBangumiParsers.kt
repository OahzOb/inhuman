package com.inhuman.core.ai.bilibili

import kotlinx.serialization.json.JsonObject

/**
 * Reads one season of a Bilibili show.
 *
 * A "season" is the endpoint's own unit for an anime, a documentary, a series
 * or a variety programme, and it is addressed by `ss` (season) or `ep` (episode)
 * id. The endpoint resolves either direction, so the tool accepts either.
 *
 * ## Why the episode list is not summarised away
 *
 * The list is the reason the tool exists: "how many episodes, when does the next
 * one air, what are they called" is what a reader asks. Each item keeps its
 * `ep` id and its `bv` id, so the model can follow up with `bili_video` on an
 * episode whose metadata is not enough.
 */
internal object BilibiliBangumiParsers {

	fun parse(root: JsonObject): BilibiliBangumi {
		val result = root.obj("result")
			?: root.obj("data")
			?: throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"show response had no season",
			)
		val rating = result.obj("rating")
		val newEp = result.obj("new_ep")
		val publish = result.obj("publish")
		val summary = result.string("evaluate").orEmpty().ifBlank {
			result.string("summary").orEmpty()
		}
		return BilibiliBangumi(
			seasonId = result.long("season_id"),
			title = BilibiliHtml.stripTags(result.string("title").orEmpty()),
			jpTitle = BilibiliHtml.stripTags(result.string("jp_title").orEmpty()),
			score = rating?.double("score") ?: 0.0,
			scoreCount = rating?.long("count") ?: 0L,
			styles = result.array("styles")?.mapNotNull { element ->
				val style = element as? kotlinx.serialization.json.JsonPrimitive
					?: return@mapNotNull null
				style.content.takeIf { it.isNotBlank() }
			}.orEmpty(),
			summary = BilibiliHtml.stripTags(summary).trim(),
			totalEpisodes = result.int("total").takeIf { it > 0 }
				?: (result.array("episodes")?.size ?: 0),
			status = newEp?.string("desc").orEmpty(),
			publishText = publish?.string("pub_time_show").orEmpty()
				.ifBlank { publish?.string("pub_time").orEmpty() },
			episodes = result.array("episodes")?.mapNotNull { element ->
				val item = element as? JsonObject ?: return@mapNotNull null
				val epId = item.long("ep_id").takeIf { it > 0 }
					?: item.long("id")
				if (epId <= 0) return@mapNotNull null
				BilibiliBangumiEpisode(
					epId = epId,
					aid = item.long("aid"),
					cid = item.long("cid"),
					bvid = item.string("bvid").orEmpty(),
					title = BilibiliHtml.stripTags(item.string("title").orEmpty()),
					longTitle = BilibiliHtml.stripTags(item.string("long_title").orEmpty()),
					pubTimeSeconds = item.long("pub_time"),
					// The episode list reports milliseconds where every other
					// endpoint reports seconds; keeping the raw ms would print
					// "24 minutes" as "23:35:00" in the result.
					durationSeconds = item.long("duration") / 1000L,
					badge = item.string("badge").orEmpty(),
				)
			}.orEmpty(),
		)
	}
}
