package com.inhuman.core.ai.bilibili

import kotlinx.serialization.json.JsonObject

/**
 * Reads one uploader's page out of four separate endpoint answers.
 *
 * ## Why the page is assembled here
 *
 * Bilibili splits an uploader into four calls (`acc/info`, `relation/stat`,
 * `arc/search`, `seasons_series_list`) plus an optional dynamic feed. The tool
 * wants one answer, and the alternative -- a tool that calls four endpoints
 * itself -- would put endpoint knowledge in two layers. The client fetches; this
 * object shapes.
 *
 * Every part except the profile is optional in the model, because a creator
 * with no collections or no recent posts is ordinary, and a missing profile on
 * the other hand raises before this is reached: the client treats it as the
 * primary refusal.
 */
internal object BilibiliUpParsers {

	fun parse(
		mid: Long,
		page: Int,
		profile: JsonObject,
		counter: JsonObject?,
		videos: JsonObject?,
		collections: JsonObject?,
		dynamics: JsonObject?,
	): BilibiliUp {
		val data = profile.obj("data") ?: JsonObject(emptyMap())
		val live = data.obj("live_room")
		val videoPage = videos?.obj("data")?.obj("page")
		val videoList = videos?.obj("data")?.obj("list")?.array("vlist")
			?.mapNotNull { element ->
				val item = element as? JsonObject ?: return@mapNotNull null
				val bvid = item.string("bvid")?.takeIf { it.isNotBlank() }
					?: return@mapNotNull null
				BilibiliUpVideo(
					bvid = bvid,
					title = BilibiliHtml.stripTags(item.string("title").orEmpty()),
					play = item.long("play"),
					durationSeconds = parseLength(item.string("length").orEmpty()),
					createdSeconds = item.long("created"),
				)
			}.orEmpty()

		val counters = counter?.obj("data")
		return BilibiliUp(
			mid = data.long("mid").takeIf { it > 0 } ?: mid,
			name = BilibiliHtml.stripTags(data.string("name").orEmpty()),
			sign = data.string("sign").orEmpty().trim(),
			level = data.int("level"),
			fans = counters?.long("follower") ?: 0L,
			following = counters?.long("following") ?: 0L,
			videos = videoPage?.long("count") ?: 0L,
			official = BilibiliHtml.stripTags(data.obj("official")?.string("title").orEmpty()),
			live = (live?.long("liveStatus") ?: 0L) > 0,
			liveTitle = live?.string("title").orEmpty(),
			roomId = live?.long("roomid") ?: 0L,
			recentVideos = videoList,
			totalVideos = videoPage?.int("count") ?: 0,
			page = videoPage?.int("pn")?.takeIf { it > 0 } ?: page,
			series = parseSeries(collections),
			dynamics = parseDynamics(dynamics),
		)
	}

	/**
	 * Collections and series share one list shape with different id keys.
	 *
	 * A collection carries `season_id` and may be ordered by its owner; a series
	 * carries `series_id`. The tool does not need the difference, only the fact
	 * that both are "sets of videos", so both become [BilibiliUpSeries] with the
	 * flag kept for the label.
	 */
	private fun parseSeries(root: JsonObject?): List<BilibiliUpSeries> {
		val lists = root?.obj("data")?.obj("items_lists") ?: return emptyList()
		fun entries(name: String, collection: Boolean): List<BilibiliUpSeries> =
			lists.array(name)?.mapNotNull { element ->
				val item = element as? JsonObject ?: return@mapNotNull null
				val meta = item.obj("meta") ?: return@mapNotNull null
				val id = meta.long(if (collection) "season_id" else "series_id")
				val title = meta.string(if (collection) "title" else "name").orEmpty()
				if (id <= 0 || title.isBlank()) return@mapNotNull null
				BilibiliUpSeries(
					id = id,
					title = BilibiliHtml.stripTags(title),
					total = meta.int("total"),
					description = BilibiliHtml.stripTags(meta.string("description").orEmpty()),
					isCollection = collection,
				)
			}.orEmpty()

		return entries("seasons_list", collection = true) +
			entries("series_list", collection = false)
	}

	/**
	 * Recent dynamic posts, reduced to text.
	 *
	 * A dynamic post is a union of shapes: text, images with a caption, a
	 * repost, a forwarded video. The text lives in `module_dynamic.desc.text`
	 * for plain posts, in `major.opus.summary.text` for the newer "opus" style,
	 * and in `major.archive.title` for a shared video. A post with none of them
	 * (an image-only post) is not worth a line, so it is dropped rather than
	 * shown as "(untitled)".
	 */
	private fun parseDynamics(root: JsonObject?): List<BilibiliUpDynamic> =
		root?.obj("data")?.array("items")?.mapNotNull { element ->
			val item = element as? JsonObject ?: return@mapNotNull null
			val modules = item.obj("modules") ?: return@mapNotNull null
			val dynamic = modules.obj("module_dynamic") ?: return@mapNotNull null
			val major = dynamic.obj("major")
			val text = sequenceOf(
				dynamic.obj("desc")?.string("text"),
				major?.obj("opus")?.obj("summary")?.string("text"),
				major?.obj("opus")?.string("title"),
				major?.obj("archive")?.string("title")?.let { "shared a video: $it" },
				major?.obj("draw")?.array("items")?.firstOrNull()
					?.let { (it as? JsonObject)?.string("desc") },
			).mapNotNull { it?.takeIf(String::isNotBlank) }.firstOrNull()
				?: return@mapNotNull null
			val author = modules.obj("module_author")
			BilibiliUpDynamic(
				text = BilibiliHtml.stripTags(text).trim(),
				createdAtSeconds = author?.long("pub_ts") ?: 0L,
				kind = dynamicKind(item.string("type").orEmpty()),
			)
		}.orEmpty().take(MAX_DYNAMICS)

	/** The endpoint's enum names are not what a reader wants to see. */
	private fun dynamicKind(type: String): String = when (type) {
		"DYNAMIC_TYPE_AV" -> "video"
		"DYNAMIC_TYPE_DRAW" -> "images"
		"DYNAMIC_TYPE_WORD" -> "text"
		"DYNAMIC_TYPE_ARTICLE" -> "article"
		"DYNAMIC_TYPE_FORWARD" -> "repost"
		"DYNAMIC_TYPE_LIVE_RCMD" -> "live"
		else -> "post"
	}

	/**
	 * `length` is "12:34" or "1:02:03"; zero when the shape is unknown.
	 *
	 * The uploader list is the only endpoint that reports a duration as text.
	 * A parser that threw here would drop a whole video row because of its
	 * length, so an unrecognised shape becomes "unknown".
	 */
	private fun parseLength(length: String): Long {
		if (length.isBlank()) return 0L
		var seconds = 0L
		var parts = 0
		for (piece in length.split(':')) {
			val value = piece.trim().toLongOrNull() ?: return 0L
			seconds = seconds * 60 + value
			parts++
		}
		return if (parts in 1..3) seconds else 0L
	}

	private const val MAX_DYNAMICS = 3
}
