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
 */
class BilibiliLiveTest {

	private val enabled: Boolean = System.getenv("INHUMAN_BILI_LIVE") == "1"

	@Test
	fun `anonymous search video comments and article`() = runBlocking {
		assumeTrue("INHUMAN_BILI_LIVE not set; skipping the live call", enabled)

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
}
