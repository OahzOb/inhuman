package com.inhuman.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.ai.provider.deepseek.DeepSeekFetchApi
import com.inhuman.core.ai.provider.deepseek.DeepSeekSearchApi
import com.inhuman.core.ai.provider.exa.ExaFetchApi
import com.inhuman.core.ai.provider.exa.ExaSearchApi
import com.inhuman.core.data.secret.SecureStore
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The keys are independent by construction, and this is the test that keeps
 * them that way: a chat key alone must not register a web tool.
 */
@RunWith(AndroidJUnit4::class)
class WebToolProvidersTest {

	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private val secure = SecureStore(context, "web-test-${UUID.randomUUID()}")

	@Test
	fun aChatKeyAloneDoesNotEnableWebTools() {
		secure.put(SecureStore.KEY_DEEPSEEK_API_KEY, "chat-key")
		secure.put(SecureStore.KEY_WEB_SEARCH_BACKEND, WebToolProviders.DEEPSEEK)
		assertNull(WebToolProviders.search(secure))
	}

	@Test
	fun exaBackendUsesTheExaKey() {
		secure.put(SecureStore.KEY_WEB_SEARCH_BACKEND, WebToolProviders.EXA)
		secure.put(SecureStore.KEY_WEB_FETCH_BACKEND, WebToolProviders.EXA)
		secure.put(SecureStore.KEY_WEB_EXA_API_KEY, "exa-key")
		assertTrue(WebToolProviders.search(secure) is ExaSearchApi)
		assertTrue(WebToolProviders.fetch(secure) is ExaFetchApi)
	}

	@Test
	fun deepSeekBackendUsesTheWebKeyNotTheChatKey() {
		secure.put(SecureStore.KEY_WEB_SEARCH_BACKEND, WebToolProviders.DEEPSEEK)
		secure.put(SecureStore.KEY_WEB_FETCH_BACKEND, WebToolProviders.DEEPSEEK)
		secure.put(SecureStore.KEY_WEB_DEEPSEEK_API_KEY, "web-key")
		assertTrue(WebToolProviders.search(secure) is DeepSeekSearchApi)
		assertTrue(WebToolProviders.fetch(secure) is DeepSeekFetchApi)
	}
}
