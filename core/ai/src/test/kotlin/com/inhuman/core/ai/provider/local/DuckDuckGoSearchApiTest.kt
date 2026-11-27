package com.inhuman.core.ai.provider.local

import com.inhuman.core.ai.testing.LocalHttpServer
import com.inhuman.core.ai.tools.GuardedHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDuckGoSearchApiTest {

	private fun provider(server: LocalHttpServer): DuckDuckGoSearchApi =
		DuckDuckGoSearchApi(
			http = GuardedHttp.forTesting(),
			baseUrl = "${server.baseUrl}/search",
			requireHttps = false,
		)

	@Test
	fun `result page is parsed with decoded links and snippets`() {
		val html = """
			<html><body><div id="links" class="results">
			<div class="result results_links">
			<h2 class="result__title"><a class="result__a"
			href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fone&amp;rut=x">First &amp; One</a></h2>
			<a class="result__snippet" href="x">First snippet here.</a>
			</div>
			<div class="result results_links">
			<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Ftwo">Second</a>
			<div class="result__snippet">Second snippet.</div>
			</div>
			<div class="result results_links">
			<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fone">Duplicate</a>
			</div>
			</div></body></html>
		""".trimIndent()

		val results = DuckDuckGoSearchApi.parse(html, "https://html.duckduckgo.com/html/", 10)

		assertEquals(2, results.size)
		assertEquals("First & One", results[0].title)
		assertEquals("https://example.com/one", results[0].url)
		assertEquals("First snippet here.", results[0].snippet)
		assertEquals("https://example.com/two", results[1].url)
	}

	@Test
	fun `max results is a ceiling`() {
		val html = (1..5).joinToString("") { i ->
			"""<div class="result"><a class="result__a" href="https://example.com/$i">R$i</a></div>"""
		}
		val results = DuckDuckGoSearchApi.parse(html, "https://example.com/", 2)
		assertEquals(2, results.size)
	}

	@Test
	fun `a bot check page is an error not an empty result`() {
		val failure = runCatching {
			DuckDuckGoSearchApi.parse(
				"<html><body><p>Unfortunately, bots use DuckDuckGo too.</p></body></html>",
				"https://html.duckduckgo.com/html/",
				5,
			)
		}.exceptionOrNull()

		assertTrue(failure is java.io.IOException)
	}

	@Test
	fun `an empty page is an empty result list`() {
		assertTrue(
			DuckDuckGoSearchApi.parse(
				"<html><body><p>Nothing found.</p></body></html>",
				"https://html.duckduckgo.com/html/",
				5,
			).isEmpty(),
		)
	}

	@Test
	fun `the provider fetches and parses end to end`() = runBlocking {
		val server = LocalHttpServer(
			mapOf(
				"/search" to LocalHttpServer.Route(
					body = """<div class="result"><a class="result__a" """ +
						"""href="https://example.com/item">Item</a>""" +
						"""<div class="result__snippet">Snip.</div></div>""",
				),
			),
		)
		try {
			val results = provider(server).search("kotlin", 5)
			assertEquals(1, results.size)
			assertEquals("https://example.com/item", results[0].url)
		} finally {
			server.close()
		}
	}

	@Test
	fun `an http error is reported with its status`() = runBlocking {
		val server = LocalHttpServer(
			mapOf("/search" to LocalHttpServer.Route(status = 503, body = "busy")),
		)
		try {
			val failure = runCatching { provider(server).search("x", 5) }.exceptionOrNull()
			assertTrue(
				"expected HTTP 503 failure, got $failure",
				failure is java.io.IOException && "503" in failure.message.orEmpty(),
			)
		} finally {
			server.close()
		}
	}
}
