package com.inhuman.core.ai.bilibili

import kotlinx.serialization.json.JsonObject

/**
 * Reads Bilibili's own discovery lists.
 *
 * The ranking and the popular feed answer with the same video shape; only the
 * hot-search list is different, and it is keywords rather than videos. The
 * three are one tool because they answer one question ("what is big right
 * now") and choosing between them is a parameter, not a second name.
 */
internal object BilibiliTrendingParsers {

	fun parseRanking(root: JsonObject): List<BilibiliTrendingItem.Video> =
		parseVideoList(root.obj("data")?.array("list") ?: return emptyList())

	fun parsePopular(root: JsonObject): List<BilibiliTrendingItem.Video> =
		parseVideoList(root.obj("data")?.array("list") ?: return emptyList())

	fun parseHotSearch(root: JsonObject): List<BilibiliTrendingItem.Keyword> {
		val data = root.obj("data")
		val trending = data?.obj("trending")?.array("list")
			?: data?.array("trending_list")
			?: data?.array("list")
			?: return emptyList()
		return trending.mapNotNull { element ->
			val item = element as? JsonObject ?: return@mapNotNull null
			val keyword = item.string("keyword")?.takeIf { it.isNotBlank() }
				?: item.string("show_name")?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			BilibiliTrendingItem.Keyword(keyword)
		}
	}

	private fun parseVideoList(
		items: kotlinx.serialization.json.JsonArray,
	): List<BilibiliTrendingItem.Video> = items.mapNotNull { element ->
		val item = element as? JsonObject ?: return@mapNotNull null
		val bvid = item.string("bvid")?.takeIf { it.isNotBlank() }
			?: return@mapNotNull null
		BilibiliTrendingItem.Video(
			bvid = bvid,
			title = BilibiliHtml.stripTags(item.string("title").orEmpty()),
			author = BilibiliHtml.stripTags(item.obj("owner")?.string("name").orEmpty()),
			play = item.obj("stat")?.long("view") ?: 0L,
			pubdateSeconds = item.long("pubdate"),
			category = item.string("tname").orEmpty(),
		)
	}
}
