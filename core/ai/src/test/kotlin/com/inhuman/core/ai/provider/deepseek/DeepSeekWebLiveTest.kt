package com.inhuman.core.ai.provider.deepseek

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Search and Fetch against the live service.
 *
 * ## Why this is skipped by default
 *
 * It spends money on two paid APIs every time it runs and needs a network and a
 * credential a build machine has neither of. A test that fails when the wifi is
 * down teaches people to ignore failures.
 *
 * Run it deliberately:
 *
 * ```
 * DEEPSEEK_LIVE_KEY=sk-... ./gradlew :core:ai:testDebugUnitTest \
 *     --tests '*DeepSeekWebLiveTest'
 * ```
 *
 * What it covers that fixtures cannot: whether the endpoint, headers and body
 * are right. The parsing is already covered without a network.
 */
class DeepSeekWebLiveTest {

	private val key: String? = System.getenv("DEEPSEEK_LIVE_KEY")

	@Test
	fun `a live search returns sources and one of them fetches`() = runBlocking {
		assumeTrue("DEEPSEEK_LIVE_KEY not set; skipping the live call", !key.isNullOrBlank())

		val results = DeepSeekSearchApi(apiKey = { key })
			.search("Kotlin latest release version", maxResults = 5)

		assertTrue(
			"the live search returned nothing; the request shape may be wrong",
			results.isNotEmpty(),
		)
		results.forEach { result ->
			assertTrue("every result needs a title", result.title.isNotBlank())
			assertTrue("every result needs an absolute url", result.url.startsWith("http"))
			assertTrue(
				"tracking parameter survived: ${result.url}",
				!result.url.contains("utm_") && !result.url.contains("via="),
			)
		}

		val page = DeepSeekFetchApi(apiKey = { key }).fetch(results.first().url)
		assertTrue("the fetch returned no content", page.content.isNotBlank())

		println("=== ${results.size} sources; fetched ${results.first().url} ===")
		println("${page.content.take(160)}…")
	}
}
