package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaToolsTest {

	@Test
	fun `the default is the assistant's provider`() {
		val config = MediaToolConfig()

		assertEquals(MediaMode.Assistant, config.mode)
		assertTrue(config.isDefault)
		assertEquals(MediaCapability.Image.defaultModel, config.modelOrDefault(MediaCapability.Image))
		assertEquals(
			MediaCapability.Transcription.defaultModel,
			config.modelOrDefault(MediaCapability.Transcription),
		)
	}

	@Test
	fun `a configured provider keeps its id and model`() {
		val config = MediaToolConfig(
			mode = MediaMode.Provider,
			providerId = "qwen-token-plan-cn",
			model = "wan3.0-video",
		)

		val decoded = MediaToolConfig.decode(config.encode())

		assertEquals(MediaMode.Provider, decoded.mode)
		assertEquals("qwen-token-plan-cn", decoded.providerId)
		assertEquals("wan3.0-video", decoded.model)
		assertEquals("wan3.0-video", decoded.modelOrDefault(MediaCapability.Video))
		assertFalse(decoded.isDefault)
	}

	@Test
	fun `a separate api keeps its endpoint and key`() {
		val config = MediaToolConfig(
			mode = MediaMode.Direct,
			model = "wan2.7-image",
			baseUrl = "https://example.test/compatible-mode/v1",
			apiKey = "sk-test",
		)

		val decoded = MediaToolConfig.decode(config.encode())

		assertEquals(MediaMode.Direct, decoded.mode)
		assertEquals("https://example.test/compatible-mode/v1", decoded.baseUrl)
		assertEquals("sk-test", decoded.apiKey)
		assertEquals("wan2.7-image", decoded.model)
	}

	@Test
	fun `an unknown mode or malformed value falls back to the assistant`() {
		assertEquals(
			MediaMode.Assistant,
			MediaToolConfig.decode("""{"mode":"something-new"}""").mode,
		)
		assertEquals(MediaMode.Assistant, MediaToolConfig.decode("not json").mode)
		assertEquals(MediaMode.Assistant, MediaToolConfig.decode(null).mode)
	}

	@Test
	fun `a blank model means the capability default`() {
		val config = MediaToolConfig(mode = MediaMode.Provider, providerId = "p", model = "")

		assertEquals(
			MediaCapability.Speech.defaultModel,
			config.modelOrDefault(MediaCapability.Speech),
		)
	}
}