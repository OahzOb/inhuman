package com.inhuman.web

import com.inhuman.core.ai.bilibili.BilibiliClient
import com.inhuman.core.ai.bilibili.BilibiliReadService

/**
 * The one anonymous Bilibili client for the process.
 *
 * ## Why one instance
 *
 * The client keeps the anonymous browsing id and the signed-key state, and
 * fetches both lazily. A second instance per conversation would fetch its own
 * pair and double the anonymous request count Bilibili's risk control watches,
 * which is the one resource this feature spends. Sharing it shares no identity:
 * the only state is the public browsing id and a rotating signature key, both
 * anonymous, and nothing is written to disk.
 *
 * A null would mean "not wired" and is not needed: unlike the paid web tools
 * there is no key to be missing. The global switch decides whether the tools
 * exist; this provider always has something to give them if it did.
 */
object BilibiliReadProvider {

	private val client: BilibiliReadService by lazy { BilibiliClient() }

	fun service(): BilibiliReadService = client
}
