package com.inhuman.core.engine

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One real, opt-in call through the engine.
 *
 * Skipped unless `DEEPSEEK_LIVE_KEY` is set. It makes one small completion, not
 * a tool loop, and it is here so the transport + engine boundary is measured
 * against the real API rather than only DemoProvider.
 */
class EngineLiveTest {

	@Test
	fun `real provider streams through the engine`() = runBlocking {
		val key = System.getenv("DEEPSEEK_LIVE_KEY")
		assumeTrue("set DEEPSEEK_LIVE_KEY to run the live engine check", !key.isNullOrBlank())

		val events = ChatEngine(DeepSeekProvider(apiKey = key!!)).run(
			EngineTurn(
				model = "deepseek-chat",
				messages = listOf(ChatMessage(Role.User, "Reply with exactly: OK")),
				thinking = false,
				maxRounds = 1,
			),
		).toList()

		assertTrue(
			"expected at least one text delta",
			events.any { it is EngineEvent.TextDelta },
		)
		assertTrue(
			"expected a clean finish, got: ${events.filterIsInstance<EngineEvent.Failed>()}",
			events.any { it is EngineEvent.Finished },
		)
		assertTrue(
			"engine failed: ${events.filterIsInstance<EngineEvent.Failed>().joinToString { it.message }}",
			events.none { it is EngineEvent.Failed },
		)
	}
}
