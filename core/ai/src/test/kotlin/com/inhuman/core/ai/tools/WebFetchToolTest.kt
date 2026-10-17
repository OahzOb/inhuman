package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fetch tool against a fake provider.
 *
 * What is worth testing here is the text the model receives: the title, the URL,
 * the truncation marker, and errors it can act on. The HTTP call itself is the
 * provider's job and is covered by its own validation tests.
 */
class WebFetchToolTest {

	private class Fake(
		private val page: FetchedPage? = null,
		private val failure: Exception? = null,
	) : FetchProvider {
		override val name = "fake"
		var lastUrl: String? = null

		override suspend fun fetch(url: String): FetchedPage {
			lastUrl = url
			failure?.let { throw it }
			return page ?: FetchedPage("", "")
		}
	}

	private fun call(provider: FetchProvider, url: String? = null): ToolResult = runBlocking {
		val args = buildJsonObject { url?.let { put("url", it) } }
		WebFetchTool(provider).execute(args)
	}

	@Test
	fun `title url and content are put in that order`() {
		val out = call(Fake(FetchedPage("Example", "# Body\n\nText.")), "https://example.com/x")

		assertFalse(out.isError)
		assertTrue(out.content, out.content.startsWith("# Example"))
		assertTrue(out.content, out.content.contains("https://example.com/x"))
		assertTrue(out.content, out.content.contains("# Body"))
	}

	@Test
	fun `a missing title does not produce an empty heading`() {
		val out = call(Fake(FetchedPage("", "Body.")), "https://example.com/x")

		assertFalse(out.content, out.content.contains("# \n"))
		assertTrue(out.content, out.content.contains("Body."))
	}

	@Test
	fun `the url is passed through unchanged`() {
		val provider = Fake(FetchedPage("T", "C"))

		call(provider, "https://example.com/a?b=1")

		assertEquals("https://example.com/a?b=1", provider.lastUrl)
	}

	@Test
	fun `a missing url is an error the model can fix`() {
		val out = call(Fake(FetchedPage("T", "C")))

		assertTrue(out.isError)
		assertTrue(out.content, out.content.contains("'url'"))
	}

	@Test
	fun `a provider failure becomes a readable tool error`() {
		val out = call(Fake(failure = IllegalStateException("blocked")), "https://example.com/x")

		assertTrue(out.isError)
		assertTrue(out.content, out.content.contains("fetch failed"))
		assertTrue(out.content, out.content.contains("blocked"))
	}

	@Test
	fun `blank content is an error rather than a success with nothing in it`() {
		val out = call(Fake(FetchedPage("T", "   ")), "https://example.com/x")

		assertTrue(out.isError)
		assertTrue(out.content, out.content.contains("no readable content"))
	}

	@Test
	fun `long content is truncated and the truncation is announced`() {
		val content = "x".repeat(WebFetchTool.MAX_CHARS + 50)
		val out = call(Fake(FetchedPage("T", content)), "https://example.com/x")

		assertFalse(out.isError)
		assertTrue(
			out.content,
			out.content.contains("[truncated at ${WebFetchTool.MAX_CHARS} characters]"),
		)
		// The marker is the last thing, so a model reading the end sees it.
		assertTrue(out.content.trimEnd().endsWith("characters]"))
	}

	@Test
	fun `short content carries no truncation marker`() {
		val out = call(Fake(FetchedPage("T", "short")), "https://example.com/x")

		assertFalse(out.content, out.content.contains("[truncated"))
	}
}
