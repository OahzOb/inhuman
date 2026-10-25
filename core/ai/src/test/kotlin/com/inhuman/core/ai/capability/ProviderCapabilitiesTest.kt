package com.inhuman.core.ai.capability

import com.inhuman.core.ai.provider.ProviderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilitiesTest {

	@Test
	fun qwenTokenPlanIsABundle() {
		val capabilities = ProviderCapabilities.of(ProviderFactory.QWEN_TOKEN_PLAN_CN)
		ProviderCapability.entries.forEach { capability ->
			assertTrue(capability.name, capability in capabilities)
		}
	}

	@Test
	fun anOrdinaryChatProviderDoesNotAdvertiseMedia() {
		assertTrue(
			ProviderCapabilities.supports(ProviderFactory.OPENAI_CHAT, ProviderCapability.Chat),
		)
		assertFalse(
			ProviderCapabilities.supports(ProviderFactory.OPENAI_CHAT, ProviderCapability.Image),
		)
		assertFalse(
			ProviderCapabilities.supports(ProviderFactory.ANTHROPIC_MESSAGES, ProviderCapability.Video),
		)
	}
}
