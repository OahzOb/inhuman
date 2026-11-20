package com.inhuman.core.ai.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTML handling is tested because the output becomes prompt text. A stale
 * `<em>` in a title is noise the model pays for, and an entity decoded twice is
 * a lie about what the page said. The article body comes from a third-party
 * editor whose HTML is not valid in the way a parser would demand, so the test
 * includes an unclosed/odd shape as well as the normal one.
 */
class BilibiliHtmlTest {

	@Test
	fun `search highlight markup is removed`() {
		assertEquals(
			"《原神》剧情PV",
			BilibiliHtml.stripTags("<em class=\"keyword\">原神</em>".let { "《${it}》剧情PV" }),
		)
	}

	@Test
	fun `entities are decoded once`() {
		assertEquals("a & b < c", BilibiliHtml.stripTags("a &amp; b &lt; c"))
		// `&amp;lt;` is an encoded entity, not an encoded tag: decoding it
		// twice would turn page text into something that looks like markup.
		assertEquals("&lt;", BilibiliHtml.stripTags("&amp;lt;"))
	}

	@Test
	fun `article body keeps paragraphs lists and emoji`() {
		val html = "<p>第一段<br/>换行</p><h1>标题</h1>" +
			"<ul><li>甲</li><li>乙</li></ul>" +
			"<figure><img src=\"x\"/><figcaption>123</figcaption></figure>" +
			"<figure><img src=\"y\"/></figure>" +
			"<p>小姨🤤🤤🤤</p>"

		val text = BilibiliHtml.toText(html)

		assertTrue("paragraph break lost: $text", text.contains("第一段\n换行"))
		assertTrue("heading lost: $text", text.contains("标题"))
		assertTrue("list marker lost: $text", text.contains("- 甲"))
		assertTrue("emoji lost: $text", text.contains("🤤"))
		// The figcaption is an image number, not prose; a wall of seven-digit
		// numbers is the most common shape of a Bilibili picture post.
		assertFalse("image caption survived: $text", text.contains("123"))
		assertFalse("raw markup survived: $text", text.contains("<"))
	}

	@Test
	fun `numeric entities become characters`() {
		assertEquals("😭😭", BilibiliHtml.toText("&#x1F62D;&#128557;"))
	}

	@Test
	fun `odd but readable input does not throw`() {
		assertEquals("hello world", BilibiliHtml.stripTags("hello <b>world"))
		assertEquals("", BilibiliHtml.toText(""))
	}
}
