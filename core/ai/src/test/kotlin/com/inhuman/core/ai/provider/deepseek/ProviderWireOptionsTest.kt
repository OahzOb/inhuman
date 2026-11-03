package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderWireOptionsTest {

	private val request = ChatRequest(
		model = "some-model",
		messages = listOf(
			ChatMessage(role = Role.User, content = "hi"),
			ChatMessage(role = Role.Assistant, content = "hello", reasoning = "because"),
		),
		thinking = true,
		reasoningEffort = "high",
	)

	@Test
	fun deepSeekModeSendsItsReasoningFields() {
		val json = DeepSeekRequest.build(request, reasoningMode = "deepseek")
		assertTrue(json.containsKey("thinking"))
		assertTrue(json.containsKey("reasoning_effort"))
		assertTrue(json.containsKey("stream_options"))
	}

	@Test
	fun noneModeOmitsEveryReasoningFieldAndStreamOptions() {
		val json = DeepSeekRequest.build(
			request,
			reasoningMode = "none",
			includeStreamUsage = false,
		)
		assertFalse(json.containsKey("thinking"))
		assertFalse(json.containsKey("reasoning_effort"))
		assertFalse(json.containsKey("stream_options"))
		assertFalse(json.toString().contains("reasoning_content"))
	}

	@Test
	fun effortModeSendsEffortButNotThinking() {
		val json = DeepSeekRequest.build(request, reasoningMode = "effort")
		assertFalse(json.containsKey("thinking"))
		assertTrue(json.containsKey("reasoning_effort"))
		assertFalse(json.toString().contains("reasoning_content"))
	}

	@Test
	fun openCodeModeSendsEffortAndReplayWithoutDeepSeekFields() {
		// The gateway's DeepSeek models name the reasoning field
		// `reasoning_content` in their model metadata, but the gateway is not
		// DeepSeek's API: `thinking` and `user_id` are guesses it does not need.
		val withUser = request.copy(userId = "conversation-1")

		val json = DeepSeekRequest.build(withUser, reasoningMode = "opencode")

		assertTrue(json.containsKey("reasoning_effort"))
		assertTrue(json.toString().contains("reasoning_content"))
		assertFalse(json.containsKey("thinking"))
		assertFalse(json.containsKey("user_id"))
	}
}
