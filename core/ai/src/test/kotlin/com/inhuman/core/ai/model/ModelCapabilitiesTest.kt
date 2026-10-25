package com.inhuman.core.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of "this model is not for chatting".
 *
 * The inference is a guess from an id, so the test states the guesses that made
 * it into the list and -- more importantly -- the chat models that must not be
 * caught by it. A false positive hides a working model behind a "not a chat
 * model" label, which is worse than the raw 400 that started this.
 */
class ModelCapabilitiesTest {

	@Test
	fun `generation models are not inferred as chat models`() {
		assertEquals(setOf(ModelCapability.Image), ModelCapabilities.infer("wan2.7-image"))
		assertEquals(setOf(ModelCapability.Image), ModelCapabilities.infer("dall-e-3"))
		assertEquals(setOf(ModelCapability.Image), ModelCapabilities.infer("flux-schnell"))
		assertEquals(setOf(ModelCapability.Video), ModelCapabilities.infer("wan-video-2.1"))
		assertEquals(setOf(ModelCapability.Video), ModelCapabilities.infer("sora-2"))
		assertEquals(setOf(ModelCapability.Audio), ModelCapabilities.infer("gpt-4o-tts"))
		assertEquals(setOf(ModelCapability.Audio), ModelCapabilities.infer("whisper-1"))

		listOf("wan2.7-image", "sora-2", "whisper-1").forEach {
			assertFalse("$it must not count as a chat model", ModelCapabilities.isChatModel(ModelCapabilities.infer(it)))
		}
	}

	@Test
	fun `ordinary chat models stay chat models`() {
		// The ids that actually appear in this app. A rule that hides one of
		// these is a regression the user cannot work around except by tagging.
		listOf(
			"deepseek-v4.1-flash",
			"deepseek-reasoner",
			"gpt-4o",
			"claude-sonnet-4",
			"gemini-2.5-pro",
			"qwen3-max",
			"qwen-plus",
			"llama-3.1-70b-instruct",
		).forEach { id ->
			assertEquals(id, setOf(ModelCapability.Text), ModelCapabilities.infer(id))
		}
	}

	@Test
	fun `a tag replaces the guess rather than adding to it`() {
		// The override has to be able to say "this image model is fine as a chat
		// model" and "this innocuous id is not", or it cannot fix a bad guess.
		assertEquals(
			setOf(ModelCapability.Text),
			ModelCapabilities.effective("wan2.7-image", setOf(ModelCapability.Text)),
		)
		assertEquals(
			setOf(ModelCapability.Image),
			ModelCapabilities.effective("qwen3-max", setOf(ModelCapability.Image)),
		)
		// Untagged keeps the guess.
		assertEquals(
			setOf(ModelCapability.Image),
			ModelCapabilities.effective("wan2.7-image", null),
		)
	}

	@Test
	fun `tags survive the round trip and unknown keys are dropped`() {
		val tags = mapOf(
			"wan2.7-image" to setOf(ModelCapability.Image),
			"qwen3-max" to setOf(ModelCapability.Text, ModelCapability.Tools),
		)
		val encoded = ModelTagCodec.encode(tags)
		assertEquals(tags, ModelTagCodec.decode(encoded))

		assertEquals(emptyMap<String, Set<ModelCapability>>(), ModelTagCodec.decode(""))
		assertEquals(emptyMap<String, Set<ModelCapability>>(), ModelTagCodec.decode("not json"))
		// A tag removed in a later release must not make the row unreadable.
		assertEquals(
			mapOf("m" to setOf(ModelCapability.Text)),
			ModelTagCodec.decode("""{"m":["text","teleportation"]}"""),
		)
		// An empty set is stored as nothing at all, which is "not tagged".
		assertEquals("", ModelTagCodec.encode(mapOf("m" to emptySet())))
	}
}

/**
 * The recogniser that turns a raw 400 into a sentence, and the 400s it must not
 * claim to understand.
 */
class ModelRejectionTest {

	private val qwen = "HTTP 400 Bad Request: {\"request_id\":\"77a4e746\",\"code\":" +
		"\"InvalidParameter\",\"message\":\"Input should be 'user': input.messages.0.role & " +
		"Input should be a valid list: input.messages.0.content\"}"

	@Test
	fun `the failure that started this is recognised`() {
		assertTrue(ModelRejection.looksLikeModelRejection(qwen))
	}

	@Test
	fun `other 400s are left alone`() {
		// A validation word without a message-shaped word is a different
		// problem, and inventing a model diagnosis for it would send someone
		// to the wrong setting.
		assertFalse(ModelRejection.looksLikeModelRejection("HTTP 400 InvalidParameter: temperature"))
		assertFalse(ModelRejection.looksLikeModelRejection("HTTP 400 Bad Request: context length exceeded"))
		assertFalse(ModelRejection.looksLikeModelRejection("HTTP 401 Unauthorized"))
		assertFalse(ModelRejection.looksLikeModelRejection("HTTP 500 Internal Server Error"))
		assertFalse(ModelRejection.looksLikeModelRejection("timeout"))
	}

	@Test
	fun `an explicit unsupported-model message is recognised`() {
		assertTrue(ModelRejection.looksLikeModelRejection("HTTP 400 model_not_supported"))
		assertTrue(ModelRejection.looksLikeModelRejection("This model does not support chat completions"))
	}
}