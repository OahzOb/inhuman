package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The error banner is only useful if its button matches the failure.
 *
 * These strings are shapes the provider actually returned or that the turn
 * loop produces; the classifier is intentionally conservative, because a
 * Retry offered for a permanent configuration error is worse than no button.
 */
class ChatErrorActionTest {

	@Test
	fun contextOverflowOffersCompactAndRetry() {
		assertEquals(
			ChatErrorAction.CompactAndRetry,
			chatErrorAction(
				"HTTP 400 Bad Request: maximum context length is 65536 tokens, " +
					"but the request has 70000",
			),
		)
	}

	@Test
	fun missingKeyOpensProviderSettings() {
		assertEquals(
			ChatErrorAction.OpenProviderSettings,
			chatErrorAction("No API key is configured for DeepSeek."),
		)
		assertEquals(
			ChatErrorAction.OpenProviderSettings,
			chatErrorAction("HTTP 401 Unauthorized: invalid api key"),
		)
	}

	@Test
	fun rateLimitAndNetworkOfferRetry() {
		assertEquals(ChatErrorAction.Retry, chatErrorAction("HTTP 429 Too Many Requests"))
		assertEquals(ChatErrorAction.Retry, chatErrorAction("timeout"))
		assertEquals(ChatErrorAction.Retry, chatErrorAction("Failed to connect to /10.0.2.2"))
	}

	@Test
	fun aHeaderTimeoutOffersRetry() {
		assertEquals(
			ChatErrorAction.Retry,
			chatErrorAction("No response headers within 90s"),
		)
	}

	@Test
	fun ordinaryUiErrorsGetNoAction() {
		assertNull(chatErrorAction("The model in use cannot read images."))
		assertNull(chatErrorAction("There are not enough earlier complete turns to compact."))
		assertNull(chatErrorAction(null))
	}
}
