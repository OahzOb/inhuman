package com.inhuman.core.ai.bilibili

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The anonymous endpoints against the live service.
 *
 * ## Why this is skipped by default
 *
 * It needs a network, it depends on a third party's risk control, and repeated
 * anonymous calls are exactly what that system throttles. A test that fails
 * because Bilibili is having a bad day teaches people to ignore failures. The
 * parsing and formatting are covered by fixtures; what only a live run can say
 * is that the endpoint set, headers, signature and browsing id are right.
 *
 * Run it deliberately:
 *
 * ```
 * INHUMAN_BILI_LIVE=1 ./gradlew :core:ai:test --tests '*BilibiliLiveTest'
 * ```
 *
 * The calls are deliberately one test with pauses: an anonymous caller that
 * fires seven requests in two seconds is the shape of a scraper, and the point
 * of the run is the normal path, not the throttle.
 *
 * A `-509`/`-412` during a deliberate run means the probe itself was throttled
 * and says nothing about the request shape. The tests do not retry: a retry
 * loop is the behaviour they exist to measure, and the next run is the retry.
 */
class BilibiliLiveTest {

	private val enabled: Boolean = System.getenv("INHUMAN_BILI_LIVE") == "1"

	@Test
	fun `anonymous search video comments and article`() = runBlocking {
		assumeTrue("INHUMAN_BILI_LIVE not set; skipping the live call", enabled)
		// A fresh anonymous caller gets a short grace period; a suite that
		// starts a dozen requests immediately reads as automation.
		delay(8_000)

		val client = BilibiliClient()

		val hits = client.search(
			keyword = "原神",
			kind = BilibiliSearchKind.VIDEO,
			order = "totalrank",
			page = 1,
			limit = 3,
		)
		assertTrue("the live search returned nothing; the request shape may be wrong", hits.isNotEmpty())
		val video = hits.first() as BilibiliSearchHit.Video
		assertTrue("search returned an empty BV id", video.bvid.isNotBlank())

		val info = client.video(video.bvid)
		assertTrue("video view returned no title", info.title.isNotBlank())
		assertTrue("video view returned no uploader", info.ownerName.isNotBlank())

		val comments = client.comments(video.bvid, BilibiliCommentSort.HOT, 5)
		println("=== live: ${video.bvid} ${info.title.take(40)}; ${comments.comments.size} comments ===")

		// A deliberate pause before the next pair: the point of the live run is
		// to prove the normal path, not to find the throttle.
		delay(4_000)

		val articles = client.search(
			keyword = "原神",
			kind = BilibiliSearchKind.ARTICLE,
			order = "totalrank",
			page = 1,
			limit = 3,
		)
		assertTrue("the live article search returned nothing", articles.isNotEmpty())
		val hit = articles.first() as BilibiliSearchHit.Article
		val read = client.article(hit.cvId)
		assertTrue("article returned no title", read.title.isNotBlank())
		assertTrue("article returned no body", read.body.isNotBlank())

		println("=== live article cv${read.cvId}: ${read.title.take(40)} (${read.body.length} chars) ===")
	}

	/**
	 * The second surface: uploader page, discovery lists, a show, a live room
	 * and the danmaku wire format.
	 *
	 * The ids are a stable uploader, a stable show and a stable room rather than
	 * values discovered at run time, so a failure means "this endpoint changed",
	 * not "someone deleted a video". The danmaku call is bounded to one window
	 * because a whole-video sample is three more requests.
	 */
	@Test
	fun `anonymous uploader trending show live and danmaku`() = runBlocking {
		assumeTrue("INHUMAN_BILI_LIVE not set; skipping the live call", enabled)
		delay(8_000)

		val client = BilibiliClient()

		val up = client.uploader(517_327_498L, page = 1, order = BilibiliUpOrder.LATEST)
		assertTrue("uploader page returned no name", up.name.isNotBlank())
		assertTrue("uploader page returned no videos", up.recentVideos.isNotEmpty())
		assertTrue("uploader page lost the follower count", up.fans > 0)

		val rankings = client.trending(BilibiliTrendingKind.RANKING, limit = 5)
		assertTrue("ranking returned nothing", rankings.size == 5)

		val show = client.bangumi(seasonId = 43_164L, epId = 0L)
		assertTrue("show returned no title", show.title.isNotBlank())
		assertTrue("show returned no episodes", show.episodes.isNotEmpty())

		val room = client.live(roomId = 22_159_237L, mid = 0L)
		assertTrue("live room returned no title", room.title.isNotBlank())

		val danmaku = client.danmaku("BV1jiWi6dEQg", page = 1, fromSeconds = 0, toSeconds = 360)
		assertTrue("danmaku reported no windows", danmaku.segmentCount >= 1)

		println(
			"=== live second surface: up ${up.name} (${up.recentVideos.size} videos), " +
				"${rankings.size} ranked, show ${show.title}, room ${room.roomId}, " +
				"danmaku windows ${danmaku.segmentCount} " +
				"(sampled ${danmaku.sampled.sumOf { it.entries }}) ===",
		)
	}
}
