package com.inhuman.core.ai.provider.deepseek

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The OCR endpoint against the live service.
 *
 * ## Why this is skipped by default
 *
 * It spends money on the paid OCR API and needs a network and a credential a
 * build machine has neither of. Run it deliberately, with the key in
 * `DEEPSEEK_LIVE_KEY` (or `DEEPSEEK_API_KEY`):
 *
 * ```
 * DEEPSEEK_LIVE_KEY=sk-... ./gradlew :core:ai:test --tests '*DeepSeekOcrLiveTest'
 * ```
 *
 * The image is rendered locally with Java2D, so no fixture binary is committed.
 */
class DeepSeekOcrLiveTest {

	private val key: String? = System.getenv("DEEPSEEK_LIVE_KEY")?.takeIf { it.isNotBlank() }
		?: System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

	@Test
	fun `a live OCR reads a rendered image`() = runBlocking {
		assumeTrue(
			"DEEPSEEK_LIVE_KEY / DEEPSEEK_API_KEY not set; skipping the live call",
			!key.isNullOrBlank(),
		)

		val image = imageWithText("INHUMAN OCR 2026")
		val api = DeepSeekOcrApi(apiKey = { key })

		val result = api.recognize(image, "ocr-live.png")
		val text = result.pages.joinToString(" ") { it.text }
		println(
			"=== OCR pages=${result.pageCount} bytes=${image.size} " +
				"text=${text.replace("\n", " ").take(200)} ===",
		)

		assertTrue("OCR returned no text", text.isNotBlank())
		assertTrue(
			"the drawn text was not recognized: $text",
			text.lowercase().contains("ocr") || text.contains("2026"),
		)
	}

	/** A white image with large black text; enough for OCR to read reliably. */
	private fun imageWithText(text: String): ByteArray {
		val width = 1200
		val height = 300
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val graphics = image.createGraphics()
		try {
			graphics.setRenderingHint(
				RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
			)
			graphics.color = Color.WHITE
			graphics.fillRect(0, 0, width, height)
			graphics.color = Color.BLACK
			graphics.font = Font("SansSerif", Font.BOLD, 96)
			graphics.drawString(text, 40, 190)
		} finally {
			graphics.dispose()
		}
		val out = ByteArrayOutputStream()
		ImageIO.write(image, "png", out)
		return out.toByteArray()
	}
}
