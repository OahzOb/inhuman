package com.inhuman.core.ai.bilibili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing for the second read surface: uploader pages, discovery lists, shows,
 * live rooms and danmaku.
 *
 * Same rule as the first file: fixtures are captures of the live anonymous
 * endpoints rather than hand-written JSON. The danmaku fixtures are bytes
 * because that surface is protobuf, and the parser's job there is to survive an
 * unknown field and a truncated body without taking the tool call down.
 */
class BilibiliMoreParsersTest {

	private fun fixture(name: String): JsonObject {
		val stream = javaClass.classLoader.getResourceAsStream("bilibili/$name")
			?: error("missing fixture bilibili/$name")
		return stream.bufferedReader(Charsets.UTF_8).use {
			Json.parseToJsonElement(it.readText()).jsonObject
		}
	}

	private fun bytes(name: String): ByteArray {
		val stream = javaClass.classLoader.getResourceAsStream("bilibili/$name")
			?: error("missing fixture bilibili/$name")
		return stream.use { it.readBytes() }
	}

	private fun uploader(): BilibiliUp = BilibiliUpParsers.parse(
		mid = 517_327_498L,
		page = 1,
		profile = fixture("up_acc_info.json"),
		counter = fixture("up_relation_stat.json"),
		videos = fixture("up_arc_search.json"),
		collections = fixture("up_seasons.json"),
		dynamics = fixture("up_dynamic.json"),
	)

	@Test
	fun `uploader fixture parses profile counters and live state`() {
		val up = uploader()

		assertEquals(517_327_498L, up.mid)
		assertEquals("罗翔说刑法", up.name)
		assertEquals(6, up.level)
		assertEquals(32_181_150L, up.fans)
		assertEquals(19L, up.following)
		assertEquals(499, up.totalVideos)
		assertTrue("official title missing", up.official.contains("百大"))
		assertFalse("the room is offline", up.live)
		assertEquals(22_159_237L, up.roomId)
	}

	@Test
	fun `uploader fixture parses videos with text durations`() {
		val up = uploader()

		assertEquals(3, up.recentVideos.size)
		val first = up.recentVideos.first()
		assertEquals("BV1v4Wj6AEJr", first.bvid)
		assertTrue("play count missing", first.play > 0)
		assertEquals("96:02 must become seconds", 5_762L, first.durationSeconds)
		assertEquals(1_794_477_600L, first.createdSeconds)
	}

	@Test
	fun `uploader fixture parses collections and series`() {
		val up = uploader()

		assertEquals(2, up.series.size)
		val collection = up.series.first { it.isCollection }
		assertEquals(3_993_361L, collection.id)
		assertEquals("经典领读", collection.title)
		assertEquals(22, collection.total)
		val series = up.series.first { !it.isCollection }
		assertEquals(2_229_877L, series.id)
		assertEquals("直播回放", series.title)
	}

	@Test
	fun `uploader fixture reads dynamic text from all three shapes`() {
		val up = uploader()

		assertEquals(3, up.dynamics.size)
		assertTrue("opus text missing", up.dynamics.any { it.text.contains("欢迎大家加入我们") })
		assertTrue("desc text missing", up.dynamics.any { it.text.contains("新工作室") })
		assertTrue(
			"shared-video text missing",
			up.dynamics.any { it.text.startsWith("shared a video:") },
		)
		assertTrue("dynamic time missing", up.dynamics.first().createdAtSeconds > 0)
	}

	@Test
	fun `ranking and popular fixtures parse video lists`() {
		val ranking = BilibiliTrendingParsers.parseRanking(fixture("ranking.json"))
		val popular = BilibiliTrendingParsers.parsePopular(fixture("popular.json"))

		assertEquals(3, ranking.size)
		assertEquals("BV1b2sK6aEJ9", ranking.first().bvid)
		assertTrue("ranking title missing", ranking.first().title.isNotBlank())
		assertTrue("ranking author missing", ranking.first().author.isNotBlank())
		assertTrue("ranking play count missing", ranking.first().play > 0)
		assertEquals("日常", ranking.first().category)
		assertEquals(3, popular.size)
		assertTrue("popular bvid missing", popular.first().bvid.startsWith("BV"))
	}

	@Test
	fun `hot search fixture parses keywords`() {
		val hot = BilibiliTrendingParsers.parseHotSearch(fixture("hot_search.json"))

		assertEquals(5, hot.size)
		assertTrue("keyword missing", hot.first().keyword.isNotBlank())
	}

	@Test
	fun `show fixture parses score summary and episodes`() {
		val show = BilibiliBangumiParsers.parse(fixture("bangumi_season.json"))

		assertEquals(43_164L, show.seasonId)
		assertEquals("孤独摇滚！", show.title)
		assertEquals(9.9, show.score, 0.001)
		assertEquals(134_510L, show.scoreCount)
		assertEquals(12, show.totalEpisodes)
		assertEquals("已完结, 全12话", show.status)
		assertEquals("2022年10月30日00:30", show.publishText)
		assertEquals(listOf("漫画改", "音乐", "日常"), show.styles)
		assertTrue("summary missing", show.summary.contains("后藤独"))
		assertEquals(2, show.episodes.size)
		val first = show.episodes.first()
		assertEquals(693_247L, first.epId)
		assertEquals("BV1yW4y1j7Ft", first.bvid)
		assertEquals("孤独的转机", first.longTitle)
		assertEquals("episode durations are milliseconds in the payload", 1_415L, first.durationSeconds)
	}

	@Test
	fun `live room fixture parses area and state`() {
		val room = BilibiliLiveParsers.parseRoom(fixture("live_room.json"))

		assertEquals(22_159_237L, room.roomId)
		assertEquals(517_327_498L, room.uid)
		assertFalse("the fixture room is offline", room.live)
		assertEquals("历史·人文·综合", room.areaName)
		assertEquals("知识", room.parentAreaName)
		assertEquals("https://live.bilibili.com/22159237", room.url)
	}

	@Test
	fun `live room by uploader fixture parses the thinner shape`() {
		val room = BilibiliLiveParsers.parseRoomByUid(fixture("live_room_by_uid.json"), 42L)

		assertEquals(22_159_237L, room.roomId)
		assertEquals(42L, room.uid)
		assertFalse(room.live)
		assertTrue("title missing", room.title.isNotBlank())
		assertTrue("url missing", room.url.startsWith("https://"))
	}

	@Test
	fun `danmaku config fixture parses window size and count`() {
		val config = BilibiliDanmakuParser.parseSegmentConfig(bytes("dm_view.bin"))

		assertNotNull(config)
		assertEquals(360_000L, config!!.segmentMillis)
		assertEquals(10, config.segmentCount)
	}

	@Test
	fun `danmaku segment fixture parses readable lines`() {
		val lines = BilibiliDanmakuParser.parseSegment(bytes("dm_seg1.bin"))

		assertTrue("no danmaku parsed: ${lines.size}", lines.size > 200)
		assertTrue("danmaku text missing", lines.all { it.text.isNotBlank() })
		assertTrue("a segment-1 danmaku lies outside the window", lines.all { it.atSeconds < 360 })
	}

	@Test
	fun `truncated protobuf degrades instead of throwing`() {
		// A length-delimited field that claims more bytes than remain.
		assertTrue(BilibiliDanmakuParser.parseSegment(byteArrayOf(0x0A, 0x7F, 0x08)).isEmpty())
		assertNull(BilibiliDanmakuParser.parseSegmentConfig(byteArrayOf(0x0A, 0x7F)))
	}
}
