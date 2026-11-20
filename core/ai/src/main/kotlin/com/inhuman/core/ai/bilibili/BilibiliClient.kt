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
 * The anonymous (or read-only linked) Bilibili client the read tools sit on.
 *
 * ## What it deliberately does not hold
 *
 * No Bilibili account cookie jar and no stored credential beyond the optional
 * read-only session (`SESSDATA` + `DedeUserID`). The only other cookie is the
 * public browsing id (`buvid3`/`buvid4`) that the anonymous web hands out; it
 * lives in memory for the process and is never written anywhere. That is what
 * makes "no account required" true rather than a promise: there is nothing to
 * leak, because there is nothing stored.
 *
 * ## Why the plumbing looks like this
 *
 * The signed endpoints need a key whose source is `/nav`, which answers `-101`
 * for an anonymous caller while still containing the key. So this client fetches
 * the browsing id once, fetches the mixin key once, and refreshes the key on
 * `-403` (a stale key is indistinguishable from a broken signature at the call
 * site). `-509` (rate limited) gets one retry after a short pause;
 * `-352`/`-412` (risk control) do not, because retrying is what caused them.
 *
 * The space endpoints additionally want a few `dm_*` "browser fingerprint"
 * fields to exist; without them they answer `-352`, which reads like a
 * permission problem but is a request-shape one. That is also why they are
 * signed: an unsigned call is refused before the parameters are even read.
 *
 * Requests are paced. An anonymous phone is exactly the caller Bilibili's risk
 * control watches, and a tool loop can otherwise make several calls back to
 * back. The pause is not a user-visible setting: it is the cost of using an
 * unofficial endpoint set. This is also why every multi-request read here is
 * bounded: `bili_up` is four calls, `bili_danmaku` at most five, and no method
 * loops without a ceiling.
 *
 * ## Why a small cache
 *
 * The tools overlap: `bili_comments`, `bili_transcript` and `bili_danmaku` all
 * need the video's `aid`/`cid`, and the model routinely reads the details before
 * asking for text. One in-memory metadata entry per BV id, for a few minutes,
 * turns that into one request without caching page content. It is bounded and
 * process-local, so it cannot become a store of Bilibili material.
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

	/** A few seconds' metadata reuse; see the class comment. */
	private val videoCache = LinkedHashMap<String, CachedVideo>()

	override suspend fun search(
		keyword: String,
		kind: BilibiliSearchKind,
		order: String,
		page: Int,
		limit: Int,
		filters: BilibiliSearchFilters,
	): List<BilibiliSearchHit> {
		val params = buildMap {
			put("keyword", keyword)
			put("search_type", kind.apiValue)
			put("page", page.toString())
			put("page_size", limit.toString())
			if (order.isNotBlank()) put("order", order)
			filters.duration?.let { put("duration", it.apiValue.toString()) }
			if (filters.publishedAfterSeconds > 0) {
				put("pubtime_begin_s", filters.publishedAfterSeconds.toString())
			}
			if (filters.publishedBeforeSeconds > 0) {
				put("pubtime_end_s", filters.publishedBeforeSeconds.toString())
			}
			if (filters.partitionId > 0) put("tids", filters.partitionId.toString())
		}
		return BilibiliParsers.parseSearch(signedGet(SEARCH_URL, params))
	}

	override suspend fun video(bvid: String): BilibiliVideoInfo {
		synchronized(videoCache) {
			val cached = videoCache[bvid]
			if (cached != null && now() - cached.atMillis < VIDEO_CACHE_TTL_MS) {
				return cached.info
			}
		}
		val video = BilibiliParsers.parseVideo(plainGet(VIEW_URL, mapOf("bvid" to bvid)))
		synchronized(videoCache) {
			if (videoCache.size >= MAX_CACHED_VIDEOS) {
				videoCache.remove(videoCache.keys.first())
			}
			videoCache[video.bvid] = CachedVideo(video, now())
		}
		return video
	}

	override suspend fun related(bvid: String, limit: Int): List<BilibiliVideoSummary> =
		BilibiliParsers.parseRelated(plainGet(RELATED_URL, mapOf("bvid" to bvid)))
			.take(limit)

	override suspend fun comments(
		bvid: String,
		sort: BilibiliCommentSort,
		limit: Int,
		page: Int,
		root: Long,
	): BilibiliComments {
		val aid = video(bvid).aid.takeIf { it > 0 } ?: throw BilibiliApiException(
			BilibiliApiException.TRANSPORT,
			"the video id $bvid did not resolve to an aid",
		)
		if (root > 0) {
			// The reply thread endpoint is the same public reader with a root
			// comment; it needs no login and no signature.
			return BilibiliParsers.parseComments(
				plainGet(
					REPLY_THREAD_URL,
					mapOf(
						"oid" to aid.toString(),
						"type" to "1",
						"root" to root.toString(),
						"ps" to limit.coerceIn(1, MAX_COMMENT_PAGE).toString(),
						"pn" to "1",
					),
				),
			)
		}
		// Bilibili documents page 2 and later as requiring a signed-in caller
		// (and it answers -412, which is indistinguishable from risk control).
		// Saying so here is honest; letting the endpoint answer -412 would make
		// a consent question look like a network failure.
		if (page > 1 && !loggedIn) {
			throw BilibiliApiException(
				-101,
				"comment page $page needs a linked Bilibili account",
			)
		}
		return commentPage(type = 1, oid = aid, sort = sort, limit = limit, page = page)
	}

	override suspend fun article(cvId: Long): BilibiliArticle =
		BilibiliParsers.parseArticle(plainGet(ARTICLE_URL, mapOf("id" to cvId.toString())))

	override suspend fun articleComments(
		cvId: Long,
		sort: BilibiliCommentSort,
		limit: Int,
		page: Int,
	): BilibiliComments =
		// Articles use the same reply surface with `type=12` and the cv id as the
		// oid. The endpoint answered `code 0` with its replies both through the
		// legacy and the `main` path; the `main` path is used because it is the
		// one that supports a cursor.
		commentPage(type = 12, oid = cvId, sort = sort, limit = limit, page = page)

	override suspend fun transcript(
		bvid: String,
		page: Int,
		prefer: BilibiliTranscriptPreference,
		language: String?,
		fromSeconds: Long,
		toSeconds: Long,
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
					subtitleTranscript(info.aid, cid, language, info.durationSeconds)

				BilibiliTranscriptSource.AI_SUMMARY ->
					aiSummaryTranscript(bvid, info.aid, cid, info.ownerMid, info.durationSeconds)
			}
			if (answer != null) return window(answer, fromSeconds, toSeconds)
		}
		return null
	}

	override suspend fun uploader(
		mid: Long,
		page: Int,
		order: BilibiliUpOrder,
	): BilibiliUp {
		val profile = signedGet(
			SPACE_INFO_URL,
			mapOf("mid" to mid.toString()),
			dm = true,
		)
		val counter = plainGet(RELATION_URL, mapOf("vmid" to mid.toString()))
		val videos = signedGet(
			SPACE_VIDEOS_URL,
			buildMap {
				put("mid", mid.toString())
				put("ps", SPACE_VIDEOS_PER_PAGE.toString())
				put("pn", page.toString())
				put("order", order.apiValue)
				put("platform", "web")
				put("web_location", "1550101")
			},
			dm = true,
		)
		val collections = signedGet(
			SPACE_COLLECTIONS_URL,
			mapOf(
				"mid" to mid.toString(),
				"page_num" to "1",
				"page_size" to "5",
			),
			dm = true,
		)
		// Dynamics are the one optional part: an uploader may have none, and the
		// feed answers an empty list rather than an error. A refusal here must
		// not take the profile and the videos down with it.
		val dynamics = runCatching {
			signedGet(
				SPACE_DYNAMICS_URL,
				mapOf("host_mid" to mid.toString(), "features" to "itemOpusStyle"),
				dm = true,
			)
		}.getOrNull()
		return BilibiliUpParsers.parse(
			mid = mid,
			page = page,
			profile = profile,
			counter = counter,
			videos = videos,
			collections = collections,
			dynamics = dynamics,
		)
	}

	override suspend fun trending(
		kind: BilibiliTrendingKind,
		limit: Int,
		category: BilibiliRankingCategory,
	): List<BilibiliTrendingItem> = when (kind) {
		BilibiliTrendingKind.RANKING -> BilibiliTrendingParsers.parseRanking(
			plainGet(
				RANKING_URL,
				mapOf("rid" to category.rid.toString(), "type" to "all"),
			),
		)

		BilibiliTrendingKind.POPULAR -> BilibiliTrendingParsers.parsePopular(
			plainGet(POPULAR_URL, mapOf("ps" to limit.toString(), "pn" to "1")),
		)

		BilibiliTrendingKind.HOT_SEARCH -> BilibiliTrendingParsers.parseHotSearch(
			plainGet(HOT_SEARCH_URL, mapOf("limit" to HOT_SEARCH_LIMIT.toString())),
		)
	}.take(limit)

	override suspend fun bangumi(seasonId: Long, epId: Long): BilibiliBangumi {
		val params = buildMap {
			if (seasonId > 0) put("season_id", seasonId.toString())
			if (epId > 0) put("ep_id", epId.toString())
		}
		if (params.isEmpty()) {
			throw BilibiliApiException(
				-400,
				"a season id or an episode id is required",
			)
		}
		return BilibiliBangumiParsers.parse(plainGet(PGC_SEASON_URL, params))
	}

	override suspend fun live(roomId: Long, mid: Long): BilibiliLiveRoom = when {
		roomId > 0 -> BilibiliLiveParsers.parseRoom(
			plainGet(LIVE_ROOM_URL, mapOf("room_id" to roomId.toString())),
		)

		mid > 0 -> BilibiliLiveParsers.parseRoomByUid(
			plainGet(LIVE_ROOM_BY_UID_URL, mapOf("mid" to mid.toString())),
			mid,
		)

		else -> throw BilibiliApiException(
			-400,
			"a room id or an uploader mid is required",
		)
	}

	override suspend fun danmaku(
		bvid: String,
		page: Int,
		fromSeconds: Long,
		toSeconds: Long,
	): BilibiliDanmaku {
		val info = video(bvid)
		val part = info.pages.firstOrNull { it.page == page } ?: info.pages.getOrNull(page - 1)
		val cid = part?.cid?.takeIf { it > 0 } ?: info.cid.takeIf { it > 0 }
			?: throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"$bvid has no playable part",
			)
		val config = BilibiliDanmakuParser.parseSegmentConfig(
			binaryGet(
				DM_VIEW_URL,
				mapOf("type" to "1", "oid" to cid.toString(), "pid" to info.aid.toString()),
			),
		)
		val segmentSeconds = (config?.segmentMillis ?: DEFAULT_SEGMENT_MILLIS) / 1000L
		val segmentCount = config?.segmentCount
			?: ((info.durationSeconds + segmentSeconds - 1) / segmentSeconds).toInt()
			.coerceAtLeast(1)

		// A window asks for the segments it touches; no window means a sample
		// spread across the video (first, middle, last) so the tool answers
		// "what are viewers saying" rather than "what were they saying in the
		// first six minutes".
		val windowed = fromSeconds > 0 || toSeconds > 0
		val indices = if (windowed) {
			val first = (fromSeconds.coerceAtLeast(0) / segmentSeconds).toInt() + 1
			val last = if (toSeconds > 0) {
				((toSeconds + segmentSeconds - 1) / segmentSeconds).toInt()
			} else {
				segmentCount
			}
			(first.coerceIn(1, segmentCount)..last.coerceIn(1, segmentCount))
				.take(MAX_DANMAKU_SEGMENTS)
		} else {
			spread(segmentCount)
		}

		val sampled = indices.map { index ->
			val lines = BilibiliDanmakuParser.parseSegment(
				binaryGet(
					DM_SEG_URL,
					mapOf(
						"type" to "1",
						"oid" to cid.toString(),
						"pid" to info.aid.toString(),
						"segment_index" to index.toString(),
					),
				),
			)
			val from = (index - 1) * segmentSeconds
			BilibiliDanmakuSegment(
				index = index,
				fromSeconds = from,
				toSeconds = from + segmentSeconds,
				entries = lines.size,
				lines = lines,
			)
		}
		return BilibiliDanmaku(
			totalCount = info.stats.danmaku,
			segmentCount = segmentCount,
			segmentSeconds = segmentSeconds,
			durationSeconds = info.durationSeconds,
			sampled = sampled,
		)
	}

	/**
	 * The page of replies for one `oid`.
	 *
	 * `type` is the reply surface's own discriminator: 1 for a video, 12 for a
	 * column article. The cursor is opaque and the endpoint supplies it; paging
	 * by offset is not the same thing and was observed to skip.
	 */
	private suspend fun commentPage(
		type: Int,
		oid: Long,
		sort: BilibiliCommentSort,
		limit: Int,
		page: Int,
	): BilibiliComments {
		var next = "0"
		var parsed = BilibiliComments(emptyList(), emptyList())
		for (index in 1..page.coerceAtLeast(1)) {
			val root = plainGet(
				REPLY_URL,
				mapOf(
					"oid" to oid.toString(),
					"type" to type.toString(),
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
					"there is no comment page $page on this item",
				)
		}
		return parsed
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
		language: String?,
		durationSeconds: Long,
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
		val requested = language?.takeIf { it.isNotBlank() }
		val track = requested?.let { wanted ->
			tracks.firstOrNull {
				it.language.equals(wanted, ignoreCase = true) ||
					it.language.startsWith(wanted, ignoreCase = true)
			}
		} ?: tracks.minByOrNull { subtitleRank(it.language) } ?: tracks.first()
		val cues = downloadSubtitleCues(track.url)
		if (cues.isEmpty()) return null
		val kept = cues.take(MAX_SUBTITLE_CUES)
		return BilibiliTranscript(
			source = BilibiliTranscriptSource.SUBTITLE,
			text = kept.joinToString("\n") { it.text }.take(MAX_TRANSCRIPT_CHARS),
			segments = kept.size,
			language = track.label.ifBlank { track.language },
			aiGenerated = track.language.startsWith("ai-") || track.type == 1,
			cues = kept,
			durationSeconds = durationSeconds,
		)
	}

	/** Bilibili's own AI summary of one part, or null when it has none. */
	private suspend fun aiSummaryTranscript(
		bvid: String,
		aid: Long,
		cid: Long,
		upMid: Long,
		durationSeconds: Long,
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
			durationSeconds = durationSeconds,
		)
	}

	/**
	 * Keep the cues inside a requested window.
	 *
	 * The window is a *subtitle* feature: the AI summary has its own timestamps
	 * and is short, so it is returned whole. A window that selects nothing is a
	 * real answer, so the result keeps the subtitle source with an empty cue
	 * list rather than turning into "this video has no subtitles".
	 */
	private fun window(
		transcript: BilibiliTranscript,
		fromSeconds: Long,
		toSeconds: Long,
	): BilibiliTranscript {
		if (transcript.cues.isEmpty()) return transcript
		if (fromSeconds <= 0 && toSeconds <= 0) return transcript
		val kept = transcript.cues.filter { cue ->
			(fromSeconds <= 0 || cue.fromSeconds >= fromSeconds) &&
				(toSeconds <= 0 || cue.fromSeconds < toSeconds)
		}
		return transcript.copy(
			text = kept.joinToString("\n") { it.text }.take(MAX_TRANSCRIPT_CHARS),
			segments = kept.size,
			cues = kept,
		)
	}

	private suspend fun downloadSubtitleCues(url: String): List<BilibiliTranscriptCue> {
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
					BilibiliParsers.parseSubtitleCues(root)
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

	/**
	 * A non-JSON read for the danmaku segment endpoints.
	 *
	 * They answer protobuf, not JSON, so this cannot go through [executeGet].
	 * The transport rules are the same: paced, cookie-carrying, one refusal
	 * shape. A body that is not the protobuf the parser expects is not an error
	 * here: the parser returns no lines and the tool reports the sample it got.
	 */
	private suspend fun binaryGet(url: String, params: Map<String, String>): ByteArray {
		val active = ensureSession()
		pace()
		val fullUrl = if (params.isEmpty()) url else "$url?${WbiSigner.queryString(params)}"
		val builder = Request.Builder()
			.url(fullUrl)
			.get()
			.header("User-Agent", USER_AGENT)
			.header("Referer", REFERER)
			.header("Accept", "*/*")
		val cookie = cookieHeader(active)
		if (cookie.isNotBlank()) builder.header("Cookie", cookie)
		return withContext(Dispatchers.IO) {
			try {
				http.newCall(builder.build()).execute().use { response ->
					if (!response.isSuccessful) {
						throw BilibiliApiException(
							BilibiliApiException.TRANSPORT,
							"HTTP ${response.code}",
						)
					}
					response.body.bytes()
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
		val cookie = cookieHeader(session)
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

	private fun cookieHeader(session: Session?): String = buildString {
		if (session != null && session.buvid3.isNotBlank()) {
			append("buvid3=${session.buvid3}; buvid4=${session.buvid4}")
		}
		val account = credentials()
		if (account != null && account.isUsable) {
			if (isNotEmpty()) append("; ")
			append("SESSDATA=${account.sessdata}; DedeUserID=${account.dedeUserId}")
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

	private data class CachedVideo(val info: BilibiliVideoInfo, val atMillis: Long)

	private data class Session(
		val buvid3: String,
		val buvid4: String,
		val mixinKey: String,
		val fetchedAtMillis: Long,
	)

	companion object {
		private const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/wbi/search/type"
		private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
		private const val RELATED_URL = "https://api.bilibili.com/x/web-interface/archive/related"
		private const val REPLY_URL = "https://api.bilibili.com/x/v2/reply/main"
		private const val REPLY_THREAD_URL = "https://api.bilibili.com/x/v2/reply/reply"
		private const val ARTICLE_URL = "https://api.bilibili.com/x/article/view"
		private const val PLAYER_URL = "https://api.bilibili.com/x/player/wbi/v2"
		private const val CONCLUSION_URL =
			"https://api.bilibili.com/x/web-interface/view/conclusion/get"
		private const val SPACE_INFO_URL = "https://api.bilibili.com/x/space/wbi/acc/info"
		private const val RELATION_URL = "https://api.bilibili.com/x/relation/stat"
		private const val SPACE_VIDEOS_URL = "https://api.bilibili.com/x/space/wbi/arc/search"
		private const val SPACE_COLLECTIONS_URL =
			"https://api.bilibili.com/x/polymer/web-space/seasons_series_list"
		private const val SPACE_DYNAMICS_URL =
			"https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space"
		private const val RANKING_URL = "https://api.bilibili.com/x/web-interface/ranking/v2"
		private const val POPULAR_URL = "https://api.bilibili.com/x/web-interface/popular"
		private const val HOT_SEARCH_URL = "https://api.bilibili.com/x/web-interface/search/square"
		private const val PGC_SEASON_URL = "https://api.bilibili.com/pgc/view/web/season"
		private const val LIVE_ROOM_URL = "https://api.live.bilibili.com/room/v1/Room/get_info"
		private const val LIVE_ROOM_BY_UID_URL =
			"https://api.live.bilibili.com/room/v1/Room/getRoomInfoOld"
		private const val DM_VIEW_URL = "https://api.bilibili.com/x/v2/dm/web/view"
		private const val DM_SEG_URL = "https://api.bilibili.com/x/v2/dm/web/seg.so"
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

		/** Bounded so a two-hour video cannot hold its whole subtitle in memory. */
		private const val MAX_SUBTITLE_CUES = 8_000

		/** The uploader's video page: the picker shows ten; the endpoint is asked for one page. */
		private const val SPACE_VIDEOS_PER_PAGE = 10

		/** One, two or three danmaku windows per call; each is a request. */
		private const val MAX_DANMAKU_SEGMENTS = 3

		/** The config's own six-minute segment; used only if the endpoint omits it. */
		private const val DEFAULT_SEGMENT_MILLIS = 360_000L

		private const val HOT_SEARCH_LIMIT = 20

		private const val MAX_CACHED_VIDEOS = 8

		/** Long enough for one conversation's overlapping reads. */
		private const val VIDEO_CACHE_TTL_MS = 10 * 60 * 1000L

		/** Long enough to be polite, short enough not to feel like a hang. */
		private const val MIN_REQUEST_INTERVAL_MS = 700L

		/** The nav keys rotate daily; a stale key answers -403, not silence. */
		private const val SESSION_TTL_MS = 30 * 60 * 1000L

		private const val RATE_LIMIT_PAUSE_MS = 1_500L

		/** Two pauses are about 4.5 seconds; longer than that is a hung tool. */
		private const val MAX_RATE_LIMIT_RETRIES = 2

		/**
		 * The sample indices for a whole-video ask: first, middle and last
		 * window. One window is a real answer for a short video, three are the
		 * ceiling; reading all of them for a two-hour stream is not a read, it
		 * is a crawl.
		 */
		private fun spread(segmentCount: Int): List<Int> = when {
			segmentCount <= 1 -> listOf(1)
			segmentCount == 2 -> listOf(1, 2)
			else -> listOf(
				1,
				segmentCount / 2,
				segmentCount,
			).distinct()
		}

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
