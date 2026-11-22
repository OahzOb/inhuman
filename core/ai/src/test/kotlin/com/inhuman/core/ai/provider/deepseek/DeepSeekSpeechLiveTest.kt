package com.inhuman.core.ai.provider.deepseek

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The ASR and TTS endpoints against the live service.
 *
 * ## Why this is skipped by default
 *
 * It spends money on two paid APIs and needs a network and a credential a build
 * machine has neither of. Run it deliberately, with the key in
 * `DEEPSEEK_LIVE_KEY` (or `DEEPSEEK_API_KEY`):
 *
 * ```
 * DEEPSEEK_LIVE_KEY=sk-... ./gradlew :core:ai:test --tests '*DeepSeekSpeechLiveTest'
 * ```
 *
 * What it covers that fixtures cannot: whether the endpoint, headers, multipart
 * shape and output container are right. The local checks and the refusal paths
 * are covered without a network in [DeepSeekSpeechApiTest].
 */
class DeepSeekSpeechLiveTest {

	private val key: String? = System.getenv("DEEPSEEK_LIVE_KEY")?.takeIf { it.isNotBlank() }
		?: System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }

	@Test
	fun `a live synthesis returns wav that transcribes back`() = runBlocking {
		assumeTrue(
			"DEEPSEEK_LIVE_KEY / DEEPSEEK_API_KEY not set; skipping the live call",
			!key.isNullOrBlank(),
		)

		val api = DeepSeekSpeechApi(apiKey = { key })

		val audio = api.synthesize("你好，这是 inhuman 的语音测试。")
		assertTrue(
			"the synthesis did not return a WAV container",
			DeepSeekSpeechApi.looksLikeWav(audio.bytes),
		)
		assertTrue("the synthesis returned too few bytes", audio.bytes.size > 1_000)
		assertTrue(
			"the WAV data chunk is incomplete",
			DeepSeekSpeechApi.wavDataComplete(audio.bytes),
		)

		val transcript = api.transcribe(audio.bytes, "wav")
		assertTrue("the transcript is blank", transcript.text.isNotBlank())
		assertTrue(
			"the transcript does not look like the spoken text: ${transcript.text}",
			transcript.text.contains("语音"),
		)

		println(
			"=== synthesized ${audio.bytes.size} bytes; transcript: ${transcript.text} " +
				"(duration ${transcript.durationMillis ?: "?"} ms) ===",
		)
	}
}
