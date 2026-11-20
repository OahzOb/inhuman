package com.inhuman.core.ai.tools

import com.inhuman.core.ai.bilibili.BilibiliBangumi
import com.inhuman.core.ai.bilibili.BilibiliBangumiEpisode
import com.inhuman.core.ai.bilibili.BilibiliDanmaku
import com.inhuman.core.ai.bilibili.BilibiliDanmakuLine
import com.inhuman.core.ai.bilibili.BilibiliDanmakuSegment
import com.inhuman.core.ai.bilibili.BilibiliLiveRoom
import com.inhuman.core.ai.bilibili.BilibiliRankingCategory
import com.inhuman.core.ai.bilibili.BilibiliTrendingItem
import com.inhuman.core.ai.bilibili.BilibiliTrendingKind
import com.inhuman.core.ai.bilibili.BilibiliUp
import com.inhuman.core.ai.bilibili.BilibiliUpDynamic
import com.inhuman.core.ai.bilibili.BilibiliUpOrder
import com.inhuman.core.ai.bilibili.BilibiliUpSeries
import com.inhuman.core.ai.bilibili.BilibiliUpVideo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second batch of tools: what a model reads from them, and which arguments
 * they refuse before spending a request.
 *
 * Same fake as `BilibiliToolsTest`: these tools are formatting and argument
 * shaping over a service, and none of that changes because the endpoint behind
 * it is live.
 */
class BilibiliMoreToolsTest {

	private val up = BilibiliUp(
		mid = 517_327_498L,
		name = "罗翔说刑法",
		sign = "工作邮箱：xfluoxiang@hotmail.com",
		level = 6,
		fans = 32_181_150,
		following = 19,
		videos = 499,
		official = "2025百大UP主",
		live = false,
		liveTitle = "",
		roomId = 22_159_237L,
		recentVideos = listOf(
			BilibiliUpVideo(
				bvid = "BV1v4Wj6AEJr",
				title = "罗翔对谈程乐松",
				play = 527_191,
				durationSeconds = 5_762,
				createdSeconds = 1_794_477_600,
			),
		),
		totalVideos = 499,
		page = 1,
		series = listOf(
			BilibiliUpSeries(
				id = 3_993_361L,
				title = "经典领读",
				total = 22,
				description = "",
				isCollection = true,
			),
		),
		dynamics = listOf(
			BilibiliUpDynamic(text = "新工作室装修好了", createdAtSeconds = 1_794_313_612, kind = "video"),
		),
	)

	@Test
	fun `up tool formats profile, videos and collections`() = runBlocking {
		val service = FakeBilibiliService()
		service.upResult = up
		val tool = BilibiliUpTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("mid", 517_327_498L)
				put("page", 2)
				put("order", "popular")
			},
		)

		assertFalse(result.isError)
		assertEquals(517_327_498L, service.lastMid)
		assertEquals(2, service.lastUpPage)
		assertEquals(BilibiliUpOrder.POPULAR, service.lastUpOrder)
		assertTrue("space url missing", "https://space.bilibili.com/517327498" in result.content)
		assertTrue("followers missing", "32,181,150 followers" in result.content)
		assertTrue("video row missing", "BV1v4Wj6AEJr" in result.content)
		assertTrue("collection missing", "经典领读" in result.content)
		assertTrue("dynamic missing", "新工作室装修好了" in result.content)
		assertTrue("offline state missing", "offline" in result.content)
	}

	@Test
	fun `up tool refuses a missing mid`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliUpTool(service)

		val result = tool.execute(buildJsonObject { })

		assertTrue(result.isError)
		assertEquals(0, service.upCalls)
	}

	@Test
	fun `trending tool formats ranking and hot searches differently`() = runBlocking {
		val service = FakeBilibiliService()
		service.trendingResult = listOf(
			BilibiliTrendingItem.Video(
				bvid = "BV1b2sK6aEJ9",
				title = "《最礼乐崩坏の一集》",
				author = "伤心欲茄222",
				play = 3_975_623,
				pubdateSeconds = 1_794_888_000,
				category = "日常",
			),
		)
		val tool = BilibiliTrendingTool(service)

		val ranking = tool.execute(buildJsonObject { put("category", "digital") })
		assertFalse(ranking.isError)
		assertEquals(BilibiliRankingCategory.DIGITAL, service.lastTrendingCategory)
		assertTrue("ranking bvid missing", "BV1b2sK6aEJ9" in ranking.content)
		assertTrue("ranking play count missing", "3,975,623 plays" in ranking.content)
		assertTrue("ranking follow-up missing", "bili_video" in ranking.content)

		service.trendingResult = listOf(BilibiliTrendingItem.Keyword("罗小黑"))
		val hot = tool.execute(buildJsonObject { put("kind", "hot_search") })
		assertFalse(hot.isError)
		assertEquals(BilibiliTrendingKind.HOT_SEARCH, service.lastTrendingKind)
		assertTrue("keyword missing", "罗小黑" in hot.content)
		assertTrue("phrase note missing", "phrases people are searching for" in hot.content)
	}

	@Test
	fun `trending tool refuses an unknown category`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliTrendingTool(service)

		val result = tool.execute(buildJsonObject { put("category", "nonsense") })

		assertTrue(result.isError)
		assertEquals(0, service.trendingCalls)
	}

	@Test
	fun `bangumi tool accepts ss and ep ids`() = runBlocking {
		val service = FakeBilibiliService()
		service.bangumiResult = BilibiliBangumi(
			seasonId = 43_164L,
			title = "孤独摇滚！",
			jpTitle = "",
			score = 9.9,
			scoreCount = 134_510,
			styles = listOf("漫画改", "音乐", "日常"),
			summary = "后藤独是一个喜爱吉他的孤独少女。",
			totalEpisodes = 12,
			status = "已完结, 全12话",
			publishText = "2022年10月30日00:30",
			episodes = listOf(
				BilibiliBangumiEpisode(
					epId = 693_247L,
					aid = 944_573_356L,
					cid = 875_212_290L,
					bvid = "BV1yW4y1j7Ft",
					title = "1",
					longTitle = "孤独的转机",
					pubTimeSeconds = 1_667_061_000L,
					durationSeconds = 1_415L,
					badge = "",
				),
			),
		)
		val tool = BilibiliBangumiTool(service)

		val bySeason = tool.execute(buildJsonObject { put("season_id", "ss43164") })
		assertFalse(bySeason.isError)
		assertEquals(43_164L, service.lastSeasonId)
		assertTrue("episode missing", "ep693247" in bySeason.content)
		assertTrue("score missing", "score: 9.9" in bySeason.content)
		assertTrue("status missing", "已完结" in bySeason.content)

		val byEpisode = tool.execute(buildJsonObject { put("ep_id", "693247") })
		assertFalse(byEpisode.isError)
		assertEquals(693_247L, service.lastEpId)
	}

	@Test
	fun `bangumi tool refuses when neither id is given`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliBangumiTool(service)

		val result = tool.execute(buildJsonObject { put("season_id", "not-an-id") })

		assertTrue(result.isError)
		assertEquals(0, service.bangumiCalls)
	}

	@Test
	fun `live tool formats room state and passes through the mid path`() = runBlocking {
		val service = FakeBilibiliService()
		service.liveResult = BilibiliLiveRoom(
			roomId = 22_159_237L,
			uid = 517_327_498L,
			anchor = "罗翔说刑法",
			title = "《格黎东篇》",
			live = true,
			areaName = "历史·人文·综合",
			parentAreaName = "知识",
			online = 1_234,
			url = "https://live.bilibili.com/22159237",
		)
		val tool = BilibiliLiveTool(service)

		val byRoom = tool.execute(buildJsonObject { put("room_id", 22_159_237L) })
		assertFalse(byRoom.isError)
		assertTrue("live status missing", "status: live now" in byRoom.content)
		assertTrue("area missing", "知识 · 历史·人文·综合" in byRoom.content)
		assertTrue("viewer count missing", "1,234" in byRoom.content)

		val byMid = tool.execute(buildJsonObject { put("mid", 517_327_498L) })
		assertFalse(byMid.isError)
		assertEquals(517_327_498L, service.lastLiveMid)
	}

	@Test
	fun `live tool refuses without any id`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliLiveTool(service)

		val result = tool.execute(buildJsonObject { })

		assertTrue(result.isError)
		assertEquals(0, service.liveCalls)
	}

	@Test
	fun `danmaku tool reports the sample rather than an archive`() = runBlocking {
		val service = FakeBilibiliService()
		service.danmakuResult = BilibiliDanmaku(
			totalCount = 54_235,
			segmentCount = 10,
			segmentSeconds = 360,
			durationSeconds = 3_435,
			sampled = listOf(
				BilibiliDanmakuSegment(
					index = 1,
					fromSeconds = 0,
					toSeconds = 360,
					entries = 3,
					lines = listOf(
						BilibiliDanmakuLine(atSeconds = 14, text = "热乎的，终于来了！"),
						BilibiliDanmakuLine(atSeconds = 62, text = "AI神力！"),
						BilibiliDanmakuLine(atSeconds = 121, text = "懂你意思"),
					),
				),
			),
		)
		val tool = BilibiliDanmakuTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("bvid", "BV1kMxM6FECM")
				put("from", 0)
				put("to", 360)
			},
		)

		assertFalse(result.isError)
		assertEquals(0L, service.lastFrom)
		assertEquals(360L, service.lastTo)
		assertTrue("sample note missing", "this is a sample, not every danmaku" in result.content)
		assertTrue("window count missing", "windows: 10" in result.content)
		assertTrue("timestamped line missing", "[0:14] 热乎的，终于来了！" in result.content)
		assertTrue("empty-window note missing for a full result", "[0:00–6:00" in result.content)
	}

	@Test
	fun `danmaku tool treats an empty sample as an answer`() = runBlocking {
		val service = FakeBilibiliService()
		service.danmakuResult = BilibiliDanmaku(
			totalCount = 0,
			segmentCount = 1,
			segmentSeconds = 360,
			durationSeconds = 200,
			sampled = listOf(
				BilibiliDanmakuSegment(1, 0, 360, entries = 0, lines = emptyList()),
			),
		)
		val tool = BilibiliDanmakuTool(service)

		val result = tool.execute(buildJsonObject { put("bvid", "BV1kMxM6FECM") })

		assertFalse(result.isError)
		assertTrue("empty-sample note missing", "returned no danmaku" in result.content)
	}

	@Test
	fun `danmaku tool rejects an inverted window`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliDanmakuTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("bvid", "BV1kMxM6FECM")
				put("from", 300)
				put("to", 200)
			},
		)

		assertTrue(result.isError)
		assertEquals(0, service.danmakuCalls)
	}
}
