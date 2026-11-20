package com.inhuman.core.ai.bilibili

/**
 * The anonymous Bilibili read surface the tools are written against.
 *
 * ## Why an interface here
 *
 * The tools format and cap what they return, and that is the part worth testing
 * without a network in either direction. The implementation talks to a
 * third-party, unofficial endpoint set; a fake is the only way to exercise
 * "risk control answered `-352`" or "the article ended up empty" deterministically.
 *
 * ## What every method guarantees
 *
 * - **A refusal is an exception, never an empty value.** An empty comment list
 *   means "there are no comments"; a blocked request must not be
 *   indistinguishable from that.
 * - **No account, no key.** The implementation uses the public web endpoints
 *   with an anonymous browsing id only. The optional login adds subtitles and
 *   comment pages; it never turns into account data.
 * - **Text in, text out.** Nothing here writes a workspace, and no parameter is
 *   a URL. The model supplies a BV/cv id, a mid, a room id or a keyword, never
 *   an address.
 */
interface BilibiliReadService {

	/**
	 * Whether the client currently sends a logged-in session.
	 *
	 * Read tools that only a login can answer are registered by the host only
	 * when this is true, so they are absent rather than present-and-failing on
	 * an anonymous install. It is not a permission: the anonymous tools work
	 * either way.
	 */
	val loggedIn: Boolean

	/**
	 * @param order raw Bilibili order value (`totalrank`, `pubdate`, `click`).
	 * @param page 1-based; the endpoints cap it in practice.
	 * @param limit how many hits the caller wants, already clamped by the tool.
	 * @param filters optional narrowing; an empty instance sends nothing extra.
	 */
	suspend fun search(
		keyword: String,
		kind: BilibiliSearchKind,
		order: String,
		page: Int,
		limit: Int,
		filters: BilibiliSearchFilters = BilibiliSearchFilters(),
	): List<BilibiliSearchHit>

	suspend fun video(bvid: String): BilibiliVideoInfo

	/** Videos Bilibili recommends beside this one. One request, no account. */
	suspend fun related(bvid: String, limit: Int): List<BilibiliVideoSummary>

	suspend fun comments(
		bvid: String,
		sort: BilibiliCommentSort,
		limit: Int,
		/** 1-based page. Pages after the first exist only for a logged-in caller. */
		page: Int = 1,
		/**
		 * When non-zero, read the replies under that comment (`rpid`) instead of
		 * the video's comment list. Sort and page are ignored in that mode.
		 */
		root: Long = 0L,
	): BilibiliComments

	suspend fun article(cvId: Long): BilibiliArticle

	/**
	 * The comments on a column article.
	 *
	 * A separate call because the article reply surface is its own `oid` space
	 * (`type=12`), not because it is a different capability: it is the same
	 * public comment reader applied to a cv id.
	 */
	suspend fun articleComments(
		cvId: Long,
		sort: BilibiliCommentSort,
		limit: Int,
		page: Int = 1,
	): BilibiliComments

	/**
	 * Read a video's speech as text: subtitles when they exist, Bilibili's own
	 * AI summary when they do not.
	 *
	 * @param page 1-based part of a multi-part video.
	 * @param language optional language prefix (`zh-CN`, `en`, `ai-zh`); null
	 *   keeps the client's Chinese-first preference.
	 * @param fromSeconds when positive, keep cues starting at or after it.
	 * @param toSeconds when positive, keep cues starting before it. The window
	 *   applies to subtitles; a summary is returned whole.
	 * @return null when Bilibili has neither for this video — a real answer the
	 *   tool must state, not an error and not an empty transcript.
	 */
	suspend fun transcript(
		bvid: String,
		page: Int,
		prefer: BilibiliTranscriptPreference,
		language: String? = null,
		fromSeconds: Long = 0L,
		toSeconds: Long = 0L,
	): BilibiliTranscript?

	/**
	 * One uploader's public page: profile, counters, live state, recent videos,
	 * collections/series and recent dynamic posts.
	 *
	 * All of it is public and readable without an account; the login is not
	 * involved. The videos are one page at a time because the endpoint is
	 * paginated and a whole channel does not belong in one tool result.
	 */
	suspend fun uploader(
		mid: Long,
		page: Int,
		order: BilibiliUpOrder,
	): BilibiliUp

	/**
	 * Bilibili's own discovery lists: the ranking, the popular feed, or the
	 * current hot searches.
	 */
	suspend fun trending(
		kind: BilibiliTrendingKind,
		limit: Int,
		category: BilibiliRankingCategory = BilibiliRankingCategory.ALL,
	): List<BilibiliTrendingItem>

	/**
	 * One season of a Bilibili show (anime, documentary, series).
	 *
	 * @param seasonId an `ss` id; 0 when only [epId] is known.
	 * @param epId an `ep` id; 0 when only a season id is known. Either one is
	 *   enough: the endpoint resolves the other direction itself.
	 */
	suspend fun bangumi(seasonId: Long, epId: Long): BilibiliBangumi

	/**
	 * One live room, by room id or by the streamer's uid.
	 *
	 * @param roomId 0 when only [mid] is known.
	 * @param mid 0 when only a room id is known.
	 */
	suspend fun live(roomId: Long, mid: Long): BilibiliLiveRoom

	/**
	 * Danmaku (scrolling comments) for one part of a video, sampled.
	 *
	 * The segment endpoints return a bounded subset per six-minute window, so
	 * the result says how many segments exist and which ones were read. It is a
	 * sample for flavour and for finding where the reaction is, not a complete
	 * archive.
	 *
	 * @param fromSeconds/toSeconds narrows which segments are fetched; zero on
	 *   both means "the start of the video".
	 */
	suspend fun danmaku(
		bvid: String,
		page: Int,
		fromSeconds: Long = 0L,
		toSeconds: Long = 0L,
	): BilibiliDanmaku
}

enum class BilibiliSearchKind(val apiValue: String, val label: String) {
	VIDEO("video", "video"),
	ARTICLE("article", "article"),

	/** Uploaders, not content: the result is a mid to use with `bili_up`. */
	USER("bili_user", "uploader"),

	/** Seasons of anime/documentaries/series, for `bili_bangumi`. */
	BANGUMI("media_bangumi", "show"),
}

/**
 * Narrowing Bilibili's search accepts on the video endpoint.
 *
 * A field with its zero value sends nothing: absence and "no filter" are the
 * same thing to the endpoint, and keeping one representation here avoids a
 * second code path in the client.
 */
data class BilibiliSearchFilters(
	val duration: BilibiliSearchDuration? = null,
	val publishedAfterSeconds: Long = 0L,
	val publishedBeforeSeconds: Long = 0L,
	/**
	 * A raw search partition id (`tids`).
	 *
	 * Deliberately raw: Bilibili's search partition ids are not the ranking's
	 * `rid` values, so a friendly-name map here would be a wrong label on a
	 * different endpoint. The tool documents a few verified ids instead.
	 */
	val partitionId: Int = 0,
)

/** The endpoint's own `duration` buckets. */
enum class BilibiliSearchDuration(val apiValue: Int, val label: String) {
	UNDER_TEN(1, "under 10 minutes"),
	TEN_TO_THIRTY(2, "10 to 30 minutes"),
	THIRTY_TO_SIXTY(3, "30 to 60 minutes"),
	OVER_SIXTY(4, "over 60 minutes"),
}

enum class BilibiliCommentSort(val mode: Int, val label: String) {
	/** Bilibili's "hot" ordering; the default a reader would see. */
	HOT(3, "hot"),
	/** Newest first. */
	NEW(2, "new"),
}

sealed interface BilibiliSearchHit {

	data class Video(
		val bvid: String,
		val aid: Long,
		val ownerMid: Long,
		val title: String,
		val author: String,
		val play: Long,
		val commentCount: Long,
		val duration: String,
		val pubdateSeconds: Long,
		val description: String,
	) : BilibiliSearchHit

	data class Article(
		val cvId: Long,
		val ownerMid: Long,
		val title: String,
		val author: String,
		val view: Long,
		val like: Long,
		val replyCount: Long,
		val pubdateSeconds: Long,
		val description: String,
	) : BilibiliSearchHit

	data class User(
		val mid: Long,
		val name: String,
		val fans: Long,
		val videos: Long,
		val level: Int,
		val sign: String,
		val official: String,
		val live: Boolean,
	) : BilibiliSearchHit

	data class Bangumi(
		val seasonId: Long,
		val title: String,
		val score: Double,
		val episodes: Int,
		val publishSeconds: Long,
		val styles: List<String>,
		val description: String,
		val status: String,
	) : BilibiliSearchHit
}

data class BilibiliStats(
	val view: Long = 0,
	val danmaku: Long = 0,
	val reply: Long = 0,
	val favorite: Long = 0,
	val coin: Long = 0,
	val like: Long = 0,
	val share: Long = 0,
)

data class BilibiliVideoPage(
	val cid: Long,
	val page: Int,
	val title: String,
	val durationSeconds: Long,
)

data class BilibiliVideoInfo(
	val bvid: String,
	val aid: Long,
	/** First part's cid; the transcript tool uses it for a single-part video. */
	val cid: Long = 0L,
	val title: String,
	val description: String,
	val ownerName: String,
	val ownerMid: Long,
	val pubdateSeconds: Long,
	val durationSeconds: Long,
	val category: String,
	val stats: BilibiliStats,
	val pages: List<BilibiliVideoPage>,
	/**
	 * How many subtitle tracks the public page advertises.
	 *
	 * The anonymous `view` response lists the tracks but with an empty
	 * `subtitle_url`: the page knows subtitles exist, the text needs a login.
	 * Telling the model that is the difference between "no subtitles" and
	 * "subtitles exist, link the account to read them".
	 */
	val subtitleTracks: Int = 0,
)

/** One video in a "related" list. Shorter than a search hit on purpose. */
data class BilibiliVideoSummary(
	val bvid: String,
	val title: String,
	val author: String,
	val play: Long,
	val durationSeconds: Long,
	val pubdateSeconds: Long,
	val category: String,
)

data class BilibiliCommentReply(
	val author: String,
	val content: String,
	val like: Long,
)

data class BilibiliComment(
	/** The comment's `rpid`, so a reply thread can be expanded by tool call. */
	val rpid: Long,
	val author: String,
	val level: Int,
	val content: String,
	val like: Long,
	val replyCount: Long,
	val createdAtSeconds: Long,
	val replies: List<BilibiliCommentReply>,
)

data class BilibiliComments(
	/** Comments pinned by the uploader. Always shown apart from the rest. */
	val pinned: List<BilibiliComment>,
	val comments: List<BilibiliComment>,
)

data class BilibiliArticle(
	val cvId: Long,
	val title: String,
	val author: String,
	val publishTimeSeconds: Long,
	val words: Long,
	val stats: BilibiliStats,
	val tags: List<String>,
	/** Article body as plain text; the tool caps what it hands the model. */
	val body: String,
)

/**
 * A subtitle cue.
 *
 * The start time is carried rather than merged into one text blob because it is
 * what makes "where in the video was that said" answerable, and because the
 * window filter and the truncation notice both need it.
 */
data class BilibiliTranscriptCue(
	val fromSeconds: Long,
	val toSeconds: Long,
	val text: String,
)

data class BilibiliTranscript(
	val source: BilibiliTranscriptSource,
	/** Summary text, or the subtitle text pre-joined for callers without cues. */
	val text: String,
	val segments: Int,
	val language: String? = null,
	val aiGenerated: Boolean = false,
	val cues: List<BilibiliTranscriptCue> = emptyList(),
	val durationSeconds: Long = 0L,
)

enum class BilibiliTranscriptSource { SUBTITLE, AI_SUMMARY }

enum class BilibiliTranscriptPreference { TEXT, SUMMARY }

/** How one uploader's videos are ordered. */
enum class BilibiliUpOrder(val apiValue: String, val label: String) {
	LATEST("pubdate", "latest"),
	POPULAR("click", "most played"),
}

data class BilibiliUpVideo(
	val bvid: String,
	val title: String,
	val play: Long,
	val durationSeconds: Long,
	val createdSeconds: Long,
)

/** A collection (`season`) or a series (`series`) on the uploader's page. */
data class BilibiliUpSeries(
	val id: Long,
	val title: String,
	val total: Int,
	val description: String,
	val isCollection: Boolean,
)

/** One dynamic post, reduced to its text and time. */
data class BilibiliUpDynamic(
	val text: String,
	val createdAtSeconds: Long,
	val kind: String,
)

data class BilibiliUp(
	val mid: Long,
	val name: String,
	val sign: String,
	val level: Int,
	val fans: Long,
	val following: Long,
	val videos: Long,
	val official: String,
	val live: Boolean,
	val liveTitle: String,
	val roomId: Long,
	val recentVideos: List<BilibiliUpVideo>,
	val totalVideos: Int,
	val page: Int,
	val series: List<BilibiliUpSeries>,
	val dynamics: List<BilibiliUpDynamic>,
)

enum class BilibiliTrendingKind(val label: String) {
	RANKING("ranking"),
	POPULAR("popular"),
	HOT_SEARCH("hot searches"),
}

/**
 * The ranking's `rid` buckets.
 *
 * Only ids that actually answered are listed: `-400` for the anime season
 * bucket and the variety bucket means the ranking has no such list, and a
 * category that returns an error is worse than a missing one.
 */
enum class BilibiliRankingCategory(val rid: Int, val label: String) {
	ALL(0, "all"),
	ANIMATION(1, "animation"),
	MUSIC(3, "music"),
	GAMES(4, "games"),
	ENTERTAINMENT(5, "entertainment"),
	TV_SERIES(11, "tv series"),
	MOVIES(23, "movies"),
	KNOWLEDGE(36, "knowledge"),
	REMIX(119, "remix"),
	DANCE(129, "dance"),
	FASHION(155, "fashion"),
	LIFE(160, "life"),
	DOCUMENTARY(177, "documentary"),
	FILM_CLIPS(181, "film and tv clips"),
	DIGITAL(188, "tech and digital"),
	FOOD(211, "food"),
	ANIMALS(217, "animals"),
	CARS(223, "cars"),
	SPORTS(234, "sports"),
}

sealed interface BilibiliTrendingItem {
	data class Video(
		val bvid: String,
		val title: String,
		val author: String,
		val play: Long,
		val pubdateSeconds: Long,
		val category: String,
	) : BilibiliTrendingItem

	/** One of the hot search phrases; there is no id to click, only words. */
	data class Keyword(val keyword: String) : BilibiliTrendingItem
}

data class BilibiliBangumiEpisode(
	val epId: Long,
	val aid: Long,
	val cid: Long,
	val bvid: String,
	val title: String,
	val longTitle: String,
	val pubTimeSeconds: Long,
	val durationSeconds: Long,
	val badge: String,
)

data class BilibiliBangumi(
	val seasonId: Long,
	val title: String,
	val jpTitle: String,
	val score: Double,
	val scoreCount: Long,
	val styles: List<String>,
	val summary: String,
	val totalEpisodes: Int,
	val status: String,
	val publishText: String,
	val episodes: List<BilibiliBangumiEpisode>,
)

data class BilibiliLiveRoom(
	val roomId: Long,
	val uid: Long,
	val anchor: String,
	val title: String,
	val live: Boolean,
	val areaName: String,
	val parentAreaName: String,
	val online: Long,
	val url: String,
)

data class BilibiliDanmakuLine(
	val atSeconds: Long,
	val text: String,
)

data class BilibiliDanmakuSegment(
	val index: Int,
	val fromSeconds: Long,
	val toSeconds: Long,
	/** How many danmaku the endpoint returned for this segment. */
	val entries: Int,
	val lines: List<BilibiliDanmakuLine>,
)

data class BilibiliDanmaku(
	/** The video's own danmaku counter, not the number returned here. */
	val totalCount: Long,
	/** How many six-minute segments the video has. */
	val segmentCount: Int,
	val segmentSeconds: Long,
	val durationSeconds: Long,
	val sampled: List<BilibiliDanmakuSegment>,
)

/**
 * A refusal from the endpoint set, or a failure to reach it.
 *
 * [code] is Bilibili's `code` field, or [TRANSPORT] when the request never got
 * a usable JSON answer. The message is the server's own text; [BilibiliErrors]
 * turns the codes a user can act on into advice.
 */
class BilibiliApiException(
	val code: Int,
	val apiMessage: String,
	cause: Throwable? = null,
) : Exception("Bilibili API code $code: $apiMessage", cause) {
	companion object {
		const val TRANSPORT = -1
	}
}

/** Turns a refusal into the sentence the model should read. */
object BilibiliErrors {

	fun describe(error: BilibiliApiException): String = when (error.code) {
		BilibiliApiException.TRANSPORT ->
			"Bilibili request failed: ${error.apiMessage.ifBlank { "network error" }}"

		-101 ->
			"Bilibili says this needs a logged-in account (code -101). " +
				"If the user wants this, Bilibili login can be linked in Settings → B站登录; " +
				"otherwise tell them this item is not readable anonymously."

		-352 ->
			"Bilibili's risk control rejected the anonymous request (code -352). " +
				"Wait a little and retry, or try again from a different network."

		-412 ->
			"Bilibili blocked the request (code -412), usually after too many anonymous " +
				"requests from this network. Wait before retrying."

		-509 ->
			"Bilibili rate-limited the request (code -509). Confirm to retry after a pause."

		-403 ->
			"Bilibili refused the request signature (code -403). This is the app's bug, " +
				"not the user's; do not retry with different arguments."

		-404 ->
			"Bilibili says the requested item does not exist (code -404)."

		-400 ->
			"Bilibili rejected the request arguments (code -400). Do not retry the same " +
				"call; adjust the id or the filter."

		else ->
			"Bilibili returned code ${error.code}: ${error.apiMessage.ifBlank { "no message" }}"
	}
}
