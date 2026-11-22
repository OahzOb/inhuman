package com.inhuman.core.ai.speech

import com.inhuman.core.ai.provider.deepseek.DeepSeekSpeechApi
import com.inhuman.core.ai.provider.qwen.QwenTokenPlanClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The format gate each backend exposes to the tool layer.
 *
 * This is the check that keeps an MP3 from being sent to DeepSeek's ASR
 * endpoint: the tool asks `acceptsFormat` before reading the file, so a wrong
 * pairing is a local refusal rather than a paid 400.
 */
class SpeechBackendTest {

	@Test
	fun deepSeekAcceptsWavAndOggOpusOnly() {
		val backend = DeepSeekSpeechBackend(DeepSeekSpeechApi(apiKey = { "test-key" }))
		assertTrue(backend.acceptsFormat("wav"))
		assertTrue(backend.acceptsFormat("OPUS"))
		assertFalse(backend.acceptsFormat("mp3"))
		assertFalse(backend.acceptsFormat("flac"))
	}

	@Test
	fun qwenAcceptsTheThreeWorkspaceAudioFormats() {
		val backend = QwenSpeechBackend(
			QwenTokenPlanClient("test-key", "https://example.test"),
			"test-model",
		)
		assertTrue(backend.acceptsFormat("mp3"))
		assertTrue(backend.acceptsFormat("wav"))
		assertTrue(backend.acceptsFormat("opus"))
		assertFalse(backend.acceptsFormat("flac"))
	}
}
