package com.inhuman.core.ai.provider.deepseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The Fetch API's validation and response shape.
 *
 * The validation is not cosmetic: each rejected URL is a paid request the app
 * does not make, and a clear message is what lets the model correct the call
 * instead of retrying it.
 */
class DeepSeekFetchApiTest {

	// --- validation -----------------------------------------------------------

	@Test
	fun `http and https are accepted and trimmed`() {
		assertEquals(
			"https://example.com/page",
			DeepSeekFetchApi.validateUrl("  https://example.com/page  "),
		)
		assertEquals(
			"http://example.com/page",
			DeepSeekFetchApi.validateUrl("http://example.com/page"),
		)
	}

	@Test
	fun `a non-web scheme is refused before the call`() {
		for (url in listOf("file:///etc/passwd", "ftp://example.com/x", "javascript:alert(1)")) {
			val error = assertThrows(IllegalArgumentException::class.java) {
				DeepSeekFetchApi.validateUrl(url)
			}
			assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("http"))
		}
	}

	@Test
	fun `a url without a host is refused`() {
		assertThrows(IllegalArgumentException::class.java) {
			DeepSeekFetchApi.validateUrl("https:///path")
		}
	}

	@Test
	fun `credentials in the url are refused`() {
		val error = assertThrows(IllegalArgumentException::class.java) {
			DeepSeekFetchApi.validateUrl("https://user:pass@example.com/page")
		}
		assertTrue(error.message.orEmpty().contains("username"))
	}

	@Test
	fun `an over-long url is refused`() {
		val url = "https://example.com/" + "a".repeat(DeepSeekFetchApi.MAX_URL_LENGTH)

		assertThrows(IllegalArgumentException::class.java) {
			DeepSeekFetchApi.validateUrl(url)
		}
	}

	@Test
	fun `an empty url is refused`() {
		assertThrows(IllegalArgumentException::class.java) {
			DeepSeekFetchApi.validateUrl("   ")
		}
	}

	// --- response -------------------------------------------------------------

	@Test
	fun `title and markdown content are read`() {
		val page = DeepSeekFetchApi.parsePage(
			"""{"title":"Example article","content":"# Example\n\nBody."}""",
		)

		assertEquals("Example article", page.title)
		assertEquals("# Example\n\nBody.", page.content)
	}

	@Test
	fun `missing fields become empty rather than throwing`() {
		val page = DeepSeekFetchApi.parsePage("{}")

		assertEquals("", page.title)
		assertEquals("", page.content)
	}

	@Test
	fun `a body that is not json is a failure, not an empty page`() {
		// The tool would otherwise report "no readable content", which sounds
		// like the page's fault rather than the response's.
		assertThrows(IOException::class.java) {
			DeepSeekFetchApi.parsePage("<html>not json</html>")
		}
	}
}
