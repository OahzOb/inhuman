package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local variants of the web tools: different names and descriptions, and
 * `render` only where the provider can actually render.
 *
 * The rendering mechanics live in `LocalFetchApi` and are tested against a
 * local fixture server there; this file pins the tool contract the model sees.
 */
class LocalWebToolsTest {

	private class StaticProvider : FetchProvider {
		override val name = "static"
		var lastUrl: String? = null

		override suspend fun fetch(url: String): FetchedPage {
			lastUrl = url
			return FetchedPage("Static", "static body")
		}
	}

	private class RenderingProvider : RenderingFetchProvider {
		override val name = "rendering"
		var lastRenderedUrl: String? = null

		override suspend fun fetch(url: String): FetchedPage =
			FetchedPage("Static", "static body")

		override suspend fun fetchRendered(url: String): FetchedPage {
			lastRenderedUrl = url
			return FetchedPage("Rendered", "rendered body")
		}
	}

	private fun call(
		tool: WebFetchTool,
		url: String = "https://example.com/x",
		render: Boolean? = null,
	): ToolResult = runBlocking {
		val args: JsonObject = buildJsonObject {
			put("url", url)
			render?.let { put("render", it) }
		}
		tool.execute(args)
	}

	@Test
	fun `the local fetch tool has its own name and schema`() {
		val tool = WebFetchTool(
			provider = RenderingProvider(),
			name = "web_fetch_local",
			description = WebFetchTool.LOCAL_DESCRIPTION,
		)

		assertEquals("web_fetch_local", tool.name)
		assertTrue(tool.parameters.toString(), "render" in tool.parameters.toString())
		assertEquals(WebFetchTool.LOCAL_DESCRIPTION, tool.description)
	}

	@Test
	fun `a static provider gets no render argument`() {
		val tool = WebFetchTool(provider = StaticProvider(), name = "web_fetch_local")

		assertFalse(tool.parameters.toString(), "render" in tool.parameters.toString())
	}

	@Test
	fun `render true dispatches to the rendering provider`() {
		val provider = RenderingProvider()
		val tool = WebFetchTool(provider = provider, name = "web_fetch_local")

		val out = call(tool, render = true)

		assertFalse(out.isError)
		assertTrue(out.content, "rendered body" in out.content)
		assertEquals("https://example.com/x", provider.lastRenderedUrl)
	}

	@Test
	fun `render false or absent stays on the static path`() {
		val provider = RenderingProvider()
		val tool = WebFetchTool(provider = provider, name = "web_fetch_local")

		val out = call(tool, render = false)

		assertFalse(out.isError)
		assertTrue(out.content, "static body" in out.content)
		assertEquals(null, provider.lastRenderedUrl)
	}

	@Test
	fun `the local search tool has its own name and description`() {
		val provider = object : SearchProvider {
			override val name = "fake"
			override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
				listOf(SearchResult("T", "https://example.com/1", "S"))
		}
		val tool = WebSearchTool(
			provider = provider,
			name = "web_search_local",
			description = WebSearchTool.LOCAL_DESCRIPTION,
		)

		assertEquals("web_search_local", tool.name)
		assertEquals(WebSearchTool.LOCAL_DESCRIPTION, tool.description)
	}

	@Test
	fun `the local descriptions state the per-turn budget`() {
		assertTrue(WebFetchTool.LOCAL_DESCRIPTION, "per-turn budget" in WebFetchTool.LOCAL_DESCRIPTION)
		assertTrue(WebSearchTool.LOCAL_DESCRIPTION, "per-turn budget" in WebSearchTool.LOCAL_DESCRIPTION)
		assertTrue(
			WebFetchTool.LOCAL_DESCRIPTION,
			"${LocalWebBudget.MAX_PER_TURN}" in WebFetchTool.LOCAL_DESCRIPTION,
		)
	}

	private class CountingProvider : FetchProvider {
		override val name = "counting"
		var calls = 0

		override suspend fun fetch(url: String): FetchedPage {
			calls++
			return FetchedPage("Counting", "counting body")
		}
	}

	private class CountingSearch : SearchProvider {
		override val name = "counting"
		var calls = 0

		override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
			calls++
			return listOf(SearchResult("T", "https://example.com/1", "S"))
		}
	}

	@Test
	fun `one budget spans both local tools`() {
		val budget = LocalWebBudget(maxRequests = 2, spacingMillis = 0)
		val fetch = CountingProvider()
		val search = CountingSearch()
		val fetchTool = WebFetchTool(
			provider = fetch,
			name = "web_fetch_local",
			description = WebFetchTool.LOCAL_DESCRIPTION,
			budget = budget,
		)
		val searchTool = WebSearchTool(
			provider = search,
			name = "web_search_local",
			description = WebSearchTool.LOCAL_DESCRIPTION,
			budget = budget,
		)

		assertFalse(call(fetchTool).isError)
		val searched = runBlocking {
			searchTool.execute(buildJsonObject { put("query", "kotlin") })
		}
		assertFalse(searched.content, searched.isError)

		// The third request is refused, with a result the model can act on.
		val refused = call(fetchTool)
		assertTrue(refused.isError)
		assertTrue(refused.content, "used up" in refused.content)
		assertEquals(1, fetch.calls)
		assertEquals(1, search.calls)
		assertEquals(2, budget.used)
	}

	@Test
	fun `a malformed call spends no budget`() {
		val budget = LocalWebBudget(spacingMillis = 0)
		val tool = WebFetchTool(
			provider = CountingProvider(),
			name = "web_fetch_local",
			budget = budget,
		)

		val out = runBlocking { tool.execute(buildJsonObject { }) }

		assertTrue(out.isError)
		assertEquals(0, budget.used)
	}

	@Test
	fun `rendered fetches count against the render cap while static ones do not`() {
		val budget = LocalWebBudget(maxRendered = 1, spacingMillis = 0)
		val tool = WebFetchTool(
			provider = RenderingProvider(),
			name = "web_fetch_local",
			description = WebFetchTool.LOCAL_DESCRIPTION,
			budget = budget,
		)

		assertFalse(call(tool, render = true).isError)

		val refused = call(tool, render = true)
		assertTrue(refused.isError)
		assertTrue(refused.content, "rendered" in refused.content)

		assertFalse(call(tool, render = false).isError)
		assertEquals(1, budget.rendered)
		assertEquals(2, budget.used)
	}
}
