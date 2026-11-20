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
	/**
	 * The read-only account session the app linked, or null when anonymous.
	 *
	 * Read per request rather than copied: unlinking the account in Settings
	 * must take effect on the next call, and a copied value would keep sending
	 * a cookie the user just revoked.
	 */
	private val credentials: () -> BilibiliCredentials? = { null },
) : BilibiliReadService {

	override val loggedIn: Boolean
		get() = credentials()?.isUsable == true

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
		page: Int,
	): BilibiliComments {
		// Bilibili documents page 2 and later as requiring a signed-in caller.
		// Saying so here is honest; letting the endpoint answer -412 would make
		// a consent question look like a network failure.
		if (page > 1 && !loggedIn) {
			throw BilibiliApiException(
				-101,
				"comment page $page needs a linked Bilibili account",
			)
		}
		val aid = synchronized(aidCache) { aidCache[bvid] } ?: video(bvid).aid
		var next = "0"
		var parsed = BilibiliComments(emptyList(), emptyList())
		for (index in 1..page.coerceAtLeast(1)) {
			val root = plainGet(
				REPLY_URL,
				mapOf(
					"oid" to aid.toString(),
					"type" to "1",
					"mode" to sort.mode.toString(),
					"ps" to limit.coerceIn(1, MAX_COMMENT_PAGE).toString(),
					"next" to next,
				),
			)
			if (index == page) {
				parsed = BilibiliParsers.parseComments(root)
				break
			}
			next = BilibiliParsers.parseNextCursor(root)?.toString()
				?: throw BilibiliApiException(
					BilibiliApiException.TRANSPORT,
					"there is no comment page $page on this video",
				)
		}
		return parsed
	}

	override suspend fun article(cvId: Long): BilibiliArticle =
		BilibiliParsers.parseArticle(plainGet(ARTICLE_URL, mapOf("id" to cvId.toString())))

	override suspend fun transcript(
		bvid: String,
		page: Int,
		prefer: BilibiliTranscriptPreference,
	): BilibiliTranscript? {
		val info = video(bvid)
		val part = info.pages.firstOrNull { it.page == page } ?: info.pages.getOrNull(page - 1)
		val cid = part?.cid?.takeIf { it > 0 } ?: info.cid.takeIf { it > 0 } ?: return null
		val order = when (prefer) {
			BilibiliTranscriptPreference.TEXT -> listOf(
				BilibiliTranscriptSource.SUBTITLE,
				BilibiliTranscriptSource.AI_SUMMARY,
			)

			BilibiliTranscriptPreference.SUMMARY -> listOf(
				BilibiliTranscriptSource.AI_SUMMARY,
				BilibiliTranscriptSource.SUBTITLE,
			)
		}
		for (source in order) {
			val answer = when (source) {
				BilibiliTranscriptSource.SUBTITLE ->
					subtitleTranscript(info.aid, cid)

				BilibiliTranscriptSource.AI_SUMMARY ->
					aiSummaryTranscript(bvid, info.aid, cid, info.ownerMid)
			}
			if (answer != null) return answer
		}
		return null
	}

	/**
	 * The subtitle text of one part, or null when the endpoint lists no track.
	 *
	 * The player endpoint needs both the WBI signature and the anti-spider
	 * `dm_*` parameters; without the latter a logged-in caller still gets
	 * `-352`, which reads like a permission problem but is a request-shape one.
	 */
	private suspend fun subtitleTranscript(
		aid: Long,
		cid: Long,
	): BilibiliTranscript? {
		val root = signedGet(
			PLAYER_URL,
			mapOf(
				"aid" to aid.toString(),
				"cid" to cid.toString(),
				"isGaiaAvoided" to "false",
				"web_location" to "1315873",
			),
			dm = true,
		)
		val tracks = BilibiliParsers.parseSubtitleTracks(root)
		if (tracks.isEmpty()) return null
		val track = tracks.minByOrNull { subtitleRank(it.language) } ?: tracks.first()
		val lines = downloadSubtitleLines(track.url)
		if (lines.isEmpty()) return null
		return BilibiliTranscript(
			source = BilibiliTranscriptSource.SUBTITLE,
			text = lines.joinToString("\n").take(MAX_TRANSCRIPT_CHARS),
			language = track.label.ifBlank { track.language },
			aiGenerated = track.language.startsWith("ai-") || track.type == 1,
			segments = lines.size,
		)
	}

	/** Bilibili's own AI summary of one part, or null when it has none. */
	private suspend fun aiSummaryTranscript(
		bvid: String,
		aid: Long,
		cid: Long,
		upMid: Long,
	): BilibiliTranscript? {
		if (!loggedIn) return null
		val root = signedGet(
			CONCLUSION_URL,
			mapOf(
				"aid" to aid.toString(),
				"bvid" to bvid,
				"cid" to cid.toString(),
				"up_mid" to upMid.toString(),
				"web_location" to "333.788",
			),
		)
		val parsed = BilibiliParsers.parseAiSummary(root) ?: return null
		val text = buildString {
			appendLine(parsed.summary)
			if (parsed.outline.isNotEmpty()) {
				appendLine()
				appendLine("Outline:")
				for (segment in parsed.outline) {
					appendLine("- ${timestamp(segment.timestampSeconds)} ${segment.title}")
					for (point in segment.points) {
						appendLine("  - ${timestamp(point.timestampSeconds)} ${point.content}")
					}
				}
			}
		}.trim().take(MAX_SUMMARY_CHARS)
		return BilibiliTranscript(
			source = BilibiliTranscriptSource.AI_SUMMARY,
			text = text,
			segments = parsed.outline.size,
		)
	}

	private suspend fun downloadSubtitleLines(url: String): List<String> {
		val full = if (url.startsWith("//")) "https:$url" else url
		val uri = runCatching { java.net.URI(full) }.getOrNull()
			?: throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"the subtitle URL was unreadable",
			)
		val host = uri.host.orEmpty()
		// The subtitle arrives from a CDN. It is fetched without cookies, and
		// only from Bilibili's own hosts: an endpoint answer is not a licence to
		// make the app fetch an arbitrary address.
		val allowedHost = host == "hdslb.com" || host.endsWith(".hdslb.com") ||
			host == "bilibili.com" || host.endsWith(".bilibili.com")
		if (uri.scheme != "https" || !allowedHost) {
			throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"the subtitle URL pointed outside Bilibili's hosts",
			)
		}
		val request = Request.Builder()
			.url(full)
			.get()
			.header("User-Agent", USER_AGENT)
			.header("Referer", REFERER)
			.header("Accept", ACCEPT)
			.build()
		return withContext(Dispatchers.IO) {
			try {
				http.newCall(request).execute().use { response ->
					val body = response.body.string()
					if (!response.isSuccessful) {
						throw BilibiliApiException(
							BilibiliApiException.TRANSPORT,
							"subtitle download HTTP ${response.code}",
						)
					}
					val root = runCatching { json.parseToJsonElement(body).jsonObject }
						.getOrElse {
							throw BilibiliApiException(
								BilibiliApiException.TRANSPORT,
								"the subtitle file was not JSON",
							)
						}
					BilibiliParsers.parseSubtitleBody(root)
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

	/** Prefer a Chinese track; within Chinese prefer uploaded text to AI text. */
	private fun subtitleRank(language: String): Int = when {
		language == "zh-CN" -> 0
		language == "zh" -> 1
		language == "ai-zh" -> 2
		language.startsWith("ai-zh") -> 3
		language.startsWith("zh") -> 4
		language.startsWith("en") -> 5
		else -> 6
	}

	private fun timestamp(seconds: Long): String {
		if (seconds <= 0) return "0:00"
		val hours = seconds / 3600
		val minutes = (seconds % 3600) / 60
		val rest = seconds % 60
		return if (hours > 0) {
			String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
		} else {
			String.format(java.util.Locale.ROOT, "%d:%02d", minutes, rest)
		}
	}

	/**
	 * A request whose signature is required. The mixin key may have rotated;
	 * one `-403` is answered by refetching it and trying the same call again.
	 */
	private suspend fun signedGet(
		url: String,
		params: Map<String, String>,
		dm: Boolean = false,
	): JsonObject {
		var attempt = 0
		while (true) {
			val active = ensureSession(refreshMixin = attempt > 0)
			val base = if (dm) params + dmParams() else params
			val signed = WbiSigner.sign(base, active.mixinKey, now() / 1000)
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
		val cookie = buildString {
			if (session != null && session.buvid3.isNotBlank()) {
				append("buvid3=${session.buvid3}; buvid4=${session.buvid4}")
			}
			val account = credentials()
			if (account != null && account.isUsable) {
				if (isNotEmpty()) append("; ")
				append("SESSDATA=${account.sessdata}; DedeUserID=${account.dedeUserId}")
			}
		}
		if (cookie.isNotBlank()) builder.header("Cookie", cookie)
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

	/** Space/player endpoints expect a few `dm_*` anti-spider fields to exist. */
	private fun dmParams(): Map<String, String> {
		val alphabet = "ABCDEFGHIJK"
		fun pick() = (1..2).map { alphabet.random() }.joinToString("")
		return mapOf(
			"dm_img_list" to "[]",
			"dm_img_str" to pick(),
			"dm_cover_img_str" to pick(),
			"dm_img_inter" to """{"ds":[],"wh":[0,0,0],"of":[0,0,0]}""",
		)
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
		private const val PLAYER_URL = "https://api.bilibili.com/x/player/wbi/v2"
		private const val CONCLUSION_URL =
			"https://api.bilibili.com/x/web-interface/view/conclusion/get"
		private const val SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi"
		private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

		private const val USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
		private const val REFERER = "https://www.bilibili.com/"
		private const val ACCEPT = "application/json, text/plain, */*"

		/** The comment endpoint's own ceiling for one page. */
		private const val MAX_COMMENT_PAGE = 30

		/** Two thousand characters of subtitle per 1k tokens is not a promise. */
		private const val MAX_TRANSCRIPT_CHARS = 20_000

		/** The AI summary plus outline is a digest; it should stay short. */
		private const val MAX_SUMMARY_CHARS = 8_000

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
