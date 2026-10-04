package com.inhuman.core.ai.provider.deepseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading DeepSeek's native search out of a Messages reply.
 *
 * The fixture is a trimmed copy of a real response, keeping the parts that
 * matter: the block nesting, the item shape, and the campaign parameters that
 * actually came back on a live URL. Inventing one would test this against an
 * idea of the format rather than the format -- and the format is the provider's,
 * not ours, so a change in it is exactly what needs to be noticed.
 */
class DeepSeekSearchResponseTest {

	/** Trimmed from a live `/anthropic/v1/messages` reply. */
	private fun reply(vararg blocks: String) =
		"""{"id":"msg_1","type":"message","role":"assistant","stop_reason":"end_turn",""" +
			""""content":[${blocks.joinToString(",")}]}"""

	private fun resultBlock(vararg items: String) =
		"""{"type":"web_search_tool_result","tool_use_id":"srv_1","content":""" +
			"""[${items.joinToString(",")}]}"""

	private fun item(
		title: String,
		url: String,
		pageAge: String = "",
	): String = """{"type":"web_search_result","title":"$title","url":"$url",""" +
		""""page_age":"$pageAge","encrypted_content":"GrlRTRJEyefbPWGj"}"""

	// --- the happy path -------------------------------------------------------

	@Test
	fun `sources come out in order`() {
		val body = reply(
			resultBlock(
				item("Kotlin release process | Kotlin", "https://kotlinlang.org/docs/releases.html"),
				item("Kotlin 2.4.0 Released", "https://blog.jetbrains.com/kotlin/2-4-0/"),
			),
		)

		val out = DeepSeekSearchResponse.parse(body)

		assertEquals(2, out.size)
		assertEquals("Kotlin release process | Kotlin", out[0].title)
		assertEquals("https://kotlinlang.org/docs/releases.html", out[0].url)
		assertEquals("Kotlin 2.4.0 Released", out[1].title)
	}

	@Test
	fun `the prose beside the results is not returned`() {
		// The model writes a summary around the sources. It is deliberately
		// dropped: it is a claim *about* the evidence, made by the same model
		// that is about to be handed the evidence, and passing it along would
		// let a synthesis be cited as a source.
		val body = reply(
			"""{"type":"text","text":"Based on my searches, the answer is 42."}""",
			resultBlock(item("A source", "https://example.com/a")),
		)

		val out = DeepSeekSearchResponse.parse(body)

		assertEquals(1, out.size)
		assertTrue(
			"prose must not become a result",
			out.none { it.url.contains("Based on my searches") },
		)
		assertEquals("https://example.com/a", out[0].url)
	}

	@Test
	fun `a model that answered without searching yields nothing`() {
		// A legitimate outcome: it decided it already knew. Not an error, and
		// the caller distinguishes it by the empty list rather than an exception.
		val body = reply("""{"type":"text","text":"Kotlin 2.0 is current."}""")

		assertEquals(0, DeepSeekSearchResponse.parse(body).size)
	}

	@Test
	fun `several searches in one turn are merged`() {
		// A vague question makes the model search twice, which is sensible.
		val body = reply(
			resultBlock(item("First", "https://example.com/1")),
			resultBlock(item("Second", "https://example.com/2")),
		)

		val out = DeepSeekSearchResponse.parse(body)

		assertEquals(listOf("https://example.com/1", "https://example.com/2"), out.map { it.url })
	}

	@Test
	fun `the same page found twice appears once`() {
		val body = reply(
			resultBlock(item("Kotlin", "https://kotlinlang.org/docs/releases.html")),
			resultBlock(item("Kotlin again", "https://kotlinlang.org/docs/releases.html")),
		)

		val out = DeepSeekSearchResponse.parse(body)

		assertEquals(1, out.size)
		// The first wins, so the order reflects where it was first seen.
		assertEquals("Kotlin", out[0].title)
	}

	@Test
	fun `page age is carried through when present`() {
		val body = reply(resultBlock(item("T", "https://e.com", pageAge = "3 days ago")))

		assertEquals("3 days ago", DeepSeekSearchResponse.parse(body)[0].publishedAt)
	}

	@Test
	fun `an empty page age becomes no date rather than an empty string`() {
		// Live results carry `"page_age":""` constantly. Preserved as empty it
		// would render as "published: " on every result.
		val body = reply(resultBlock(item("T", "https://e.com", pageAge = "")))

		assertNull(DeepSeekSearchResponse.parse(body)[0].publishedAt)
	}

	// --- url cleaning ---------------------------------------------------------

	@Test
	fun `campaign parameters are stripped from a live url`() {
		// This is verbatim what a real search returned for a Kotlin query.
		val dirty = "https://kotlinlang.org/docs/releases.html" +
			"?via=Knowgenerativeai.com&utm_source=Knowgenerativeai.com" +
			"&utm_campaign=Knowgenerativeai.com&utm_medium=marketplace#release-history"

		assertEquals(
			"https://kotlinlang.org/docs/releases.html#release-history",
			DeepSeekSearchResponse.cleanUrl(dirty),
		)
	}

	@Test
	fun `a meaningful parameter survives`() {
		// `page` selects content. Removing unknown parameters to be tidy would
		// turn a working link into a broken one, which is the worse failure.
		assertEquals(
			"https://example.com/list?page=3",
			DeepSeekSearchResponse.cleanUrl("https://example.com/list?page=3"),
		)
	}

	@Test
	fun `tracking is removed without touching the rest`() {
		assertEquals(
			"https://shop.example.com/item?id=9",
			DeepSeekSearchResponse.cleanUrl(
				"https://shop.example.com/item?id=9&utm_source=news&gclid=abc&fbclid=def",
			),
		)
	}

	@Test
	fun `a url with no query is unchanged`() {
		assertEquals(
			"https://example.com/plain",
			DeepSeekSearchResponse.cleanUrl("https://example.com/plain"),
		)
	}

	@Test
	fun `a url that is only tracking loses the question mark`() {
		assertEquals(
			"https://example.com/page",
			DeepSeekSearchResponse.cleanUrl("https://example.com/page?utm_source=x"),
		)
	}

	@Test
	fun `a fragment survives cleaning`() {
		assertEquals(
			"https://example.com/doc#section-2",
			DeepSeekSearchResponse.cleanUrl("https://example.com/doc?utm_medium=email#section-2"),
		)
	}

	// --- robustness -----------------------------------------------------------

	@Test
	fun `an item with no url is skipped`() {
		val body = reply(
			resultBlock(
				"""{"type":"web_search_result","title":"No url","url":""}""",
				item("Real", "https://example.com/real"),
			),
		)

		val out = DeepSeekSearchResponse.parse(body)

		assertEquals(listOf("https://example.com/real"), out.map { it.url })
	}

	@Test
	fun `a missing title falls back to the url`() {
		// Better than an empty label: the reader can still see where it points.
		val body = reply(
			resultBlock("""{"type":"web_search_result","url":"https://example.com/x"}"""),
		)

		assertEquals("https://example.com/x", DeepSeekSearchResponse.parse(body)[0].title)
	}

	@Test
	fun `an error item is not mistaken for a result`() {
		// The tool result block can carry an error object instead of an array
		// when the search fails upstream. It must not become a source.
		val body = reply(
			"""{"type":"web_search_tool_result","tool_use_id":"srv_1",""" +
				""""content":{"type":"web_search_tool_result_error","error_code":"unavailable"}}""",
		)

		assertEquals(0, DeepSeekSearchResponse.parse(body).size)
	}

	@Test
	fun `a malformed body yields nothing rather than throwing`() {
		// The caller already handles an empty list; an exception here would turn
		// a readable reply into a failed search.
		assertEquals(0, DeepSeekSearchResponse.parse("not json").size)
		assertEquals(0, DeepSeekSearchResponse.parse("{}").size)
		assertEquals(0, DeepSeekSearchResponse.parse("""{"content":[]}""").size)
	}

	@Test
	fun `unknown block types are ignored`() {
		val body = reply(
			"""{"type":"thinking","thinking":"let me search"}""",
			"""{"type":"server_tool_use","name":"web_search","input":{"query":"x"}}""",
			resultBlock(item("Real", "https://example.com/real")),
		)

		assertEquals(1, DeepSeekSearchResponse.parse(body).size)
	}
}
