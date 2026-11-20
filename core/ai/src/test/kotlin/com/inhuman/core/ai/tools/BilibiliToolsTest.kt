package com.inhuman.core.ai.tools

import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliArticle
import com.inhuman.core.ai.bilibili.BilibiliComment
import com.inhuman.core.ai.bilibili.BilibiliCommentReply
import com.inhuman.core.ai.bilibili.BilibiliCommentSort
import com.inhuman.core.ai.bilibili.BilibiliComments
import com.inhuman.core.ai.bilibili.BilibiliSearchDuration
import com.inhuman.core.ai.bilibili.BilibiliSearchHit
import com.inhuman.core.ai.bilibili.BilibiliSearchKind
import com.inhuman.core.ai.bilibili.BilibiliStats
import com.inhuman.core.ai.bilibili.BilibiliTranscript
import com.inhuman.core.ai.bilibili.BilibiliTranscriptCue
import com.inhuman.core.ai.bilibili.BilibiliTranscriptPreference
import com.inhuman.core.ai.bilibili.BilibiliTranscriptSource
import com.inhuman.core.ai.bilibili.BilibiliVideoInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tools' side of the boundary: argument validation, caps and error text.
 *
 * The service is faked so nothing here depends on Bilibili's uptime or on the
 * anonymous risk-control mood. What is asserted is what the model reads: a
 * truncation must be announced, an empty comment list must not look like a
 * failed request, and a URL must never reach the client as an id.
 */
class BilibiliToolsTest {

	private val videoHit = BilibiliSearchHit.Video(
		bvid = "BV1das46aEKM",
		aid = 117_640_865_519_199L,
		ownerMid = 349_308_634_058_199L,
		title = "标题",
		author = "UP主",
		play = 68_060,
		commentCount = 605,
		duration = "3:16",
		pubdateSeconds = 1_795_078_800,
		description = "摘要",
	)

	private val articleHit = BilibiliSearchHit.Article(
		cvId = 34_975_835L,
		ownerMid = 1_177_593_795L,
		title = "专栏标题",
		author = "作者",
		view = 56_613,
		like = 983,
		replyCount = 18,
		pubdateSeconds = 1_717_248_600,
		description = "摘要",
	)

	private val videoInfo = BilibiliVideoInfo(
		bvid = "BV1das46aEKM",
		aid = 1L,
		title = "标题",
		description = "视频简介",
		ownerName = "UP主",
		ownerMid = 42L,
		pubdateSeconds = 1_795_078_800,
		durationSeconds = 196,
		category = "手机游戏",
		stats = BilibiliStats(view = 67_972, danmaku = 175, reply = 605, like = 3_234),
		pages = emptyList(),
	)

	private val article = BilibiliArticle(
		cvId = 34_975_835L,
		title = "专栏标题",
		author = "作者",
		publishTimeSeconds = 1_717_248_600,
		words = 171,
		stats = BilibiliStats(view = 69_494, like = 983, reply = 18),
		tags = emptyList(),
		body = "第一段\n第二段",
	)

	@Test
	fun `search clamps arguments and formats both kinds`() = runBlocking {
		val service = FakeBilibiliService()
		service.searchResult = listOf(videoHit, articleHit)
		val tool = BilibiliSearchTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("keyword", "原神")
				put("limit", 999)
				put("page", 99)
			},
		)

		assertFalse(result.isError)
		assertEquals(10, service.lastLimit)
		assertEquals(5, service.lastPage)
		assertTrue("video id missing from result", "BV1das46aEKM" in result.content)
		assertTrue("article id missing from result", "cv34975835" in result.content)
		assertTrue("page number missing", "page 5" in result.content)
	}

	@Test
	fun `search passes kind and filters through to the service`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliSearchTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("keyword", "罗翔")
				put("kind", "user")
				put("duration", "30_60")
				put("published_after", "2025-01-01")
				put("partition", 188)
			},
		)

		assertFalse(result.isError)
		assertEquals(BilibiliSearchKind.USER, service.lastKind)
		val filters = service.lastFilters
		assertTrue("filters were not passed", filters != null)
		assertEquals(BilibiliSearchDuration.THIRTY_TO_SIXTY, filters!!.duration)
		assertEquals(1_735_660_800L, filters.publishedAfterSeconds)
		assertEquals(188, filters.partitionId)
		// The note prints the date the caller wrote, not a zone-rendered
		// version of the epoch it became.
		assertTrue(
			"filter note missing: ${result.content}",
			"length 30 to 60 minutes, from 2025-01-01, partition 188" in result.content,
		)
	}

	@Test
	fun `search rejects an unreadable date before reaching the service`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliSearchTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("keyword", "原神")
				put("published_after", "last year")
			},
		)

		assertTrue(result.isError)
		assertEquals(0, service.searchCalls)
	}

	@Test
	fun `search rejects an unknown kind before reaching the service`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliSearchTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("keyword", "原神")
				put("kind", "channel")
			},
		)

		assertTrue(result.isError)
		assertEquals(0, service.searchCalls)
	}

	@Test
	fun `search formats uploader and show hits with their follow-up ids`() = runBlocking {
		val service = FakeBilibiliService()
		service.searchResult = listOf(
			BilibiliSearchHit.User(
				mid = 517_327_498L,
				name = "罗翔说刑法",
				fans = 32_181_151,
				videos = 498,
				level = 6,
				sign = "工作邮箱",
				official = "2025百大UP主",
				live = false,
			),
			BilibiliSearchHit.Bangumi(
				seasonId = 43_164L,
				title = "孤独摇滚！",
				score = 9.9,
				episodes = 12,
				publishSeconds = 1_665_158_400L,
				styles = listOf("音乐"),
				description = "后藤独",
				status = "全12话",
			),
		)
		val tool = BilibiliSearchTool(service)

		val users = tool.execute(
			buildJsonObject {
				put("keyword", "罗翔")
				put("kind", "user")
			},
		)
		assertFalse(users.isError)
		assertTrue("mid missing", "mid 517327498" in users.content)
		assertTrue("user follow-up missing", "bili_up" in users.content)

		val shows = tool.execute(
			buildJsonObject {
				put("keyword", "孤独摇滚")
				put("kind", "bangumi")
			},
		)
		assertFalse(shows.isError)
		assertTrue("season id missing", "ss43164" in shows.content)
		assertTrue("show follow-up missing", "bili_bangumi" in shows.content)
	}

	@Test
	fun `video rejects a URL and never hands it to the client`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliVideoTool(service)

		val result = tool.execute(
			buildJsonObject { put("bvid", "https://www.bilibili.com/video/BV1das46aEKM") },
		)

		assertTrue(result.isError)
		assertEquals(0, service.videoCalls)
	}

	@Test
	fun `video formats stats, url and subtitle hint`() = runBlocking {
		val service = FakeBilibiliService()
		service.videoResult = videoInfo
		val tool = BilibiliVideoTool(service)

		val result = tool.execute(buildJsonObject { put("bvid", "bv1das46aEKM") })

		assertFalse(result.isError)
		assertTrue(result.content.startsWith("BV1das46aEKM"))
		assertTrue("stats missing", "67,972 views" in result.content)
		assertTrue("description missing", "视频简介" in result.content)
		assertTrue("url missing", "https://www.bilibili.com/video/BV1das46aEKM" in result.content)
		assertTrue("subtitle hint missing", "subtitles: none advertised" in result.content)
		assertEquals("lowercase bv should be normalised", "BV1das46aEKM", service.lastBvid)

		service.videoResult = videoInfo.copy(subtitleTracks = 2)
		val withSubtitles = tool.execute(buildJsonObject { put("bvid", "BV1das46aEKM") })
		assertTrue(
			"subtitle hint missing",
			withSubtitles.content.contains("2 track(s) advertised"),
		)
	}

	@Test
	fun `empty comments are an answer and not an error`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliCommentsTool(service)

		val result = tool.execute(buildJsonObject { put("bvid", "BV1das46aEKM") })

		assertFalse(result.isError)
		assertTrue(result.content.contains("No comments"))
	}

	@Test
	fun `comments format pinned entries, nested replies and rpids`() = runBlocking {
		val service = FakeBilibiliService()
		service.commentsResult = BilibiliComments(
			pinned = listOf(
				BilibiliComment(
					rpid = 111L,
					author = "置顶人",
					level = 6,
					content = "置顶内容",
					like = 46,
					replyCount = 3,
					createdAtSeconds = 1_795_078_825,
					replies = emptyList(),
				),
			),
			comments = listOf(
				BilibiliComment(
					rpid = 222L,
					author = "评论人",
					level = 5,
					content = "评论内容",
					like = 47,
					replyCount = 2,
					createdAtSeconds = 1_795_082_074,
					replies = listOf(
						BilibiliCommentReply(author = "回复人", content = "回复内容", like = 2),
					),
				),
			),
		)
		val tool = BilibiliCommentsTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("bvid", "BV1das46aEKM")
				put("sort", "new")
			},
		)

		assertFalse(result.isError)
		assertTrue("pinned block missing", "pinned" in result.content)
		assertTrue("pinned comment missing", "置顶内容" in result.content)
		assertTrue("reply missing", "回复人" in result.content)
		assertTrue("rpid missing", "rpid 222" in result.content)
		assertEquals(BilibiliCommentSort.NEW, service.lastSort)
	}

	@Test
	fun `comment root reads that thread instead of the list`() = runBlocking {
		val service = FakeBilibiliService()
		service.threadResult = BilibiliComments(
			pinned = emptyList(),
			comments = listOf(
				BilibiliComment(
					rpid = 333L,
					author = "楼中人",
					level = 4,
					content = "楼中楼内容",
					like = 3,
					replyCount = 0,
					createdAtSeconds = 1_795_082_074,
					replies = emptyList(),
				),
			),
		)
		val tool = BilibiliCommentsTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("bvid", "BV1das46aEKM")
				put("root", 222L)
			},
		)

		assertFalse(result.isError)
		assertEquals(222L, service.lastRoot)
		assertTrue("thread header missing", "replies under comment 222" in result.content)
		assertTrue("thread reply missing", "楼中楼内容" in result.content)
	}

	@Test
	fun `article comments are read through the cv id path`() = runBlocking {
		val service = FakeBilibiliService()
		service.articleCommentsResult = BilibiliComments(
			pinned = emptyList(),
			comments = listOf(
				BilibiliComment(
					rpid = 444L,
					author = "专栏读者",
					level = 3,
					content = "专栏评论",
					like = 1,
					replyCount = 0,
					createdAtSeconds = 1_795_082_074,
					replies = emptyList(),
				),
			),
		)
		val tool = BilibiliCommentsTool(service)

		val result = tool.execute(buildJsonObject { put("cv_id", "cv34975835") })

		assertFalse(result.isError)
		assertEquals(1, service.articleCommentCalls)
		assertEquals(34_975_835L, service.lastCvId)
		assertTrue("article comment missing", "专栏评论" in result.content)
	}

	@Test
	fun `comments demand exactly one id`() = runBlocking {
		val service = FakeBilibiliService()
		val tool = BilibiliCommentsTool(service)

		val missing = tool.execute(buildJsonObject { })
		assertTrue(missing.isError)

		val both = tool.execute(
			buildJsonObject {
				put("bvid", "BV1das46aEKM")
				put("cv_id", "34975835")
			},
		)
		assertFalse("a BV id wins when both are present", both.isError)
		assertEquals(0, service.articleCommentCalls)
	}

	@Test
	fun `article truncation is announced`() = runBlocking {
		val service = FakeBilibiliService()
		service.articleResult = article.copy(body = "字".repeat(BilibiliArticleTool.MAX_BODY_CHARS + 10))
		val tool = BilibiliArticleTool(service)

		val result = tool.execute(buildJsonObject { put("id", "cv34975835") })

		assertFalse(result.isError)
		assertTrue("no truncation marker", "[truncated at 20000 characters" in result.content)
		assertEquals(34_975_835L, service.lastCvId)
	}

	@Test
	fun `article accepts plain digits and refuses a URL`() = runBlocking {
		val service = FakeBilibiliService()
		service.articleResult = article
		val tool = BilibiliArticleTool(service)

		val ok = tool.execute(buildJsonObject { put("id", "34975835") })
		assertFalse(ok.isError)

		val refused = tool.execute(
			buildJsonObject { put("id", "https://www.bilibili.com/read/cv34975835") },
		)
		assertTrue(refused.isError)
	}

	@Test
	fun `a risk-control refusal becomes readable advice`() = runBlocking {
		val service = FakeBilibiliService()
		service.failure = BilibiliApiException(-352, "风控校验失败")
		val tool = BilibiliSearchTool(service)

		val result = tool.execute(buildJsonObject { put("keyword", "原神") })

		assertTrue(result.isError)
		assertTrue("advice missing: ${result.content}", "risk control" in result.content)
	}

	@Test
	fun `an empty article body is an error not an empty success`() = runBlocking {
		val service = FakeBilibiliService()
		service.articleResult = article.copy(body = "   ")
		val tool = BilibiliArticleTool(service)

		val result = tool.execute(buildJsonObject { put("id", "cv34975835") })

		assertTrue(result.isError)
		assertTrue(result.content.contains("no readable text body"))
	}

	@Test
	fun `transcript names its source and reports AI subtitles`() = runBlocking {
		val service = FakeBilibiliService()
		service.transcriptResult = BilibiliTranscript(
			source = BilibiliTranscriptSource.SUBTITLE,
			text = "第一句\n第二句",
			language = "中文",
			aiGenerated = true,
			segments = 2,
			cues = listOf(
				BilibiliTranscriptCue(0, 2, "第一句"),
				BilibiliTranscriptCue(63, 65, "第二句"),
			),
		)
		val tool = BilibiliTranscriptTool(service)

		val result = tool.execute(buildJsonObject { put("bvid", "BV1das46aEKM") })

		assertFalse(result.isError)
		assertTrue("source missing: ${result.content}", "AI-generated subtitles" in result.content)
		assertTrue("language missing", "中文" in result.content)
		assertTrue("body missing", "第一句" in result.content)
		assertTrue("start time missing", "[1:03] 第二句" in result.content)
		assertEquals(BilibiliTranscriptPreference.TEXT, service.lastPrefer)
	}

	@Test
	fun `transcript passes window and language to the service`() = runBlocking {
		val service = FakeBilibiliService()
		service.transcriptResult = BilibiliTranscript(
			source = BilibiliTranscriptSource.SUBTITLE,
			text = "一",
			segments = 1,
			cues = listOf(BilibiliTranscriptCue(60, 62, "一")),
		)
		val tool = BilibiliTranscriptTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("bvid", "BV1das46aEKM")
				put("language", "en")
				put("from", 60)
				put("to", 120)
			},
		)

		assertFalse(result.isError)
		assertEquals("en", service.lastLanguage)
		assertEquals(60L, service.lastFrom)
		assertEquals(120L, service.lastTo)
		assertTrue("window note missing", "window 1:00–2:00" in result.content)
	}

	@Test
	fun `transcript truncation names the timestamp to continue from`() = runBlocking {
		val service = FakeBilibiliService()
		val cues = (0 until 500).map { index ->
			BilibiliTranscriptCue(
				index * 10L,
				index * 10L + 9,
				"这句话有点长，用来撑满字符上限".repeat(3) + index,
			)
		}
		service.transcriptResult = BilibiliTranscript(
			source = BilibiliTranscriptSource.SUBTITLE,
			text = "",
			segments = cues.size,
			cues = cues,
		)
		val tool = BilibiliTranscriptTool(service)

		val result = tool.execute(buildJsonObject { put("bvid", "BV1das46aEKM") })

		assertFalse(result.isError)
		assertTrue("truncation marker missing", "[truncated at 20000 characters" in result.content)
		assertTrue("continuation hint missing", "Call again with from=" in result.content)
	}

	@Test
	fun `an empty subtitle window is explained, not treated as no subtitles`() = runBlocking {
		val service = FakeBilibiliService()
		service.transcriptResult = BilibiliTranscript(
			source = BilibiliTranscriptSource.SUBTITLE,
			text = "",
			segments = 0,
			cues = emptyList(),
		)
		val tool = BilibiliTranscriptTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("bvid", "BV1das46aEKM")
				put("from", 100)
			},
		)

		assertFalse(result.isError)
		assertTrue("empty-window note missing", "No subtitle lines fall in that window" in result.content)
	}

	@Test
	fun `transcript passes the summary preference through`() = runBlocking {
		val service = FakeBilibiliService()
		service.transcriptResult = BilibiliTranscript(
			source = BilibiliTranscriptSource.AI_SUMMARY,
			text = "Bilibili's summary text",
			segments = 3,
		)
		val tool = BilibiliTranscriptTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("bvid", "BV1das46aEKM")
				put("prefer", "summary")
				put("page", 2)
			},
		)

		assertFalse(result.isError)
		assertEquals(BilibiliTranscriptPreference.SUMMARY, service.lastPrefer)
		assertEquals(2, service.lastPage)
		assertTrue("AI summary not labelled", "Bilibili AI summary" in result.content)
		assertTrue("part not shown", "part 2" in result.content)
	}

	@Test
	fun `no transcript is an explicit answer not an empty success`() = runBlocking {
		val service = FakeBilibiliService()
		service.transcriptResult = null
		val tool = BilibiliTranscriptTool(service)

		val result = tool.execute(buildJsonObject { put("bvid", "BV1das46aEKM") })

		assertFalse(result.isError)
		assertTrue(result.content.contains("No subtitles and no Bilibili AI summary"))
	}

	@Test
	fun `comment pages after the first need the login`() = runBlocking {
		val service = FakeBilibiliService()
		service.loggedIn = false
		val tool = BilibiliCommentsTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("bvid", "BV1das46aEKM")
				put("page", 2)
			},
		)

		assertTrue(result.isError)
		assertTrue(result.content.contains("needs the optional Bilibili login"))
		assertEquals(0, service.commentCalls)
	}

	@Test
	fun `logged-in comment page two reaches the service`() = runBlocking {
		val service = FakeBilibiliService()
		service.loggedIn = true
		val tool = BilibiliCommentsTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("bvid", "BV1das46aEKM")
				put("page", 2)
			},
		)

		assertFalse(result.isError)
		assertEquals(2, service.lastCommentPage)
		assertTrue(result.content.contains("page 2"))
	}
}
