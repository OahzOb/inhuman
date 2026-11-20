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
 *   with an anonymous browsing id only.
 * - **Text in, text out.** Nothing here writes a workspace, and no parameter is
 *   a URL. The model supplies a BV/cv id or a keyword, never an address.
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
	 */
	suspend fun search(
		keyword: String,
		kind: BilibiliSearchKind,
		order: String,
		page: Int,
		limit: Int,
	): List<BilibiliSearchHit>

	suspend fun video(bvid: String): BilibiliVideoInfo

	suspend fun comments(
		bvid: String,
		sort: BilibiliCommentSort,
		limit: Int,
		/** 1-based page. Pages after the first exist only for a logged-in caller. */
		page: Int = 1,
	): BilibiliComments

	suspend fun article(cvId: Long): BilibiliArticle

	/**
	 * Read a video's speech as text: subtitles when they exist, Bilibili's own
	 * AI summary when they do not.
	 *
	 * @param page 1-based part of a multi-part video.
	 * @return null when Bilibili has neither for this video — a real answer the
	 *   tool must state, not an error and not an empty transcript.
	 */
	suspend fun transcript(
		bvid: String,
		page: Int,
		prefer: BilibiliTranscriptPreference,
	): BilibiliTranscript?
}

enum class BilibiliSearchKind(val apiValue: String, val label: String) {
	VIDEO("video", "video"),
	ARTICLE("article", "article"),
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
		val title: String,
		val author: String,
		val view: Long,
		val like: Long,
		val replyCount: Long,
		val pubdateSeconds: Long,
		val description: String,
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
)

data class BilibiliCommentReply(
	val author: String,
	val content: String,
	val like: Long,
)

data class BilibiliComment(
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

		else ->
			"Bilibili returned code ${error.code}: ${error.apiMessage.ifBlank { "no message" }}"
	}
}
