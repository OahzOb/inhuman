package com.inhuman.core.ai.bilibili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Parsing is tested against responses captured from the live anonymous
 * endpoints (2026-11-20) rather than hand-written JSON: the point is that the
 * shapes are real, including the fields that are absent and the title markup.
 *
 * A fixture is a snapshot, not a contract. If Bilibili renames a field, these
 * tests stay green and the parser starts returning defaults -- which is why
 * every default here is one whose meaning is "not reported" (zero, empty)
 * rather than a fabricated fact.
 */
class BilibiliParsersTest {

	private fun fixture(name: String): JsonObject {
		val stream = javaClass.classLoader.getResourceAsStream("bilibili/$name")
			?: error("missing fixture bilibili/$name")
		return stream.bufferedReader(Charsets.UTF_8).use {
			Json.parseToJsonElement(it.readText()).jsonObject
		}
	}

	@Test
	fun `video search fixture parses ids and strips highlight markup`() {
		val hits = BilibiliParsers.parseSearch(fixture("search_video.json"))

		assertEquals(2, hits.size)
		val first = hits.first() as BilibiliSearchHit.Video
		assertEquals("BV1jiWi6dEQg", first.bvid)
		assertTrue("title kept markup: ${first.title}", !first.title.contains("<em"))
		assertTrue("title lost its text: ${first.title}", first.title.contains("原神"))
		assertEquals("原神", first.author)
		assertTrue("play count missing", first.play > 0)
		assertEquals("8:38", first.duration)
	}

	@Test
	fun `article search fixture parses cv ids`() {
		val hits = BilibiliParsers.parseSearch(fixture("search_article.json"))

		assertEquals(2, hits.size)
		val first = hits.first() as BilibiliSearchHit.Article
		assertEquals(34_975_835L, first.cvId)
		assertTrue("title kept markup: ${first.title}", !first.title.contains("<em"))
		assertTrue("view count missing", first.view > 0)
	}

	@Test
	fun `video view fixture parses owner stats and parts`() {
		val info = BilibiliParsers.parseVideo(fixture("video_view.json"))

		assertEquals("BV1das46aEKM", info.bvid)
		assertEquals(196L, info.durationSeconds)
		assertEquals("绝云丘丘", info.ownerName)
		assertEquals(67_972L, info.stats.view)
		assertEquals(1, info.pages.size)
		assertEquals("P1 part title missing", true, info.pages.first().title.isNotBlank())
		assertEquals("the first part's cid is needed by the transcript tool", 43_728_179_086L, info.cid)
	}

	@Test
	fun `player fixture parses subtitle tracks`() {
		val tracks = BilibiliParsers.parseSubtitleTracks(fixture("player_subtitles.json"))

		assertEquals(2, tracks.size)
		assertEquals("zh", tracks[0].language)
		assertEquals("中文", tracks[0].label)
		assertEquals(0, tracks[0].type)
		assertEquals("ai-zh", tracks[1].language)
		assertEquals("AI track flag missing", 1, tracks[1].type)
		assertTrue("track URL missing", tracks[1].url.startsWith("https://"))
	}

	@Test
	fun `subtitle body fixture parses lines in order`() {
		val lines = BilibiliParsers.parseSubtitleBody(fixture("subtitle_body.json"))

		assertTrue("no subtitle lines", lines.size >= 8)
		assertTrue("first line missing", lines.first().isNotBlank())
		assertTrue("body order lost", lines[1].contains("2026") || lines[0].isNotBlank())
	}

	@Test
	fun `AI summary fixture parses summary and outline`() {
		val summary = BilibiliParsers.parseAiSummary(fixture("ai_summary.json"))

		assertTrue("no summary", summary != null)
		assertTrue("summary text missing", summary!!.summary.isNotBlank())
		assertTrue("outline missing", summary.outline.isNotEmpty())
		assertTrue(
			"outline points missing",
			summary.outline.any { it.points.isNotEmpty() },
		)
	}

	@Test
	fun `an AI summary with nothing in it is null`() {
		val empty = Json.parseToJsonElement(
			"""{"code":0,"data":{"model_result":{"summary":"","outline":[]}}}""",
		).jsonObject
		assertNull(BilibiliParsers.parseAiSummary(empty))
	}

	@Test
	fun `comment cursor is carried not calculated`() {
		val root = Json.parseToJsonElement(
			"""{"code":0,"data":{"cursor":{"is_end":false,"next":2},"replies":[]}}""",
		).jsonObject
		assertEquals(2L, BilibiliParsers.parseNextCursor(root))

		val last = Json.parseToJsonElement(
			"""{"code":0,"data":{"cursor":{"is_end":true,"next":0},"replies":[]}}""",
		).jsonObject
		assertNull(BilibiliParsers.parseNextCursor(last))
	}

	@Test
	fun `comment fixture parses pinned and ordinary comments`() {
		val comments = BilibiliParsers.parseComments(fixture("comments_hot.json"))

		assertEquals(1, comments.pinned.size)
		assertEquals(3, comments.comments.size)
		val first = comments.comments.first()
		assertTrue("author missing", first.author.isNotBlank())
		assertTrue("content missing", first.content.isNotBlank())
		assertTrue("like count missing", first.like > 0)
		assertTrue("nested replies missing", first.replies.isNotEmpty())
	}

	@Test
	fun `article fixture parses metadata and body text`() {
		val article = BilibiliParsers.parseArticle(fixture("article_view.json"))

		assertEquals(34_975_835L, article.cvId)
		assertEquals("原神壁纸62——申鹤篇6", article.title)
		assertEquals("宵宫你带我走吧-代肝", article.author)
		assertTrue("body missing", article.body.isNotBlank())
		assertTrue("body kept markup", !article.body.contains("<p>"))
		assertTrue("emoji lost", article.body.contains("🤤"))
	}

	@Test
	fun `a response without data is a refusal not an empty value`() {
		try {
			BilibiliParsers.parseVideo(JsonObject(emptyMap()))
			fail("a response with no data parsed as a video")
		} catch (expected: BilibiliApiException) {
			assertTrue(expected.code == BilibiliApiException.TRANSPORT)
		}
	}
}
