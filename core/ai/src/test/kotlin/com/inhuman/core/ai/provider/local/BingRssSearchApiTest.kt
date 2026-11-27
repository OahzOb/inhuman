package com.inhuman.core.ai.provider.local

import com.inhuman.core.ai.testing.LocalHttpServer
import com.inhuman.core.ai.tools.GuardedHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BingRssSearchApiTest {

	private val rss = """
		<?xml version="1.0" encoding="utf-8"?>
		<rss version="2.0"><channel>
		<title>必应：query</title>
		<item>
		<title>First result</title>
		<link>https://example.com/1</link>
		<description>&lt;b&gt;Bing&lt;/b&gt; snippet &amp;amp; more</description>
		<pubDate>Wed, 25 Nov 2026 19:05:00 GMT</pubDate>
		</item>
		<item>
		<title>Second result</title>
		<link>https://example.com/2</link>
		<description>Second snippet</description>
		</item>
		<item>
		<title>Duplicate</title>
		<link>https://example.com/1</link>
		</item>
		</channel></rss>
	""".trimIndent()

	private fun provider(server: LocalHttpServer): BingRssSearchApi =
		BingRssSearchApi(
			http = GuardedHttp.forTesting(),
			baseUrl = "${server.baseUrl}/search",
			requireHttps = false,
		)

	@Test
	fun `rss items are mapped with stripped descriptions and deduplicated links`() {
		val results = BingRssSearchApi.parse(rss, 10)

		assertEquals(2, results.size)
		assertEquals("First result", results[0].title)
		assertEquals("https://example.com/1", results[0].url)
		assertEquals("Bing snippet & more", results[0].snippet)
		assertEquals("https://example.com/2", results[1].url)
	}

	@Test
	fun `max results is a ceiling`() {
		assertEquals(1, BingRssSearchApi.parse(rss, 1).size)
	}

	@Test
	fun `a doctype is refused`() {
		val failure = runCatching {
			BingRssSearchApi.parse("<!DOCTYPE rss><rss></rss>", 5)
		}.exceptionOrNull()
		assertTrue(failure is java.io.IOException)
	}

	@Test
	fun `a non-rss page is an error not an empty list`() {
		val failure = runCatching {
			BingRssSearchApi.parse("<html><body>consent</body></html>", 5)
		}.exceptionOrNull()
		assertTrue(failure is java.io.IOException)
	}

	@Test
	fun `the provider fetches and parses end to end`() = runBlocking {
		val server = LocalHttpServer(
			mapOf("/search" to LocalHttpServer.Route(contentType = "text/xml", body = rss)),
		)
		try {
			val results = provider(server).search("kotlin", 5)
			assertEquals(2, results.size)
			assertEquals("https://example.com/1", results[0].url)
		} finally {
			server.close()
		}
	}

	@Test
	fun `an http error is reported with its status`() = runBlocking {
		val server = LocalHttpServer(
			mapOf("/search" to LocalHttpServer.Route(status = 429, body = "slow down")),
		)
		try {
			val failure = runCatching { provider(server).search("x", 5) }.exceptionOrNull()
			assertTrue(
				"expected HTTP 429 failure, got $failure",
				failure is java.io.IOException && "429" in failure.message.orEmpty(),
			)
		} finally {
			server.close()
		}
	}
}
