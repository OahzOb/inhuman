package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown-to-speech projection.
 *
 * The device cannot verify this: a synthetic TTS sample only proves that bytes
 * came back. The invariant that matters is what the model's reply becomes
 * before it is sent, and that is a pure string transformation.
 */
class SpeechTextTest {

	@Test
	fun headingsListsAndEmphasisLoseTheirSyntax() {
		val markdown = """
			## 结论

			- **第一点** 很重要
			- 第二点见[文档](https://example.com)
		""".trimIndent()

		val speech = SpeechText.fromMarkdown(markdown)

		assertFalse(speech.contains("#"))
		assertFalse(speech.contains("*"))
		assertFalse(speech.contains("]("))
		assertTrue(speech.contains("结论"))
		assertTrue(speech.contains("第一点 很重要"))
		assertTrue(speech.contains("文档"))
	}

	@Test
	fun codeBlocksAndTablesAreAnnouncedNotRead() {
		val markdown = """
			```kotlin
			val secret = 42
			```

			| a | b |
			| - | - |
			| 1 | 2 |
		""".trimIndent()

		val speech = SpeechText.fromMarkdown(markdown)

		assertTrue(speech.contains("代码块"))
		assertTrue(speech.contains("表格"))
		assertFalse(speech.contains("val secret"))
		assertFalse(speech.contains("|"))
	}

	@Test
	fun inlineCodeKeepsItsTextAndImagesKeepAltText() {
		val markdown = "运行 `npm test`，然后看 ![截图](shot.png)。"
		assertEquals("运行 npm test，然后看 截图。", SpeechText.fromMarkdown(markdown))
	}

	@Test
	fun blankMarkdownBecomesBlankSpeech() {
		assertEquals("", SpeechText.fromMarkdown("   \n\n  "))
	}
}
