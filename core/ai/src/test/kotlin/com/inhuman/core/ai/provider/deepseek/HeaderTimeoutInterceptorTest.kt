package com.inhuman.core.ai.provider.deepseek

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * A server that accepts the connection and then says nothing is the exact shape
 * of the 2026-10-25 spinner: TCP is up, no HTTP headers arrive, and the normal
 * read timeout is eleven minutes away.
 */
class HeaderTimeoutInterceptorTest {

	@Test
	fun `a server that never sends headers is cancelled`() {
		val server = ServerSocket(0)
		val accepter = Thread {
			runCatching {
				server.accept().use {
					Thread.sleep(10_000)
				}
			}
		}.apply {
			isDaemon = true
			start()
		}
		val client = OkHttpClient.Builder()
			.addInterceptor(HeaderTimeoutInterceptor(1))
			.connectTimeout(1, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.build()

		val start = System.nanoTime()
		try {
			client.newCall(
				Request.Builder()
					.url("http://127.0.0.1:${server.localPort}/")
					.build(),
			).execute().close()
			fail("expected the header timeout to cancel the call")
		} catch (expected: IOException) {
			assertTrue(
				"unexpected exception: ${expected.message}",
				expected.message.orEmpty().contains("No response headers"),
			)
			val elapsedMs = (System.nanoTime() - start) / 1_000_000
			assertTrue("took ${elapsedMs}ms", elapsedMs < 5_000)
		} finally {
			server.close()
			accepter.interrupt()
			client.connectionPool.evictAll()
			client.dispatcher.executorService.shutdown()
		}
	}
}
