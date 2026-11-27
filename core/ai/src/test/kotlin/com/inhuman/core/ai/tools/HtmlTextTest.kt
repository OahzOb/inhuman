package com.inhuman.core.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class HtmlTextTest {

	@Test
	fun `article text keeps paragraphs and drops scripts nav and footer`() {
		val html = """
			<html><head><title>Doc Title</title>
			<style>.x { color: red }</style>
			<script>alert('head')</script></head>
			<body><nav>menu</nav>
			<article><h1>Heading</h1>
			<p>First paragraph.</p>
			<p>Second <b>bold</b> paragraph.</p>
			<script>evil()</script></article>
			<footer>foot</footer></body></html>
		""".trimIndent()

		val out = HtmlText.extract(html)

		assertEquals("Doc Title", out.title)
		assertTrue(out.text, out.text.contains("First paragraph."))
		assertTrue(out.text, out.text.contains("Second bold paragraph."))
		assertFalse("script body leaked: ${out.text}", out.text.contains("evil"))
		assertFalse("style body leaked: ${out.text}", out.text.contains("color: red"))
		assertFalse("nav leaked: ${out.text}", out.text.contains("menu"))
		assertFalse("footer leaked: ${out.text}", out.text.contains("foot"))
		assertTrue("paragraphs should be on separate lines: ${out.text}", out.text.contains("\n"))
	}

	@Test
	fun `title falls back to the first h1`() {
		val out = HtmlText.extract("<html><body><main><h1>Only heading</h1><p>Body.</p></main></body></html>")
		assertEquals("Only heading", out.title)
	}

	@Test
	fun `main is used when there is no article`() {
		val out = HtmlText.extract(
			"<html><body><div>outside</div><main><p>inside</p></main></body></html>",
		)
		assertTrue(out.text, out.text.contains("inside"))
		assertFalse("content outside main should not be the page: ${out.text}", out.text.contains("outside"))
	}

	@Test
	fun `meta charset is detected when the header has none`() {
		val html = "<html><head><meta charset=\"gbk\"><title>中文标题</title></head>" +
			"<body><main><p>中文正文</p></main></body></html>"
		val bytes = html.toByteArray(Charset.forName("GBK"))

		val out = HtmlText.extract(bytes, contentType = "text/html", baseUri = "https://example.com/")

		assertTrue("text was: ${out.text}", out.text.contains("中文正文"))
	}

	@Test
	fun `header charset wins and fragment text strips markup`() {
		assertEquals("A B", HtmlText.text("<p>A <b>B</b></p>"))
		assertEquals("GBK", HtmlText.charsetOf("text/html; charset=GBK"))
		assertEquals("utf-8", HtmlText.charsetOf("text/html; charset=\"utf-8\""))
		assertEquals(null, HtmlText.charsetOf("text/html"))
	}
}
