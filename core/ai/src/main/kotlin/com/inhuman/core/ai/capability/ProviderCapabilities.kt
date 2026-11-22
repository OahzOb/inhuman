package com.inhuman.core.ai.capability

/** A provider's non-chat capabilities. Chat is still the baseline. */
enum class ProviderCapability {
	Chat,
	Image,
	Video,
	Decision,
	Speech,
}

/**
 * Which capabilities a profile kind can serve.
 *
 * This is intentionally a property of the wire/provider, not of a model: one
 * Qwen Token Plan key serves chat, image, video, decision and speech through
 * different endpoints. Model-level capability filtering belongs to the model
 * catalogue, not here.
 */
object ProviderCapabilities {

	fun of(kind: String): Set<ProviderCapability> = when (kind) {
		"qwen-token-plan",
		"qwen-token-plan-cn" -> ProviderCapability.entries.toSet()
		// DeepSeek's own profile kind serves chat and its standalone speech
		// tools (`/v1/tools/asr`, `/v1/tools/tts`) from the same account and
		// key. Image, video and decision have no DeepSeek endpoint, so they are
		// deliberately absent: a capability the provider cannot serve must not
		// appear in the media pickers.
		"deepseek" -> setOf(ProviderCapability.Chat, ProviderCapability.Speech)
		else -> setOf(ProviderCapability.Chat)
	}

	fun supports(kind: String, capability: ProviderCapability): Boolean =
		capability in of(kind)
}
