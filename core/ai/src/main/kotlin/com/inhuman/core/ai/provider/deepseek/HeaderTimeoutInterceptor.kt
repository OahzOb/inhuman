package com.inhuman.core.ai.provider.deepseek

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cancels a call when the server has not produced response headers in time.
 *
 * The chat client's read timeout is eleven minutes on purpose: DeepSeek may
 * reason for a long time before the first token, and a conventional short read
 * timeout kills legitimate slow answers. But that timeout also covers the
 * window *before* the first byte, where an established connection can simply go
 * silent -- the 2026-10-25 log had two chat requests with no `HTTP 200` line at
 * all, and the turn spinner sat there until the app was restarted.
 *
 * This interceptor bounds only the wait for headers, which normally takes
 * hundreds of milliseconds. Once `proceed` returns, the body is free to stream
 * for the full eleven-minute read timeout.
 */
internal class HeaderTimeoutInterceptor(
	private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : Interceptor {

	private val timer = Timer("http-header-timeout", true)

	override fun intercept(chain: Interceptor.Chain): Response {
		val call = chain.call()
		val timedOut = AtomicBoolean(false)
		val timeout = object : TimerTask() {
			override fun run() {
				timedOut.set(true)
				call.cancel()
			}
		}
		timer.schedule(timeout, TimeUnit.SECONDS.toMillis(timeoutSeconds))
		return try {
			chain.proceed(chain.request())
		} catch (failure: IOException) {
			if (timedOut.get()) throw HeaderTimeoutException(timeoutSeconds)
			throw failure
		} finally {
			timeout.cancel()
		}
	}

	companion object {
		/**
		 * Headers are not the first token. Ninety seconds is generous for a
		 * header, while still ending a dead connection long before eleven
		 * minutes.
		 */
		const val DEFAULT_TIMEOUT_SECONDS = 90L
	}
}

/** The call was cancelled because the server sent no response headers. */
internal class HeaderTimeoutException(timeoutSeconds: Long) : IOException(
	"No response headers within ${timeoutSeconds}s",
)
