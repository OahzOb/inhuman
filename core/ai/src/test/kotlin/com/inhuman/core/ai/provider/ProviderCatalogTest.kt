package com.inhuman.core.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog is the "only the key is required" promise. A duplicate id, a
 * blank base URL or a kind the factory does not understand breaks that promise
 * before any network call is made.
 */
class ProviderCatalogTest {

	@Test
	fun idsAndNamesAreUnique() {
		assertEquals(ProviderCatalog.known.size, ProviderCatalog.known.map { it.id }.toSet().size)
		assertEquals(ProviderCatalog.known.size, ProviderCatalog.known.map { it.name }.toSet().size)
	}

	@Test
	fun everyPresetHasAnHTTPSBaseUrl() {
		ProviderCatalog.known.forEach { provider ->
			assertTrue(provider.name, provider.baseUrl.startsWith("https://"))
			assertTrue(provider.name, provider.baseUrl.isNotBlank())
		}
	}

	@Test
	fun everyPresetUsesAKnownWireAPI() {
		val kinds = setOf(
			ProviderFactory.OPENAI_CHAT,
			ProviderFactory.ANTHROPIC_MESSAGES,
			ProviderFactory.GOOGLE_GENERATIVE_AI,
			ProviderFactory.QWEN_TOKEN_PLAN_CN,
			ProviderFactory.OPENAI_RESPONSES,
		)
		ProviderCatalog.known.forEach { provider ->
			assertTrue(provider.name, provider.kind in kinds)
		}
	}

	@Test
	fun theMainNativeProvidersArePresent() {
		assertEquals(
			ProviderFactory.ANTHROPIC_MESSAGES,
			ProviderCatalog.byId("anthropic")?.kind,
		)
		assertEquals(
			ProviderFactory.GOOGLE_GENERATIVE_AI,
			ProviderCatalog.byId("google")?.kind,
		)
		assertEquals(
			ProviderFactory.OPENAI_CHAT,
			ProviderCatalog.byId("openai")?.kind,
		)
		assertEquals(
			ProviderFactory.OPENAI_RESPONSES,
			ProviderCatalog.byId("openai-responses")?.kind,
		)
	}

	@Test
	fun opencodeZenAndGoAreOpenAiCompatiblePresets() {
		val zen = ProviderCatalog.byId("opencode-zen")
		val go = ProviderCatalog.byId("opencode-go")

		assertEquals(ProviderFactory.OPENAI_CHAT, zen?.kind)
		assertEquals(ProviderFactory.OPENAI_CHAT, go?.kind)
		assertEquals("https://opencode.ai/zen/v1", zen?.baseUrl)
		assertEquals("https://opencode.ai/zen/go/v1", go?.baseUrl)
		// The gateway takes `reasoning_effort` and replays
		// `reasoning_content`, but does not take DeepSeek's `thinking` object.
		assertEquals("opencode", zen?.reasoningMode)
		assertEquals("opencode", go?.reasoningMode)
	}
}
