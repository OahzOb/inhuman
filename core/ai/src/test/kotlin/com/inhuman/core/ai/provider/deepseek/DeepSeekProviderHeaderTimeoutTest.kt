package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * A connection that goes silent before the headers is a failure, not a stop.
 *
 * The interceptor unblocks the socket by calling `call.cancel()`, so
 * `onFailure` sees `isCanceled() == true` and the provider used to mistake the
 * timeout for a user stop: the flow closed cleanly, the row was removed for
 * being empty, and the engine had no failure to retry. This pins the exception
 * check that distinguishes the two.
 */
class DeepSeekProviderHeaderTimeoutTest {

	@Test
	fun `a silent connection produces a retryable failure`() = runBlocking {
		val server = ServerSocket(0)
		val accepter = Thread {
			runCatching {
				server.accept().use { Thread.sleep(10_000) }
			}
		}.apply {
			isDaemon = true
			start()
		}
		val client = OkHttpClient.Builder()
			.addInterceptor(HeaderTimeoutInterceptor(1))
			.connectTimeout(1, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.retryOnConnectionFailure(false)
			.build()
		try {
			val provider = DeepSeekProvider(
				apiKey = "test-key",
				baseUrl = "http://127.0.0.1:${server.localPort}",
				client = client,
			)
			val chunks = withTimeout(10_000) {
				provider.stream(
					ChatRequest(
						model = "test-model",
						messages = listOf(ChatMessage(Role.User, "hello")),
					),
				).toList()
			}

			val failure = chunks.filterIsInstance<StreamChunk.Failure>().single()
			assertTrue(
				"unexpected failure: ${failure.message}",
				failure.message.contains("No response headers"),
			)
			assertTrue(
				"the cause is what lets the retry policy see an IOException",
				failure.cause is HeaderTimeoutException,
			)
		} finally {
			server.close()
			accepter.interrupt()
			client.connectionPool.evictAll()
			client.dispatcher.executorService.shutdown()
		}
	}
}
