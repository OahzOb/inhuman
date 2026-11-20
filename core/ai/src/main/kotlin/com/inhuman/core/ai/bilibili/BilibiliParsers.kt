package com.inhuman.core.ai.bilibili

import kotlinx.serialization.json.JsonObject

/**
 * Reads the JSON shapes the endpoints actually return into typed values.
 *
 * ## Why the parsing is separate from the client
 *
 * Fixtures captured from the live service are cheap and stable; the service is
 * neither. Parsing is where a renamed field silently turns into "no title" or
 * "no comments", and that is exactly the failure the response model must not
 * hide: a field that is missing becomes an explicit default only where the
 * default means something (a count of zero). The title and id have no such
 * default, so a hit without them is dropped rather than shown blank.
 *
 * Nullability mirrors the endpoint rather than a convenient model: `desc` is
 * routinely empty, `data` is present only on a successful call, and `pages` is
 * absent for some redirected content.
 *
 * ## Why one parser file for four search kinds
 *
 * The four searches are one endpoint with one differing field, so they are one
 * parser with one `when`. The uploader and show pages get their own files
 * because they are different endpoints with their own shapes.
 */
internal object BilibiliParsers {

	fun parseSearch(root: JsonObject): List<BilibiliSearchHit> {
		val data = root.obj("data") ?: return emptyList()
		val results = data.array("result") ?: return emptyList()
		return results.mapNotNull { element ->
			val item = element as? JsonObject ?: return@mapNotNull null
			when (item.string("type")) {
				"video" -> videoHit(item)
				"article" -> articleHit(item)
				"bili_user" -> userHit(item)
				"media_bangumi", "media_ft" -> bangumiHit(item)
				else -> null
			}
		}
	}

	fun parseVideo(root: JsonObject): BilibiliVideoInfo {
		val data = root.obj("data")
			?: throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"video response had no data",
			)
		val owner = data.obj("owner")
		return BilibiliVideoInfo(
			bvid = data.string("bvid").orEmpty(),
			aid = data.long("aid"),
			cid = data.long("cid"),
			title = BilibiliHtml.stripTags(data.string("title").orEmpty()),
			description = data.string("desc").orEmpty().trim(),
			ownerName = owner?.string("name").orEmpty(),
			ownerMid = owner?.long("mid") ?: 0L,
			pubdateSeconds = data.long("pubdate"),
			durationSeconds = data.long("duration"),
			category = data.string("tname").orEmpty(),
			stats = stats(data.obj("stat")),
			pages = data.array("pages")?.mapNotNull { element ->
				val page = element as? JsonObject ?: return@mapNotNull null
				BilibiliVideoPage(
					cid = page.long("cid"),
					page = page.int("page"),
					title = BilibiliHtml.stripTags(page.string("part").orEmpty()),
					durationSeconds = page.long("duration"),
				)
			}.orEmpty(),
			// The public page lists subtitle tracks with an empty URL; the count
			// is still the honest answer to "does this video have subtitles".
			subtitleTracks = data.obj("subtitle")?.array("list")?.size ?: 0,
		)
	}

	/** The related list answers with a bare array under `data`. */
	fun parseRelated(root: JsonObject): List<BilibiliVideoSummary> =
		root.array("data")?.mapNotNull { element ->
			val item = element as? JsonObject ?: return@mapNotNull null
			val bvid = item.string("bvid")?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			BilibiliVideoSummary(
				bvid = bvid,
				title = BilibiliHtml.stripTags(item.string("title").orEmpty()),
				author = BilibiliHtml.stripTags(item.obj("owner")?.string("name").orEmpty()),
				play = item.obj("stat")?.long("view") ?: 0L,
				durationSeconds = item.long("duration"),
				pubdateSeconds = item.long("pubdate"),
				category = item.string("tname").orEmpty(),
			)
		}.orEmpty()

	fun parseComments(root: JsonObject): BilibiliComments {
		val data = root.obj("data")
			?: return BilibiliComments(emptyList(), emptyList())
		return BilibiliComments(
			pinned = commentList(data, "top_replies"),
			comments = commentList(data, "replies"),
		)
	}

	/**
	 * The cursor that asks the comment endpoint for the next page.
	 *
	 * Null means "this is the last page". The value is opaque — an offset, not
	 * a page number — so it is carried rather than calculated.
	 */
	fun parseNextCursor(root: JsonObject): Long? {
		val cursor = root.obj("data")?.obj("cursor") ?: return null
		val next = cursor.long("next")
		return if (next > 0) next else null
	}

	fun parseArticle(root: JsonObject): BilibiliArticle {
		val data = root.obj("data")
			?: throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"article response had no data",
			)
		val author = data.obj("author")
		val tags = data.array("tags")?.mapNotNull { element ->
			// Older responses carry tag objects; a missing name is not worth a
			// placeholder, because a tag list with blanks is noise in the result.
			(element as? JsonObject)?.string("tag_name")?.takeIf { it.isNotBlank() }
		}.orEmpty()
		return BilibiliArticle(
			cvId = data.long("id"),
			title = BilibiliHtml.stripTags(data.string("title").orEmpty()),
			author = author?.string("name") ?: data.string("author").orEmpty(),
			publishTimeSeconds = data.long("publish_time"),
			words = data.long("words"),
			stats = stats(data.obj("stats")),
			tags = tags,
			body = BilibiliHtml.toText(data.string("content").orEmpty()),
		)
	}

	private fun videoHit(item: JsonObject): BilibiliSearchHit.Video? {
		val bvid = item.string("bvid")?.takeIf { it.isNotBlank() } ?: return null
		return BilibiliSearchHit.Video(
			bvid = bvid,
			aid = item.long("aid"),
			ownerMid = item.long("mid"),
			title = BilibiliHtml.stripTags(item.string("title").orEmpty()),
			author = BilibiliHtml.stripTags(item.string("author").orEmpty()),
			play = item.long("play"),
			commentCount = item.long("review"),
			// Search renders this as "3:16"; the view endpoint uses seconds.
			// Keeping each endpoint's own shape here avoids inventing a duration
			// for a hit whose caller has not asked for the video's details.
			duration = item.string("duration").orEmpty(),
			pubdateSeconds = item.long("pubdate"),
			description = BilibiliHtml.stripTags(item.string("description").orEmpty()),
		)
	}

	private fun articleHit(item: JsonObject): BilibiliSearchHit.Article? {
		val cvId = item.long("id").takeIf { it > 0 } ?: return null
		return BilibiliSearchHit.Article(
			cvId = cvId,
			ownerMid = item.long("mid"),
			title = BilibiliHtml.stripTags(item.string("title").orEmpty()),
			author = BilibiliHtml.stripTags(item.string("author").orEmpty()),
			view = item.long("view"),
			like = item.long("like"),
			replyCount = item.long("reply"),
			pubdateSeconds = item.long("pubdate"),
			description = BilibiliHtml.stripTags(item.string("desc").orEmpty()),
		)
	}

	private fun userHit(item: JsonObject): BilibiliSearchHit.User? {
		val mid = item.long("mid").takeIf { it > 0 } ?: return null
		return BilibiliSearchHit.User(
			mid = mid,
			name = BilibiliHtml.stripTags(item.string("uname").orEmpty()),
			fans = item.long("fans"),
			videos = item.long("videos"),
			level = item.int("level"),
			sign = item.string("usign").orEmpty(),
			official = item.obj("official_verify")?.string("desc").orEmpty(),
			live = item.long("is_live") > 0,
		)
	}

	private fun bangumiHit(item: JsonObject): BilibiliSearchHit.Bangumi? {
		val seasonId = item.long("season_id").takeIf { it > 0 } ?: return null
		val styles = item.string("styles").orEmpty()
			.split('/')
			.map { it.trim() }
			.filter { it.isNotEmpty() }
		return BilibiliSearchHit.Bangumi(
			seasonId = seasonId,
			title = BilibiliHtml.stripTags(item.string("title").orEmpty()),
			score = item.obj("media_score")?.double("score") ?: 0.0,
			episodes = item.int("ep_size"),
			publishSeconds = item.long("pubtime"),
			styles = styles,
			description = BilibiliHtml.stripTags(item.string("desc").orEmpty()),
			status = item.string("index_show").orEmpty(),
		)
	}

	private fun commentList(data: JsonObject, name: String): List<BilibiliComment> =
		data.array(name)?.mapNotNull { element ->
			(element as? JsonObject)?.let(::comment)
		}.orEmpty()

	private fun comment(item: JsonObject): BilibiliComment {
		val member = item.obj("member")
		return BilibiliComment(
			rpid = item.long("rpid"),
			author = member?.string("uname").orEmpty(),
			level = member?.obj("level_info")?.int("current_level") ?: 0,
			content = item.obj("content")?.string("message").orEmpty(),
			like = item.long("like"),
			replyCount = item.long("rcount"),
			createdAtSeconds = item.long("ctime"),
			replies = item.array("replies")?.mapNotNull { element ->
				val reply = element as? JsonObject ?: return@mapNotNull null
				BilibiliCommentReply(
					author = reply.obj("member")?.string("uname").orEmpty(),
					content = reply.obj("content")?.string("message").orEmpty(),
					like = reply.long("like"),
				)
			}.orEmpty(),
		)
	}

	private fun stats(stat: JsonObject?): BilibiliStats {
		if (stat == null) return BilibiliStats()
		return BilibiliStats(
			view = stat.long("view"),
			danmaku = stat.long("danmaku"),
			reply = stat.long("reply"),
			favorite = stat.long("favorite"),
			coin = stat.long("coin"),
			like = stat.long("like"),
			share = stat.long("share"),
		)
	}

	/**
	 * The subtitle tracks of one part, as the player endpoint reports them.
	 *
	 * `type` is the endpoint's own flag (0 = uploaded, 1 = AI generated); the
	 * client also treats an `ai-` language prefix as AI, because the flag has
	 * been observed to lag the track.
	 */
	fun parseSubtitleTracks(root: JsonObject): List<ParsedSubtitleTrack> {
		val subtitle = root.obj("data")?.obj("subtitle") ?: return emptyList()
		return subtitle.array("subtitles")?.mapNotNull { element ->
			val track = element as? JsonObject ?: return@mapNotNull null
			val url = track.string("subtitle_url").orEmpty().ifBlank { return@mapNotNull null }
			ParsedSubtitleTrack(
				language = track.string("lan").orEmpty(),
				label = track.string("lan_doc").orEmpty(),
				url = url,
				type = track.int("type"),
			)
		}.orEmpty()
	}

	/**
	 * The cues of one downloaded subtitle file.
	 *
	 * `from` and `to` are seconds as decimals, which is why they go through
	 * [JsonObject.double] rather than the integer reader: `longOrNull` returns
	 * null for `"12.5"`, and a whole transcript of null start times would parse
	 * silently into a timestamp-less result.
	 */
	fun parseSubtitleCues(root: JsonObject): List<BilibiliTranscriptCue> =
		root.array("body")?.mapNotNull { element ->
			val line = element as? JsonObject ?: return@mapNotNull null
			val text = line.string("content")?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			BilibiliTranscriptCue(
				fromSeconds = line.double("from").toLong(),
				toSeconds = line.double("to").toLong(),
				text = text,
			)
		}.orEmpty()

	/** Bilibili's own AI summary of one part, or null when it has none. */
	fun parseAiSummary(root: JsonObject): ParsedAiSummary? {
		val data = root.obj("data") ?: return null
		val model = data.obj("model_result") ?: return null
		val summary = model.string("summary").orEmpty().trim()
		val outline = model.array("outline")?.mapNotNull { element ->
			val segment = element as? JsonObject ?: return@mapNotNull null
			ParsedOutline(
				title = BilibiliHtml.stripTags(segment.string("title").orEmpty()),
				timestampSeconds = segment.long("timestamp"),
				points = segment.array("part_outline")?.mapNotNull { pointElement ->
					val point = pointElement as? JsonObject ?: return@mapNotNull null
					val content = BilibiliHtml.stripTags(point.string("content").orEmpty())
					if (content.isBlank()) {
						null
					} else {
						ParsedPoint(timestampSeconds = point.long("timestamp"), content = content)
					}
				}.orEmpty(),
			)
		}.orEmpty()
		if (summary.isBlank() && outline.isEmpty()) return null
		return ParsedAiSummary(summary = summary, outline = outline)
	}
}

/** One subtitle track the player endpoint offered. */
internal data class ParsedSubtitleTrack(
	val language: String,
	val label: String,
	val url: String,
	val type: Int,
)

/** Bilibili's AI summary plus its timeline outline. */
internal data class ParsedAiSummary(
	val summary: String,
	val outline: List<ParsedOutline>,
)

internal data class ParsedOutline(
	val title: String,
	val timestampSeconds: Long,
	val points: List<ParsedPoint>,
)

internal data class ParsedPoint(
	val timestampSeconds: Long,
	val content: String,
)
