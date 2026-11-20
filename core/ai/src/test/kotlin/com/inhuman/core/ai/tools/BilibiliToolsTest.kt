package com.inhuman.core.ai.tools

import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliArticle
import com.inhuman.core.ai.bilibili.BilibiliComment
import com.inhuman.core.ai.bilibili.BilibiliCommentReply
import com.inhuman.core.ai.bilibili.BilibiliCommentSort
import com.inhuman.core.ai.bilibili.BilibiliComments
import com.inhuman.core.ai.bilibili.BilibiliReadService
import com.inhuman.core.ai.bilibili.BilibiliSearchHit
import com.inhuman.core.ai.bilibili.BilibiliSearchKind
import com.inhuman.core.ai.bilibili.BilibiliStats
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
		val service = FakeService()
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
	fun `search rejects an unknown kind before reaching the service`() = runBlocking {
		val service = FakeService()
		val tool = BilibiliSearchTool(service)

		val result = tool.execute(
			buildJsonObject {
				put("keyword", "原神")
				put("kind", "user")
			},
		)

		assertTrue(result.isError)
		assertEquals(0, service.searchCalls)
	}

	@Test
	fun `video rejects a URL and never hands it to the client`() = runBlocking {
		val service = FakeService()
		val tool = BilibiliVideoTool(service)

		val result = tool.execute(
			buildJsonObject { put("bvid", "https://www.bilibili.com/video/BV1das46aEKM") },
		)

		assertTrue(result.isError)
		assertEquals(0, service.videoCalls)
	}

	@Test
	fun `video formats stats and description`() = runBlocking {
		val service = FakeService()
		service.videoResult = videoInfo
		val tool = BilibiliVideoTool(service)

		val result = tool.execute(buildJsonObject { put("bvid", "bv1das46aEKM") })

		assertFalse(result.isError)
		assertTrue(result.content.startsWith("BV1das46aEKM"))
		assertTrue("stats missing", "67,972 views" in result.content)
		assertTrue("description missing", "视频简介" in result.content)
		assertEquals("lowercase bv should be normalised", "BV1das46aEKM", service.lastBvid)
	}

	@Test
	fun `empty comments are an answer and not an error`() = runBlocking {
		val service = FakeService()
		val tool = BilibiliCommentsTool(service)

		val result = tool.execute(buildJsonObject { put("bvid", "BV1das46aEKM") })

		assertFalse(result.isError)
		assertTrue(result.content.contains("No comments"))
	}

	@Test
	fun `comments format pinned entries and nested replies`() = runBlocking {
		val service = FakeService()
		service.commentsResult = BilibiliComments(
			pinned = listOf(
				BilibiliComment(
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
		assertEquals(BilibiliCommentSort.NEW, service.lastSort)
	}

	@Test
	fun `article truncation is announced`() = runBlocking {
		val service = FakeService()
		service.articleResult = article.copy(body = "字".repeat(BilibiliArticleTool.MAX_BODY_CHARS + 10))
		val tool = BilibiliArticleTool(service)

		val result = tool.execute(buildJsonObject { put("id", "cv34975835") })

		assertFalse(result.isError)
		assertTrue("no truncation marker", "[truncated at 20000 characters" in result.content)
		assertEquals(34_975_835L, service.lastCvId)
	}

	@Test
	fun `article accepts plain digits and refuses a URL`() = runBlocking {
		val service = FakeService()
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
		val service = FakeService()
		service.failure = BilibiliApiException(-352, "风控校验失败")
		val tool = BilibiliSearchTool(service)

		val result = tool.execute(buildJsonObject { put("keyword", "原神") })

		assertTrue(result.isError)
		assertTrue("advice missing: ${result.content}", "risk control" in result.content)
	}

	@Test
	fun `an empty article body is an error not an empty success`() = runBlocking {
		val service = FakeService()
		service.articleResult = article.copy(body = "   ")
		val tool = BilibiliArticleTool(service)

		val result = tool.execute(buildJsonObject { put("id", "cv34975835") })

		assertTrue(result.isError)
		assertTrue(result.content.contains("no readable text body"))
	}

	private class FakeService : BilibiliReadService {
		var searchResult: List<BilibiliSearchHit> = emptyList()
		var videoResult: BilibiliVideoInfo? = null
		var commentsResult: BilibiliComments = BilibiliComments(emptyList(), emptyList())
		var articleResult: BilibiliArticle? = null
		var failure: Exception? = null
		var searchCalls = 0
		var videoCalls = 0
		var lastLimit = 0
		var lastPage = 0
		var lastBvid = ""
		var lastSort: BilibiliCommentSort? = null
		var lastCvId = 0L

		override suspend fun search(
			keyword: String,
			kind: BilibiliSearchKind,
			order: String,
			page: Int,
			limit: Int,
		): List<BilibiliSearchHit> {
			failure?.let { throw it }
			searchCalls++
			lastPage = page
			lastLimit = limit
			return searchResult
		}

		override suspend fun video(bvid: String): BilibiliVideoInfo {
			failure?.let { throw it }
			videoCalls++
			lastBvid = bvid
			return videoResult ?: error("video result not configured")
		}

		override suspend fun comments(
			bvid: String,
			sort: BilibiliCommentSort,
			limit: Int,
		): BilibiliComments {
			failure?.let { throw it }
			lastSort = sort
			return commentsResult
		}

		override suspend fun article(cvId: Long): BilibiliArticle {
			failure?.let { throw it }
			lastCvId = cvId
			return articleResult ?: error("article result not configured")
		}
	}
}
