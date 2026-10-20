package com.inhuman.core.ai.provider.exa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ExaApiTest {

	@Test
	fun searchMapsTextIntoASnippet() {
		val page = ExaSearchApi("key").parse(
			"""
			{
			  "results": [
			    {
			      "title": "Kotlin 2.2",
			      "url": "https://example.com/kotlin",
			      "publishedDate": "2026-01-01",
			      "text": "The release notes."
			    }
			  ]
			}
			""".trimIndent(),
		)

		assertEquals(1, page.size)
		assertEquals("Kotlin 2.2", page[0].title)
		assertEquals("https://example.com/kotlin", page[0].url)
		assertEquals("The release notes.", page[0].snippet)
		assertEquals("2026-01-01", page[0].publishedAt)
	}

	@Test
	fun searchDropsResultsWithoutAUrl() {
		val page = ExaSearchApi("key").parse(
			"""{"results":[{"title":"no link"},{"title":"ok","url":"https://ok"}]}""",
		)
		assertEquals(1, page.size)
		assertEquals("https://ok", page[0].url)
	}

	@Test
	fun searchAcceptsAnEmptyResultList() {
		assertTrue(ExaSearchApi("key").parse("""{"results":[]}""").isEmpty())
	}

	@Test
	fun fetchReturnsTitleAndText() {
		val page = ExaFetchApi("key").parse(
			"""
			{"results":[{"title":"A page","url":"https://example.com","text":"Body"}]}
			""".trimIndent(),
			requestedUrl = "https://example.com",
		)
		assertEquals("A page", page.title)
		assertEquals("Body", page.content)
	}

	@Test
	fun fetchWithoutTextIsAnErrorRatherThanAnEmptyPage() {
		val failure = runCatching {
			ExaFetchApi("key").parse(
				"""{"results":[{"url":"https://example.com"}]}""",
				requestedUrl = "https://example.com",
			)
		}.exceptionOrNull()
		assertTrue("expected an IOException, got $failure", failure is IOException)
	}

	@Test
	fun aMissingSnippetIsNullNotAnEmptyString() {
		val page = ExaSearchApi("key").parse(
			"""{"results":[{"title":"T","url":"https://example.com"}]}""",
		)
		assertNull(page.single().snippet)
	}
}
