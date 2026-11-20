package com.inhuman.web

import com.inhuman.core.ai.bilibili.BilibiliClient
import com.inhuman.core.ai.bilibili.BilibiliCredentials
import com.inhuman.core.ai.bilibili.BilibiliReadService

/**
 * The one anonymous-or-linked Bilibili client for the process.
 *
 * ## Why one instance
 *
 * The client keeps the anonymous browsing id and the signed-key state, and
 * fetches both lazily. A second instance per conversation would fetch its own
 * pair and double the anonymous request count Bilibili's risk control watches.
 * Sharing it shares no account data: the credentials are read from a supplier
 * on every request, so whichever conversation asks gets the current setting and
 * unlinking takes effect immediately.
 *
 * The supplier is installed at the same wiring point that creates a
 * `ChatViewModel`; there is exactly one app-wide store behind it.
 */
object BilibiliReadProvider {

	private var credentials: () -> BilibiliCredentials? = { null }

	private val client: BilibiliReadService by lazy {
		BilibiliClient(credentials = { credentials() })
	}

	fun service(source: () -> BilibiliCredentials?): BilibiliReadService {
		credentials = source
		return client
	}
}
