package com.inhuman.core.ai.provider

import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.provider.anthropic.AnthropicProvider
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.ai.provider.gemini.GeminiProvider
import com.inhuman.core.ai.provider.openai.OpenAiResponsesProvider

/**
 * Maps a stored profile kind to its wire adapter.
 *
 * The database keeps a string rather than a sealed type so a profile written by
 * an older build remains readable; this is the one place that string is
 * interpreted.
 */
object ProviderFactory {

	const val OPENAI_CHAT = "openai-chat"
	const val ANTHROPIC_MESSAGES = "anthropic-messages"
	const val GOOGLE_GENERATIVE_AI = "google-generative-ai"
	const val OPENAI_RESPONSES = "openai-responses"
	/** One key for chat, image, video, decision and speech. */
	const val QWEN_TOKEN_PLAN_CN = "qwen-token-plan-cn"

	fun create(
		kind: String,
		apiKey: String,
		baseUrl: String,
		id: String,
		reasoningMode: String,
		includeStreamUsage: Boolean,
	): ChatProvider = when (kind) {
		ANTHROPIC_MESSAGES -> AnthropicProvider(
			apiKey = apiKey,
			baseUrl = baseUrl.trimEnd('/'),
			id = id,
		)
		GOOGLE_GENERATIVE_AI -> GeminiProvider(
			apiKey = apiKey,
			baseUrl = baseUrl.trimEnd('/'),
			id = id,
		)
		OPENAI_RESPONSES -> OpenAiResponsesProvider(
			apiKey = apiKey,
			baseUrl = baseUrl.trimEnd('/'),
			id = id,
		)
		// "deepseek" and "openai-compatible" are the legacy spellings of the
		// same Chat Completions adapter.
		else -> DeepSeekProvider(
			apiKey = apiKey,
			baseUrl = baseUrl.trimEnd('/'),
			id = id,
			reasoningMode = reasoningMode,
			includeStreamUsage = includeStreamUsage,
		)
	}
}
