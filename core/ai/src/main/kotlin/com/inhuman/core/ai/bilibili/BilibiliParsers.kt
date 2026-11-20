package com.inhuman.core.ai.bilibili

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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
		)
	}

	fun parseComments(root: JsonObject): BilibiliComments {
		val data = root.obj("data")
			?: return BilibiliComments(emptyList(), emptyList())
		return BilibiliComments(
			pinned = commentList(data, "top_replies"),
			comments = commentList(data, "replies"),
		)
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
			title = BilibiliHtml.stripTags(item.string("title").orEmpty()),
			author = BilibiliHtml.stripTags(item.string("author").orEmpty()),
			view = item.long("view"),
			like = item.long("like"),
			replyCount = item.long("reply"),
			pubdateSeconds = item.long("pubdate"),
			description = BilibiliHtml.stripTags(item.string("desc").orEmpty()),
		)
	}

	private fun commentList(data: JsonObject, name: String): List<BilibiliComment> =
		data.array(name)?.mapNotNull { element ->
			(element as? JsonObject)?.let(::comment)
		}.orEmpty()

	private fun comment(item: JsonObject): BilibiliComment {
		val member = item.obj("member")
		return BilibiliComment(
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
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonObject.string(name: String): String? =
	(this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(name: String): Long =
	(this[name] as? JsonPrimitive)?.longOrNull ?: 0L

private fun JsonObject.int(name: String): Int =
	(this[name] as? JsonPrimitive)?.intOrNull ?: 0
