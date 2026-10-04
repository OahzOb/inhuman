package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.tools.SearchProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The search provider against the live service.
 *
 * ## Why this is skipped by default
 *
 * It costs a model turn and real money every time it runs, and it depends on a
 * network and a credential that a build machine has neither of. A test that
 * fails when the wifi is down teaches people to ignore failures.
 *
 * Run it deliberately:
 *
 * ```
 * DEEPSEEK_LIVE_KEY=sk-... ./gradlew :core:ai:testDebugUnitTest \
 *     --tests '*DeepSeekSearchProviderLiveTest'
 * ```
 *
 * What it is for: the parsing is covered by fixtures, so what remains untested
 * is whether the *request* is right -- the endpoint, the tool declaration, the
 * headers. Those can only be checked by asking the real service, and getting
 * them wrong is silent: a malformed tool declaration comes back as a model that
 * simply chose not to search, which the parser correctly reports as no results.
 */
class DeepSeekSearchProviderLiveTest {

	private val key: String? = System.getenv("DEEPSEEK_LIVE_KEY")

	private fun provider(): SearchProvider {
		assumeTrue("DEEPSEEK_LIVE_KEY not set; skipping the live call", !key.isNullOrBlank())
		return DeepSeekSearchProvider(apiKey = key!!)
	}

	@Test
	fun `a live search returns real sources with clean urls`() = runBlocking {
		val results = provider().search("Kotlin latest release version", maxResults = 5)

		// The request shape was accepted and the model actually searched.
		assertTrue(
			"the live call returned no sources; the tool declaration may be wrong",
			results.isNotEmpty(),
		)

		results.forEach { r ->
			assertTrue("every result needs a title", r.title.isNotBlank())
			assertTrue("every result needs an absolute url", r.url.startsWith("http"))
			// The cleaning is the part that is silent when wrong: a URL still
			// carrying campaign parameters looks fine and leaks the query.
			assertTrue(
				"tracking parameter survived: ${r.url}",
				!r.url.contains("utm_") && !r.url.contains("via="),
			)
		}

		assertTrue("maxResults was not honoured", results.size <= 5)

		println("=== ${results.size} sources ===")
		results.forEachIndexed { i, r ->
			println("${i + 1}. ${r.title}")
			println("   ${r.url}")
			r.snippet?.let { println("   ${it.take(120)}") }
		}
	}
}
