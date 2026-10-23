package com.inhuman

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.engine.ChatEngine
import com.inhuman.core.engine.EngineEvent
import com.inhuman.core.engine.EngineTurn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One real call on Android, using whatever key the device already has.
 *
 * It validates the Android transport/runtime for the shared engine without
 * printing or copying the key. It does not drive the UI; the UI bridge is
 * covered by the demo path and the JVM host tests.
 */
@RunWith(AndroidJUnit4::class)
class EngineAndroidLiveTest {

	@Test
	fun realProviderStreamsThroughEngineOnAndroid() = runBlocking {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val supplied = InstrumentationRegistry.getArguments()
			.getString("DEEPSEEK_LIVE_KEY")
			?.takeIf { it.isNotBlank() }
		val key = supplied ?: runCatching {
			SecureStore(context).get(SecureStore.KEY_DEEPSEEK_API_KEY)
		}.getOrNull()
		assumeTrue("no usable DeepSeek key on this device", !key.isNullOrBlank())

		val events = ChatEngine(DeepSeekProvider(apiKey = key!!)).run(
			EngineTurn(
				model = "deepseek-chat",
				messages = listOf(ChatMessage(Role.User, "Reply with exactly: OK")),
				thinking = false,
				maxRounds = 1,
			),
		).toList()

		assertTrue(
			"expected a text delta, got: ${events.filterIsInstance<EngineEvent.Failed>()}",
			events.any { it is EngineEvent.TextDelta },
		)
		assertTrue(
			"engine failed: ${events.filterIsInstance<EngineEvent.Failed>().joinToString { it.message }}",
			events.none { it is EngineEvent.Failed },
		)
		assertTrue("expected a finish event", events.any { it is EngineEvent.Finished })
	}
}
