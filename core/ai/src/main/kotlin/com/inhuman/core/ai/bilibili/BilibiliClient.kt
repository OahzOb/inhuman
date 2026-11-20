package com.inhuman.core.ai.bilibili

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The anonymous Bilibili client the read tools sit on.
 *
 * ## What it deliberately does not hold
 *
 * No Bilibili account, no cookie jar and no stored credential. The only cookie
 * is the public browsing id (`buvid3`/`buvid4`) that the anonymous web hands
 * out; it lives in memory for the process and is never written anywhere. That
 * is what makes "no account required" true rather than a promise: there is
 * nothing to leak, because there is nothing stored.
 *
 * ## Why the plumbing looks like this
 *
 * The search endpoint needs a signature whose key comes from `/nav`, which
 * answers `-101` for an anonymous caller while still containing the key. So
 * this client fetches the browsing id once, fetches the mixin key once, and
 * refreshes the key on `-403` (a stale key is indistinguishable from a broken
 * signature at the call site). `-509` (rate limited) gets one retry after a
 * short pause; `-352`/`-412` (risk control) do not, because retrying is what
 * caused them.
 *
 * Requests are paced. An anonymous phone is exactly the caller Bilibili's risk
 * control watches, and a tool loop can otherwise make several calls back to
 * back. The pause is not a user-visible setting: it is the cost of using an
 * unofficial endpoint set.
 */
class BilibiliClient(
	private val http: OkHttpClient = defaultClient(),
	private val now: () -> Long = System::currentTimeMillis,
) : BilibiliReadService {

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	private val sessionMutex = Mutex()
	private val paceMutex = Mutex()
	private var session: Session? = null
	private var lastRequestAt = 0L

	/** Video id to its `aid`, needed by the comment endpoint. Tiny and bounded. */
	private val aidCache = LinkedHashMap<String, Long>()

	override suspend fun search(
		keyword: String,
		kind: BilibiliSearchKind,
		order: String,
		page: Int,
		limit: Int,
	): List<BilibiliSearchHit> {
		val params = buildMap {
			put("keyword", keyword)
			put("search_type", kind.apiValue)
			put("page", page.toString())
			put("page_size", limit.toString())
			if (order.isNotBlank()) put("order", order)
		}
		return BilibiliParsers.parseSearch(signedGet(SEARCH_URL, params))
	}

	override suspend fun video(bvid: String): BilibiliVideoInfo {
		val video = BilibiliParsers.parseVideo(plainGet(VIEW_URL, mapOf("bvid" to bvid)))
		rememberAid(video.bvid, video.aid)
		return video
	}

	override suspend fun comments(
		bvid: String,
		sort: BilibiliCommentSort,
		limit: Int,
	): BilibiliComments {
		val aid = synchronized(aidCache) { aidCache[bvid] } ?: video(bvid).aid
		val params = mapOf(
			"oid" to aid.toString(),
			"type" to "1",
			"mode" to sort.mode.toString(),
			"ps" to limit.coerceIn(1, MAX_COMMENT_PAGE).toString(),
			"next" to "0",
		)
		return BilibiliParsers.parseComments(plainGet(REPLY_URL, params))
	}

	override suspend fun article(cvId: Long): BilibiliArticle =
		BilibiliParsers.parseArticle(plainGet(ARTICLE_URL, mapOf("id" to cvId.toString())))

	/**
	 * A request whose signature is required. The mixin key may have rotated;
	 * one `-403` is answered by refetching it and trying the same call again.
	 */
	private suspend fun signedGet(url: String, params: Map<String, String>): JsonObject {
		var attempt = 0
		while (true) {
			val active = ensureSession(refreshMixin = attempt > 0)
			val signed = WbiSigner.sign(params, active.mixinKey, now() / 1000)
			val root = executeGet(url, signed, active)
			val code = root.code()
			if (code == 0) return root
			val message = root.message()
			when {
				code == -403 && attempt < 1 -> attempt++
				code == -509 && attempt < MAX_RATE_LIMIT_RETRIES -> {
					attempt++
					delay(RATE_LIMIT_PAUSE_MS * attempt)
				}
				else -> throw BilibiliApiException(code, message)
			}
		}
	}

	/** A public read that needs no signature. One pause retry for `-509`. */
	private suspend fun plainGet(url: String, params: Map<String, String>): JsonObject {
		val active = ensureSession()
		var attempt = 0
		while (true) {
			val root = executeGet(url, params, active)
			val code = root.code()
			if (code == 0) return root
			if (code == -509 && attempt < MAX_RATE_LIMIT_RETRIES) {
				attempt++
				delay(RATE_LIMIT_PAUSE_MS * attempt)
				continue
			}
			throw BilibiliApiException(code, root.message())
		}
	}

	private suspend fun ensureSession(refreshMixin: Boolean = false): Session = sessionMutex.withLock {
		val current = session
		if (
			current != null && !refreshMixin &&
			now() - current.fetchedAtMillis < SESSION_TTL_MS
		) {
			return@withLock current
		}
		val buvid = if (current == null) fetchBuvid() else current.buvid3 to current.buvid4
		val mixin = fetchMixinKey(buvid)
		Session(
			buvid3 = buvid.first,
			buvid4 = buvid.second,
			mixinKey = mixin,
			fetchedAtMillis = now(),
		).also { session = it }
	}

	private suspend fun fetchBuvid(): Pair<String, String> {
		val root = executeGet(SPI_URL, emptyMap(), session = null)
		val code = root.code()
		if (code != 0) throw BilibiliApiException(code, root.message())
		val data = root.objectOrNull("data") ?: throw BilibiliApiException(
			BilibiliApiException.TRANSPORT,
			"the browsing-id endpoint returned no data",
		)
		val buvid3 = data.stringOrEmpty("b_3")
		val buvid4 = data.stringOrEmpty("b_4")
		if (buvid3.isBlank() || buvid4.isBlank()) {
			throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"the browsing-id endpoint returned empty ids",
			)
		}
		return buvid3 to buvid4
	}

	private suspend fun fetchMixinKey(buvid: Pair<String, String>): String {
		val root = executeGet(NAV_URL, emptyMap(), Session(buvid.first, buvid.second, "", 0))
		// The nav endpoint answers -101 ("not logged in") for anonymous callers
		// and still carries the key. Only the key is read here; the code is
		// irrelevant by design, which is also why it is not checked.
		val wbi = root.objectOrNull("data")?.objectOrNull("wbi_img")
			?: throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"the nav endpoint returned no wbi_img keys",
			)
		val img = wbi.stringOrEmpty("img_url")
		val sub = wbi.stringOrEmpty("sub_url")
		if (img.isBlank() || sub.isBlank()) {
			throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"the nav endpoint returned empty wbi_img keys",
			)
		}
		return WbiSigner.mixinKey(img, sub)
	}

	private suspend fun executeGet(
		url: String,
		params: Map<String, String>,
		session: Session?,
	): JsonObject {
		pace()
		val fullUrl = if (params.isEmpty()) url else "$url?${WbiSigner.queryString(params)}"
		val builder = Request.Builder()
			.url(fullUrl)
			.get()
			.header("User-Agent", USER_AGENT)
			.header("Referer", REFERER)
			.header("Accept", ACCEPT)
		if (session != null && session.buvid3.isNotBlank()) {
			builder.header("Cookie", "buvid3=${session.buvid3}; buvid4=${session.buvid4}")
		}
		return withContext(Dispatchers.IO) {
			try {
				http.newCall(builder.build()).execute().use { response ->
					val body = response.body.string()
					if (!response.isSuccessful) {
						throw BilibiliApiException(
							BilibiliApiException.TRANSPORT,
							"HTTP ${response.code}",
						)
					}
					try {
						json.parseToJsonElement(body).jsonObject
					} catch (failure: Exception) {
						throw BilibiliApiException(
							BilibiliApiException.TRANSPORT,
							"the response was not JSON",
							failure,
						)
					}
				}
			} catch (failure: IOException) {
				throw BilibiliApiException(
					BilibiliApiException.TRANSPORT,
					failure.message ?: failure.javaClass.simpleName,
					failure,
				)
			}
		}
	}

	/** Remember a video's aid so `bili_comments` can skip the second view call. */
	private fun rememberAid(bvid: String, aid: Long) {
		if (aid <= 0) return
		synchronized(aidCache) {
			if (aidCache.size >= 16) aidCache.clear()
			aidCache[bvid] = aid
		}
	}

	/** Space calls apart; the lock also makes the interval true under concurrency. */
	private suspend fun pace() = paceMutex.withLock {
		val elapsed = now() - lastRequestAt
		if (elapsed in 0 until MIN_REQUEST_INTERVAL_MS) {
			delay(MIN_REQUEST_INTERVAL_MS - elapsed)
		}
		lastRequestAt = now()
	}

	private data class Session(
		val buvid3: String,
		val buvid4: String,
		val mixinKey: String,
		val fetchedAtMillis: Long,
	)

	companion object {
		private const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/wbi/search/type"
		private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
		private const val REPLY_URL = "https://api.bilibili.com/x/v2/reply/main"
		private const val ARTICLE_URL = "https://api.bilibili.com/x/article/view"
		private const val SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi"
		private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

		private const val USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
		private const val REFERER = "https://www.bilibili.com/"
		private const val ACCEPT = "application/json, text/plain, */*"

		/** The comment endpoint's own ceiling for one page. */
		private const val MAX_COMMENT_PAGE = 30

		/** Long enough to be polite, short enough not to feel like a hang. */
		private const val MIN_REQUEST_INTERVAL_MS = 700L

		/** The nav keys rotate daily; a stale key answers -403, not silence. */
		private const val SESSION_TTL_MS = 30 * 60 * 1000L

		private const val RATE_LIMIT_PAUSE_MS = 1_500L

		/** Two pauses are about 4.5 seconds; longer than that is a hung tool. */
		private const val MAX_RATE_LIMIT_RETRIES = 2

		private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS)
			.callTimeout(25, TimeUnit.SECONDS)
			.retryOnConnectionFailure(true)
			.build()
	}

	private fun JsonObject.code(): Int =
		(this["code"] as? JsonPrimitive)?.intOrNull ?: 0

	private fun JsonObject.message(): String =
		stringOrEmpty("message").ifBlank { stringOrEmpty("msg") }

	private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

	private fun JsonObject.stringOrEmpty(name: String): String =
		(this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
