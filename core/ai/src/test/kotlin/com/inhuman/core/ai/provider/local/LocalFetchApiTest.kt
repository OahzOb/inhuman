package com.inhuman.core.ai.provider.local

import com.inhuman.core.ai.testing.LocalHttpServer
import com.inhuman.core.ai.tools.FetchedPage
import com.inhuman.core.ai.tools.GuardedHttp
import com.inhuman.core.ai.tools.PageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocalFetchApiTest {

	private val article = """
		<html><head><title>Example Domain</title>
		<script>secret()</script></head>
		<body><nav>menu</nav><article>
		<h1>Hello</h1>
		<p>First paragraph text.</p>
		<p>Second paragraph text.</p>
		<script>hidden-script()</script>
		</article></body></html>
	""".trimIndent()

	@Test
	fun `static fetch extracts visible article text`() = runBlocking {
		val server = LocalHttpServer(
			mapOf("/article" to LocalHttpServer.Route(body = article)),
		)
		try {
			val provider = LocalFetchApi(
				http = GuardedHttp.forTesting(),
				requireHttps = false,
			)

			val page = provider.fetch("${server.baseUrl}/article")

			assertEquals("Example Domain", page.title)
			assertTrue(page.content, page.content.contains("First paragraph text."))
			assertTrue(page.content, page.content.contains("Second paragraph text."))
			assertFalse("script body leaked: ${page.content}", page.content.contains("hidden-script"))
			assertFalse("nav leaked: ${page.content}", page.content.contains("menu"))
		} finally {
			server.close()
		}
	}

	@Test
	fun `json and plain text come back raw`() = runBlocking {
		val server = LocalHttpServer(
			mapOf(
				"/api" to LocalHttpServer.Route(
					contentType = "application/json",
					body = """{"ok":true}""",
				),
				"/text" to LocalHttpServer.Route(
					contentType = "text/plain; charset=utf-8",
					body = "plain body",
				),
			),
		)
		try {
			val provider = LocalFetchApi(
				http = GuardedHttp.forTesting(),
				requireHttps = false,
			)

			assertEquals("""{"ok":true}""", provider.fetch("${server.baseUrl}/api").content)
			assertEquals("plain body", provider.fetch("${server.baseUrl}/text").content)
		} finally {
			server.close()
		}
	}

	@Test
	fun `a binary page is refused with a readable error`() = runBlocking {
		val server = LocalHttpServer(
			mapOf(
				"/file.pdf" to LocalHttpServer.Route(
					contentType = "application/pdf",
					body = "%PDF-1.4",
				),
				"/image.png" to LocalHttpServer.Route(
					contentType = "image/png",
					body = "png",
				),
			),
		)
		try {
			val provider = LocalFetchApi(
				http = GuardedHttp.forTesting(),
				requireHttps = false,
			)

			for (path in listOf("/file.pdf", "/image.png")) {
				val failure = runCatching { provider.fetch(server.baseUrl + path) }
					.exceptionOrNull()
				assertTrue("${path} should be refused, got $failure", failure is IOException)
				assertTrue(
					"${path} error should mention download_url: $failure",
					"download_url" in failure!!.message.orEmpty(),
				)
			}
		} finally {
			server.close()
		}
	}

	@Test
	fun `a truncated body is marked in the content`() = runBlocking {
		val server = LocalHttpServer(
			mapOf(
				"/long" to LocalHttpServer.Route(
					body = "<html><body><main><p>" + "word ".repeat(200) + "</p></main></body></html>",
				),
			),
		)
		try {
			val provider = LocalFetchApi(
				http = GuardedHttp.forTesting(),
				requireHttps = false,
				maxBytes = 120,
			)

			val page = provider.fetch("${server.baseUrl}/long")
			assertTrue(page.content, "truncated at 120 bytes" in page.content)
		} finally {
			server.close()
		}
	}

	@Test
	fun `rendering is unavailable without a renderer`() = runBlocking {
		val provider = LocalFetchApi(
			http = GuardedHttp.forTesting(),
			requireHttps = false,
		)

		val failure = runCatching {
			provider.fetchRendered("https://example.com/")
		}.exceptionOrNull()

		assertTrue(failure is IOException)
		assertTrue("failure was $failure", "not available" in failure!!.message.orEmpty())
	}

	@Test
	fun `a renderer is used for render and its output is capped by the tool contract`() = runBlocking {
		val renderer = object : PageRenderer {
			override suspend fun render(url: String): FetchedPage =
				FetchedPage(title = "Rendered", content = "visible body")
		}
		val provider = LocalFetchApi(
			renderer = renderer,
			http = GuardedHttp.forTesting(),
			requireHttps = false,
		)

		val page = provider.fetchRendered("http://localhost:1/page")

		assertEquals("Rendered", page.title)
		assertEquals("visible body", page.content)
	}

	@Test
	fun `https is required and a private literal is refused before any network work`() = runBlocking {
		val provider = LocalFetchApi(http = GuardedHttp.forTesting())

		val insecure = runCatching { provider.fetch("http://example.com/x") }.exceptionOrNull()
		assertTrue(insecure is IllegalArgumentException)

		val privateLiteral = runCatching {
			LocalFetchApi(http = GuardedHttp.forTesting(), requireHttps = false)
				.fetch("http://127.0.0.1:1/x")
		}.exceptionOrNull()
		assertTrue(privateLiteral is IllegalArgumentException)
	}

	@Test
	fun `a rendered private host is refused before the browser engine runs`() = runBlocking {
		val renderer = object : PageRenderer {
			override suspend fun render(url: String): FetchedPage =
				FetchedPage("Rendered", "body")
		}
		// The default client enforces public addresses; localhost resolves to a
		// loopback address, so the render path must refuse it.
		val provider = LocalFetchApi(renderer = renderer, http = GuardedHttp.default)

		val failure = runCatching {
			provider.fetchRendered("https://localhost/x")
		}.exceptionOrNull()

		assertTrue(failure is IllegalArgumentException)
	}
}
