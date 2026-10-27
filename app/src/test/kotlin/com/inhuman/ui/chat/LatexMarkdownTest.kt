package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LaTeX rewrite and its streaming contract.
 *
 * The renderer only ever sees Markdown. `LatexMarkdown` turns math into image
 * links, withholds a construct whose closing delimiter has not arrived, and
 * leaves code alone. The prefix test is the part that matters for streaming: the
 * append-only parser cannot be told to replace text, so every longer snapshot
 * must extend the previous output rather than rewrite it.
 */
class LatexMarkdownTest {

	private fun onlyUrl(out: String): String {
		val start = out.indexOf("https://inhuman.invalid/math/")
		assertTrue("no math URL in: $out", start >= 0)
		return out.substring(start).substringBefore(')')
	}

	private fun ref(out: String): MathRef {
		val url = onlyUrl(out)
		return parseMathUrl(url) ?: error("unparseable URL: $url")
	}

	@Test
	fun `inline math becomes an inline image`() {
		val out = LatexMarkdown.transform("before \$x^2\$ after", finished = true)

		assertTrue(out.startsWith("before ![math](https://inhuman.invalid/math/i/"))
		assertTrue(out.endsWith(") after"))
		assertEquals(MathRef("x^2", display = false), ref(out))
	}

	@Test
	fun `display math becomes a block image`() {
		val out = LatexMarkdown.transform("a\n\n\$\$\\frac{1}{2}\$\$\n\nb", finished = true)

		assertTrue(out.startsWith("a\n\n\n\n![math](https://inhuman.invalid/math/d/"))
		assertEquals(MathRef("\\frac{1}{2}", display = true), ref(out))
		assertTrue(out.endsWith("\n\nb"))
	}

	@Test
	fun `text with no math is unchanged`() {
		val text = "Just prose, a table | and `code`."
		assertEquals(text, LatexMarkdown.transform(text, finished = true))
	}

	@Test
	fun `inline code and fences are left alone`() {
		val text = "Use `\$x\$` or:\n\n```kotlin\nval x = \"\$y\$\"\n```\n"
		assertEquals(text, LatexMarkdown.transform(text, finished = true))
	}

	@Test
	fun `an escaped dollar is literal`() {
		val text = "Costs \\\$5, not math."
		assertEquals(text, LatexMarkdown.transform(text, finished = true))
	}

	@Test
	fun `currency is not mistaken for a formula`() {
		val text = "It costs \$5 and the other costs \$10."
		assertEquals(text, LatexMarkdown.transform(text, finished = true))
	}

	@Test
	fun `an unfinished inline formula is withheld until it closes`() {
		assertEquals("Answer ", LatexMarkdown.transform("Answer \$x^2", finished = false))
		assertTrue(
			LatexMarkdown.transform("Answer \$x^2\$", finished = false)
				.contains("math/i/"),
		)
	}

	@Test
	fun `an unfinished formula is flushed literally at the end`() {
		val text = "Answer \$x^2"
		assertEquals(text, LatexMarkdown.transform(text, finished = true))
	}

	@Test
	fun `an unfinished display formula is withheld`() {
		assertEquals("Answer ", LatexMarkdown.transform("Answer \$\$\\frac{1}{2}", finished = false))
		assertEquals(
			"Answer \$\$\\frac{1}{2}",
			LatexMarkdown.transform("Answer \$\$\\frac{1}{2}", finished = true),
		)
	}

	@Test
	fun `a formula over the layout guard stays literal`() {
		val long = "x".repeat(MAX_MATH_CHARS + 1)
		val text = "\$$long\$"
		assertEquals(text, LatexMarkdown.transform(text, finished = true))
	}

	@Test
	fun `the URL survives characters that would break a Markdown destination`() {
		val latex = "\\frac{a}{b} + \\sqrt{2} = \\text{price & tax}"
		val out = LatexMarkdown.transform("\$$latex\$", finished = true)

		assertEquals(MathRef(latex, display = false), ref(out))
	}

	@Test
	fun `a non-math URL is not a math reference`() {
		assertNull(parseMathUrl("https://example.com/image.png"))
		assertNull(parseMathUrl("https://inhuman.invalid/math/i/not-base64!"))
		assertNull(parseMathUrl("https://inhuman.invalid/math/x/abc"))
	}

	@Test
	fun `every prefix extends the previous streaming output`() {
		val text = buildString {
			append("Intro ")
			append("\$x^2\$")
			append(" and \$\$\\frac{1}{2}\$\$ plus ")
			append("`code \$y\$` and ")
			append("```\n\$z\$\n```\n")
			append("Costs \$5 and \$10. ")
			append("\\\$not math\$ ")
			append("Unclosed \$tail")
		}
		var previous = ""
		for (length in 0..text.length) {
			val out = LatexMarkdown.transform(text.substring(0, length), finished = false)
			assertTrue(
				"output at $length does not extend its predecessor:\nnew=$out\nold=$previous",
				out.startsWith(previous),
			)
			previous = out
		}
	}

	@Test
	fun `finishing extends the last unfinished snapshot`() {
		val text = "Answer \$x^2"
		val streaming = LatexMarkdown.transform(text, finished = false)
		val finished = LatexMarkdown.transform(text, finished = true)

		assertTrue(finished.startsWith(streaming))
		assertFalse(finished.contains("math/i/"))
	}
}