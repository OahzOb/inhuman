package com.inhuman.core.ai.provider

/**
 * A provider whose endpoint, wire API and authentication are known in advance.
 *
 * Known providers ask for one thing: the API key. Their base URL and kind are
 * part of the catalog, not a user decision. "Custom" provider profiles remain
 * available for anything not listed here.
 *
 * `models` is deliberately empty for most entries: those endpoints expose
 * `/models`, and a hardcoded list would go stale. A profile can always be
 * customised to add manual model IDs if an endpoint does not.
 */
data class KnownProvider(
	val id: String,
	val name: String,
	val kind: String,
	val baseUrl: String,
	val models: List<String> = emptyList(),
)

object ProviderCatalog {

	val known: List<KnownProvider> = listOf(
		KnownProvider(
			id = "anthropic",
			name = "Anthropic",
			kind = ProviderFactory.ANTHROPIC_MESSAGES,
			baseUrl = "https://api.anthropic.com",
		),
		KnownProvider(
			id = "google",
			name = "Google Gemini",
			kind = ProviderFactory.GOOGLE_GENERATIVE_AI,
			baseUrl = "https://generativelanguage.googleapis.com/v1beta",
		),
		KnownProvider(
			id = "openai",
			name = "OpenAI",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://api.openai.com/v1",
		),
		KnownProvider(
			id = "openai-responses",
			name = "OpenAI (Responses API)",
			kind = ProviderFactory.OPENAI_RESPONSES,
			baseUrl = "https://api.openai.com/v1",
		),
		// OpenCode's two gateways are the same OpenAI-compatible wire API with
		// different base paths: Zen is pay-as-you-go, Go is the subscription.
		// Both expose `/models`, so the model list is fetched rather than
		// hardcoded and a new model appears without an app release.
		KnownProvider(
			id = "opencode-zen",
			name = "OpenCode Zen",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://opencode.ai/zen/v1",
		),
		KnownProvider(
			id = "opencode-go",
			name = "OpenCode Go",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://opencode.ai/zen/go/v1",
		),
		KnownProvider(
			id = "zai",
			name = "Z.AI Coding Plan",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://api.z.ai/api/coding/paas/v4",
		),
		KnownProvider(
			id = "zai-cn",
			name = "Z.AI Coding Plan (China)",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
		),
		KnownProvider(
			id = "qwen-token-plan",
			name = "Qwen Token Plan",
			kind = ProviderFactory.QWEN_TOKEN_PLAN_CN,
			baseUrl = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
		),
		KnownProvider(
			id = "qwen-token-plan-cn",
			name = "Qwen Token Plan (China)",
			kind = ProviderFactory.QWEN_TOKEN_PLAN_CN,
			baseUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
		),
		KnownProvider(
			id = "xiaomi-mimo",
			name = "Xiaomi MiMo",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://api.xiaomimimo.com/v1",
		),
		KnownProvider(
			id = "xiaomi-token-plan-cn",
			name = "Xiaomi Token Plan (China)",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
		),
		KnownProvider(
			id = "kimi-coding",
			name = "Kimi For Coding",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://api.kimi.com/coding/v1",
		),
		KnownProvider(
			id = "minimax",
			name = "MiniMax",
			kind = ProviderFactory.ANTHROPIC_MESSAGES,
			baseUrl = "https://api.minimax.io/anthropic",
		),
		KnownProvider(
			id = "minimax-cn",
			name = "MiniMax (China)",
			kind = ProviderFactory.ANTHROPIC_MESSAGES,
			baseUrl = "https://api.minimaxi.com/anthropic",
		),
		KnownProvider(
			id = "openrouter",
			name = "OpenRouter",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://openrouter.ai/api/v1",
		),
		KnownProvider(
			id = "groq",
			name = "Groq",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://api.groq.com/openai/v1",
		),
		KnownProvider(
			id = "mistral",
			name = "Mistral",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://api.mistral.ai/v1",
		),
		KnownProvider(
			id = "cerebras",
			name = "Cerebras",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://api.cerebras.ai/v1",
		),
		KnownProvider(
			id = "xai",
			name = "xAI",
			kind = ProviderFactory.OPENAI_CHAT,
			baseUrl = "https://api.x.ai/v1",
		),
	)

	fun byId(id: String): KnownProvider? = known.firstOrNull { it.id == id }
}
