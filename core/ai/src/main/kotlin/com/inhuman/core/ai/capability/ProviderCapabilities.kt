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
		else -> setOf(ProviderCapability.Chat)
	}

	fun supports(kind: String, capability: ProviderCapability): Boolean =
		capability in of(kind)
}
