package com.inhuman.core.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search tool, against a provider that cannot fail unpredictably.
 *
 * No network: the tool's job is to turn results into something a model can read,
 * and to fail in a way the model can act on. Both are testable with a fake, and
 * testing them against a live service would make the suite depend on someone
 * else's uptime.
 */
class WebSearchToolTest {

	private class Fake(
		private val results: List<SearchResult> = emptyList(),
		private val failure: Exception? = null,
	) : SearchProvider {
		override val name = "fake"
		var lastQuery: String? = null
		var lastMax: Int? = null

		override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
			lastQuery = query
			lastMax = maxResults
			failure?.let { throw it }
			return results
		}
	}

	private fun results(n: Int) = (1..n).map {
		SearchResult(
			title = "Result $it",
			url = "https://example.com/$it",
			snippet = "Snippet number $it",
		)
	}

	private fun call(
		provider: SearchProvider,
		query: String? = null,
		maxResults: Int? = null,
	): com.inhuman.core.ai.ToolResult = runBlocking {
		val args = buildJsonObject {
			query?.let { put("query", it) }
			maxResults?.let { put("max_results", it) }
		}
		WebSearchTool(provider).execute(args)
	}

	// --- the happy path -------------------------------------------------------

	@Test
	fun `results are numbered with their links`() {
		val out = call(Fake(results(3)), query = "kotlin coroutines").content

		assertTrue(out, out.contains("Result 1"))
		assertTrue(out, out.contains("https://example.com/1"))
		assertTrue(out, out.contains("Snippet number 1"))
		// Numbered so the model can refer to "result 2" without quoting a URL.
		assertTrue(out, out.contains("1. "))
		assertTrue(out, out.contains("3. "))
	}

	@Test
	fun `the query is echoed so the transcript is readable`() {
		val out = call(Fake(results(1)), query = "kotlin coroutines").content
		assertTrue(out, out.contains("kotlin coroutines"))
	}

	@Test
	fun `the requested count is passed through`() {
		val provider = Fake(results(2))
		call(provider, query = "x", maxResults = 7)
		assertEquals(7, provider.lastMax)
	}

	@Test
	fun `an unstated count uses the default`() {
		val provider = Fake(results(1))
		call(provider, query = "x")
		assertEquals(WebSearchTool.DEFAULT_RESULTS, provider.lastMax)
	}

	@Test
	fun `an oversized count is clamped rather than refused`() {
		val provider = Fake(results(1))
		// Clamped, not rejected: a model asking for 50 wants more results, and
		// spending a turn to explain the cap buys nothing.
		call(provider, query = "x", maxResults = 50)
		assertEquals(10, provider.lastMax)
	}

	// --- the parts that are easy to get wrong ---------------------------------

	@Test
	fun `fewer results than requested is said out loud`() {
		// Otherwise the model reads three results as "there are only three
		// things on the internet about this" rather than "the search was thin".
		val out = call(Fake(results(2)), query = "x", maxResults = 8).content

		assertTrue(out, out.contains("fewer than the 8 requested"))
	}

	@Test
	fun `an exact count does not claim a shortfall`() {
		val out = call(Fake(results(5)), query = "x", maxResults = 5).content
		assertFalse(out, out.contains("fewer than"))
	}

	@Test
	fun `no results is an answer, not an error`() {
		val result = call(Fake(emptyList()), query = "zxqwv")

		// Reported as a successful search that found nothing. If this came back
		// as an error the model would retry the same query, because an error
		// means "try again" and an empty result means "try something else".
		assertFalse(result.isError)
		assertTrue(result.content, result.content.contains("No results"))
		assertTrue(result.content, result.content.contains("zxqwv"))
	}

	@Test
	fun `a provider failure is reported as an error`() {
		val result = call(Fake(failure = java.io.IOException("connection reset")), query = "x")

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("connection reset"))
	}

	@Test
	fun `a missing query is refused rather than searched`() {
		val provider = Fake(results(1))
		val result = call(provider, maxResults = 3)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("query"))
		assertEquals("a provider must not be called without a query", null, provider.lastQuery)
	}

	@Test
	fun `a blank query is refused too`() {
		val provider = Fake(results(1))
		val result = call(provider, query = "   ")

		assertTrue(result.isError)
		assertEquals(null, provider.lastQuery)
	}

	@Test
	fun `an absent snippet does not leave a blank line claiming one`() {
		val provider = Fake(listOf(SearchResult("Title", "https://example.com", snippet = null)))
		val out = call(provider, query = "x").content

		assertTrue(out, out.contains("Title"))
		assertTrue(out, out.contains("https://example.com"))
		// No dangling "   " line where the snippet would have been.
		assertFalse(out, out.lines().any { it.trim() == "" && it != "" && it.length > 1 })
	}

	@Test
	fun `a multi-line snippet is flattened`() {
		val provider = Fake(
			listOf(SearchResult("T", "https://e.com", snippet = "line one\n\n   line two")),
		)
		val out = call(provider, query = "x").content

		// A newline inside a snippet would break the one-result-per-block shape
		// the model is reading.
		assertTrue(out, out.contains("line one line two"))
	}

	@Test
	fun `a publication date is shown when the provider gives one`() {
		val provider = Fake(
			listOf(SearchResult("T", "https://e.com", snippet = "s", publishedAt = "2026-01-02")),
		)
		val out = call(provider, query = "x").content

		assertTrue(out, out.contains("2026-01-02"))
	}

	// --- classification -------------------------------------------------------

	@Test
	fun `searching does not block regeneration`() {
		// It reads a remote service and changes nothing: replaying costs another
		// request, not another side effect.
		assertFalse(WebSearchTool(Fake()).mutates)
	}

	@Test
	fun `the schema requires only a query`() {
		val required = WebSearchTool(Fake()).parameters["required"]
		assertEquals(listOf("query"), required)
	}
}
