package com.inhuman.ui.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one availability predicate the pickers and the request builder share.
 *
 * The cases are the ones that can silently disagree: a mode with a key but no
 * endpoint, a provider kind that cannot serve the capability, and the workspace
 * condition that gates the four file-writing tools. A picker that gets any of
 * these wrong either offers an inert row or hides a working tool, and neither
 * shows up as a failure anywhere else.
 */
class MediaAvailabilityTest {

	private fun direct(baseUrl: String, apiKey: String) =
		MediaToolConfig(mode = MediaMode.Direct, baseUrl = baseUrl, apiKey = apiKey)

	private fun provider(id: String) = MediaToolConfig(mode = MediaMode.Provider, providerId = id)

	private val noProfile: suspend (String) -> MediaProviderFacts? = { null }

	@Test
	fun `a direct backend needs both an https endpoint and a key`() = runBlocking {
		assertTrue(
			direct("https://example.test/v1", "k").isConfigured(
				MediaCapability.Image,
				assistant = null,
				profile = noProfile,
			),
		)
		assertFalse(
			direct("https://example.test/v1", "").isConfigured(
				MediaCapability.Image,
				assistant = null,
				profile = noProfile,
			),
		)
		// Plain HTTP is refused by the resolver, so it must not count here.
		assertFalse(
			direct("http://example.test/v1", "k").isConfigured(
				MediaCapability.Image,
				assistant = null,
				profile = noProfile,
			),
		)
	}

	@Test
	fun `the assistant's provider must serve the capability and have a key and endpoint`() = runBlocking {
		val qwen = MediaProviderFacts(kind = "qwen-token-plan", hasKey = true, baseUrl = "https://q.test")
		assertTrue(
			MediaToolConfig().isConfigured(MediaCapability.Image, qwen, noProfile),
		)
		assertFalse(
			"no key",
			MediaToolConfig().isConfigured(
				MediaCapability.Image,
				qwen.copy(hasKey = false),
				noProfile,
			),
		)
		assertFalse(
			"no endpoint",
			MediaToolConfig().isConfigured(
				MediaCapability.Image,
				qwen.copy(baseUrl = null),
				noProfile,
			),
		)
		// DeepSeek serves speech but not image; the media dialog says so.
		val deepSeek = MediaProviderFacts(kind = "deepseek", hasKey = true, baseUrl = "https://d.test")
		assertTrue(MediaToolConfig().isConfigured(MediaCapability.Speech, deepSeek, noProfile))
		assertFalse(MediaToolConfig().isConfigured(MediaCapability.Image, deepSeek, noProfile))
	}

	@Test
	fun `an explicitly chosen profile must resolve and serve the capability`() = runBlocking {
		val facts = MediaProviderFacts(kind = "qwen-token-plan", hasKey = true, baseUrl = "https://q.test")
		val lookup: suspend (String) -> MediaProviderFacts? = { id ->
			if (id == "chosen") facts else null
		}
		assertTrue(provider("chosen").isConfigured(MediaCapability.Video, null, lookup))
		assertFalse(
			"unknown profile",
			provider("missing").isConfigured(MediaCapability.Video, null, lookup),
		)
		assertFalse(
			"profile without a key",
			provider("chosen").isConfigured(MediaCapability.Video, null) { facts.copy(hasKey = false) },
		)
		assertFalse(
			"profile that cannot serve it",
			provider("chosen").isConfigured(MediaCapability.Image, null) { facts.copy(kind = "deepseek") },
		)
	}

	@Test
	fun `tool names follow the capability, and video owns its status poller`() {
		assertEquals(listOf("generate_image"), MediaCapability.Image.toolNames)
		assertEquals(listOf("generate_video", "video_status"), MediaCapability.Video.toolNames)
		assertEquals(listOf("speak"), MediaCapability.Speech.toolNames)
		assertEquals(listOf("transcribe"), MediaCapability.Transcription.toolNames)
		assertEquals(listOf("decide"), MediaCapability.Decision.toolNames)
	}

	@Test
	fun `without a workspace only the text-only capability counts`() = runBlocking {
		val configs = mapOf(
			MediaCapability.Decision to direct("https://a.test", "k"),
			MediaCapability.Image to direct("https://b.test", "k"),
		)
		val configFor: (MediaCapability) -> MediaToolConfig = { configs[it] ?: MediaToolConfig() }
		val profile: suspend (String) -> MediaProviderFacts? = { null }
		val assistant: (MediaCapability) -> MediaProviderFacts? = { null }

		assertEquals(
			setOf("decide"),
			mediaConfiguredToolNames(configFor, false, assistant, profile),
		)
		assertEquals(
			setOf("decide", "generate_image"),
			mediaConfiguredToolNames(configFor, true, assistant, profile),
		)
	}

	@Test
	fun `an unconfigured capability contributes no names`() = runBlocking {
		val names = mediaConfiguredToolNames(
			configFor = { MediaToolConfig() },
			workspaceAvailable = true,
			assistantFacts = { null },
			profileFacts = { null },
		)
		assertTrue(names.isEmpty())
	}
}
