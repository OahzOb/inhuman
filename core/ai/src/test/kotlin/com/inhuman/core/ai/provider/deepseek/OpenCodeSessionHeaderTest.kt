package com.inhuman.core.ai.provider.deepseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OpenCode Go rejects a request with no `x-opencode-session` (HTTP 400
 * MissingSessionID), so the value is not cosmetic.
 */
class OpenCodeSessionHeaderTest {

	@Test
	fun `opencode uses the conversation id`() {
		assertEquals(
			"conversation-1",
			openCodeSessionHeader(
				baseUrl = "https://opencode.ai/zen/go/v1",
				userId = "conversation-1",
				profileId = "profile-1",
			),
		)
	}

	@Test
	fun `opencode falls back to the profile id when there is no conversation`() {
		assertEquals(
			"profile-1",
			openCodeSessionHeader(
				baseUrl = "https://opencode.ai/zen/v1",
				userId = null,
				profileId = "profile-1",
			),
		)
	}

	@Test
	fun `other providers get no session header`() {
		assertNull(
			openCodeSessionHeader(
				baseUrl = "https://api.deepseek.com",
				userId = "conversation-1",
				profileId = "profile-1",
			),
		)
	}
}
