package com.inhuman.core.ai.provider.deepseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the Search API's JSON.
 *
 * The fixture follows the official response shape: a `results` array whose
 * items carry `title`, `url`, `snippet`, `summary`, `date` and other fields this
 * app ignores. It is deliberately a fixture rather than a live call -- the
 * parsing is what changes when the provider changes, and it should be checked
 * without a network and without spending a request.
 */
class DeepSeekSearchApiTest {

	private fun results(vararg items: String) = """{"results":[${items.joinToString(",")}]}"""

	private fun item(
		title: String? = "Example",
		url: String? = "https://example.com/page",
		snippet: String? = null,
		summary: String? = null,
		date: String? = null,
	): String = buildString {
		append("{")
		val fields = mutableListOf<String>()
		title?.let { fields += """"title":${quote(it)}""" }
		url?.let { fields += """"url":${quote(it)}""" }
		snippet?.let { fields += """"snippet":${quote(it)}""" }
		summary?.let { fields += """"summary":${quote(it)}""" }
		date?.let { fields += """"date":${quote(it)}""" }
		append(fields.joinToString(","))
		append("}")
	}

	private fun quote(text: String) = "\"" + text.replace("\"", "\\\"") + "\""

	@Test
	fun `results come out in order with title and url`() {
		val out = DeepSeekSearchApi.parseResults(
			results(
				item("First", "https://example.com/1"),
				item("Second", "https://example.com/2"),
			),
		)

		assertEquals(2, out.size)
		assertEquals("First", out[0].title)
		assertEquals("https://example.com/1", out[0].url)
		assertEquals("Second", out[1].title)
	}

	@Test
	fun `summary is preferred over snippet`() {
		// The long summary is written for a model; the snippet is written for a
		// person scanning a list. One excerpt per result, and the model gets the
		// useful one.
		val out = DeepSeekSearchApi.parseResults(
			results(item(snippet = "short", summary = "a much longer summary")),
		)

		assertEquals("a much longer summary", out[0].snippet)
	}

	@Test
	fun `snippet is the fallback when summary is absent`() {
		val out = DeepSeekSearchApi.parseResults(results(item(snippet = "short")))

		assertEquals("short", out[0].snippet)
	}

	@Test
	fun `an empty date becomes no date rather than an empty string`() {
		val out = DeepSeekSearchApi.parseResults(results(item(date = "")))

		assertNull(out[0].publishedAt)
	}

	@Test
	fun `a date is carried through`() {
		val out = DeepSeekSearchApi.parseResults(results(item(date = "2026-10-17T00:00:00+00:00")))

		assertEquals("2026-10-17T00:00:00+00:00", out[0].publishedAt)
	}

	@Test
	fun `duplicate urls are collapsed after cleaning`() {
		val out = DeepSeekSearchApi.parseResults(
			results(
				item("First", "https://example.com/a?utm_source=x"),
				item("Second", "https://example.com/a"),
			),
		)

		assertEquals(1, out.size)
		assertEquals("First", out[0].title)
	}

	@Test
	fun `an item with no url is skipped`() {
		val out = DeepSeekSearchApi.parseResults(
			results(
				item(title = "No url", url = null),
				item("Real", "https://example.com/real"),
			),
		)

		assertEquals(listOf("https://example.com/real"), out.map { it.url })
	}

	@Test
	fun `a non-web url is skipped`() {
		val out = DeepSeekSearchApi.parseResults(
			results(
				item("Script", "javascript:alert(1)"),
				item("Real", "https://example.com/real"),
			),
		)

		assertEquals(listOf("https://example.com/real"), out.map { it.url })
	}

	@Test
	fun `a missing title falls back to the url`() {
		val out = DeepSeekSearchApi.parseResults(
			results(item(title = null, url = "https://example.com/x")),
		)

		assertEquals("https://example.com/x", out[0].title)
	}

	@Test
	fun `a malformed body yields nothing rather than throwing`() {
		assertEquals(0, DeepSeekSearchApi.parseResults("not json").size)
		assertEquals(0, DeepSeekSearchApi.parseResults("{}").size)
		assertEquals(0, DeepSeekSearchApi.parseResults("""{"results":[]}""").size)
	}

	// --- url cleaning ---------------------------------------------------------

	@Test
	fun `campaign parameters are stripped`() {
		val dirty = "https://kotlinlang.org/docs/releases.html" +
			"?via=Knowgenerativeai.com&utm_source=Knowgenerativeai.com" +
			"&utm_campaign=Knowgenerativeai.com&utm_medium=marketplace#release-history"

		assertEquals(
			"https://kotlinlang.org/docs/releases.html#release-history",
			DeepSeekSearchApi.cleanUrl(dirty),
		)
	}

	@Test
	fun `a meaningful parameter survives`() {
		assertEquals(
			"https://example.com/list?page=3",
			DeepSeekSearchApi.cleanUrl("https://example.com/list?page=3"),
		)
	}

	@Test
	fun `tracking is removed without touching the rest`() {
		assertEquals(
			"https://shop.example.com/item?id=9",
			DeepSeekSearchApi.cleanUrl(
				"https://shop.example.com/item?id=9&utm_source=news&gclid=abc&fbclid=def",
			),
		)
	}

	@Test
	fun `a url with no query is unchanged`() {
		assertEquals(
			"https://example.com/plain",
			DeepSeekSearchApi.cleanUrl("https://example.com/plain"),
		)
	}

	@Test
	fun `a url that is only tracking loses the question mark`() {
		assertEquals(
			"https://example.com/page",
			DeepSeekSearchApi.cleanUrl("https://example.com/page?utm_source=x"),
		)
	}

	@Test
	fun `a fragment survives cleaning`() {
		assertEquals(
			"https://example.com/doc#section-2",
			DeepSeekSearchApi.cleanUrl("https://example.com/doc?utm_medium=email#section-2"),
		)
	}

	@Test
	fun `html escaped ampersands are decoded before cleaning`() {
		// A live search returned this shape: the separators were HTML-escaped,
		// and splitting on '&' without decoding let every tracking parameter
		// through under a name like "amp;utm_medium".
		assertEquals(
			"https://example.com/a",
			DeepSeekSearchApi.cleanUrl("https://example.com/a?&amp;utm_source=x"),
		)
		assertEquals(
			"https://example.com/a?page=3",
			DeepSeekSearchApi.cleanUrl("https://example.com/a?page=3&amp;utm_source=x"),
		)
	}

	@Test
	fun `a clean url is not rewritten`() {
		assertTrue(
			DeepSeekSearchApi.cleanUrl("https://example.com/x?ref=docs")
				.endsWith("?ref=docs"),
		)
	}
}
