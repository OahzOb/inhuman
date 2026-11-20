package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliComment
import com.inhuman.core.ai.bilibili.BilibiliCommentSort
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import kotlinx.serialization.json.JsonObject

/**
 * Read the top comments on a Bilibili video or column article.
 *
 * ## Why only one page, and why it says so
 *
 * The public comment endpoint returns one page per request and anonymous
 * callers are the ones risk control watches, so this tool reads one page and
 * states its size. A model that thinks it read every comment will summarise the
 * comment section wrongly; a model told "20 shown" knows the difference between
 * a quiet video and an unread page. Pages after the first need the linked login,
 * and the note says that rather than pretending the list ended.
 *
 * ## Why comment ids are printed
 *
 * A comment thread is where the useful argument usually is. Each comment's
 * `rpid` is printed so the model can ask for that thread specifically
 * (`root`) instead of re-reading pages hoping to reach the replies.
 *
 * ## Why articles share this tool
 *
 * "What do people say under this" is the same question for a video and a column.
 * The endpoints differ only in the reply surface's `type`; the output, the caps
 * and the reader's expectations are identical, so they are one tool with two
 * id arguments rather than two names that differ by a noun.
 */
class BilibiliCommentsTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_comments"

	override val mutates = false

	override val description = """
		Read the top comments on a Bilibili video, with like counts and a couple of replies.
		Also reads the comments on a column article when given its cv id instead of a BV id.

		Use this after `bili_video` when the user asks what viewers think, whether a video is
		useful, or how a discussion went. Pass `root` with a comment's rpid (printed on each
		comment) to read that comment's reply thread. Comments are public and read
		anonymously; there is no account involved and you cannot reply, like or post from
		here.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"bvid" to mapOf(
				"type" to "string",
				"description" to "The video's BV id, for example BV1das46aEKM. Use `cv_id` " +
					"instead for a column article.",
			),
			"cv_id" to mapOf(
				"type" to "string",
				"description" to "The column's cv id (cv34975835 or 34975835), when reading " +
					"an article's comments.",
			),
			"sort" to mapOf(
				"type" to "string",
				"enum" to listOf("hot", "new"),
				"description" to "Order the comments by likes (default) or newest first.",
			),
			"limit" to mapOf(
				"type" to "integer",
				"description" to "How many comments to return, 1 to $MAX_LIMIT. Defaults to $DEFAULT_LIMIT.",
			),
			"page" to mapOf(
				"type" to "integer",
				"description" to "Which page of comments, starting at 1. Pages after the first " +
					"need the optional Bilibili login linked in Settings.",
			),
			"root" to mapOf(
				"type" to "integer",
				"description" to "A comment's rpid: read that comment's reply thread instead of " +
					"the video's comment list (videos only).",
			),
		),
		"required" to emptyList<String>(),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val bvid = BilibiliToolSupport.bvid(arguments)
		val cvId = if (bvid == null) BilibiliToolSupport.cvId(arguments, "cv_id") else null
		if (bvid == null && cvId == null) {
			return ToolResult.error(
				"pass either 'bvid' (a BV id) or 'cv_id' (a column cv id)",
			)
		}
		val sort = when (BilibiliToolSupport.string(arguments, "sort")?.lowercase()) {
			null, "hot" -> BilibiliCommentSort.HOT
			"new" -> BilibiliCommentSort.NEW
			else -> return ToolResult.error("'sort' must be either 'hot' or 'new'")
		}
		val limit = BilibiliToolSupport.int(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
		val page = BilibiliToolSupport.int(arguments, "page", 1, 1, MAX_PAGE)
		val root = BilibiliToolSupport.long(arguments, "root")
		if (root > 0 && bvid == null) {
			return ToolResult.error("'root' expands a video comment thread; pass 'bvid' for it")
		}
		if (bvid != null && root <= 0 && page > 1 && !service.loggedIn) {
			// The client refuses this too; checking here gives the model a
			// sentence without spending a request on a refusal it can predict.
			return ToolResult.error(
				"comment page $page needs the optional Bilibili login linked in " +
					"Settings → B站登录; page 1 works anonymously.",
			)
		}

		return try {
			val pageData = if (bvid != null) {
				service.comments(bvid, sort, limit, page, root)
			} else {
				service.articleComments(cvId!!, sort, limit, page)
			}
			val target = bvid ?: "cv$cvId"
			if (pageData.pinned.isEmpty() && pageData.comments.isEmpty()) {
				// "Empty" is a real answer. The endpoint returning code 0 with no
				// comments is different from a blocked request, which throws.
				ToolResult.ok(
					if (root > 0) {
						"No replies are shown under comment $root on $target."
					} else {
						"No comments are shown for $target (page $page)."
					},
				)
			} else {
				ToolResult.ok(format(target, bvid != null, sort, page, root, pageData.pinned, pageData.comments))
			}
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili comment read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(
		target: String,
		isVideo: Boolean,
		sort: BilibiliCommentSort,
		page: Int,
		root: Long,
		pinned: List<BilibiliComment>,
		comments: List<BilibiliComment>,
	): String = buildString {
		val header = if (root > 0) {
			"$target — replies under comment $root, ${comments.size} shown"
		} else {
			"$target — comments (${sort.label}, page $page), ${comments.size} shown" +
				if (pinned.isEmpty()) "" else " · ${pinned.size} pinned"
		}
		appendLine(header)
		appendLine(
			if (isVideo) {
				BilibiliToolSupport.videoUrl(target)
			} else {
				BilibiliToolSupport.articleUrl(target.removePrefix("cv").toLongOrNull() ?: 0L)
			},
		)
		if (pinned.isNotEmpty()) {
			appendLine()
			appendLine("pinned:")
			pinned.forEachIndexed { index, comment ->
				appendComment("P${index + 1}", comment)
			}
		}
		if (comments.isNotEmpty()) {
			appendLine()
			appendLine(if (root > 0) "replies:" else "comments:")
			comments.forEachIndexed { index, comment ->
				appendComment("${index + 1}", comment)
			}
		}
		appendLine()
		when {
			root > 0 -> appendLine("Read a comment list with `bili_comments` (without `root`).")
			isVideo -> appendLine(
				if (page > 1) {
					"Only page $page is shown; the video may have more comments."
				} else {
					"Only this page is shown; pages after the first need the Bilibili login. " +
						"Pass a comment's rpid as `root` to read its reply thread."
				},
			)
			else -> appendLine(
				"Only this page is shown. Use `bili_article` with cv${target.removePrefix("cv")} " +
					"for the article's text.",
			)
		}
	}

	private fun StringBuilder.appendComment(label: String, comment: BilibiliComment) {
		val level = if (comment.level > 0) " · Lv${comment.level}" else ""
		appendLine(
			"$label. ${BilibiliToolSupport.oneLine(comment.author, AUTHOR_CHARS)}$level" +
				" · ${BilibiliToolSupport.number(comment.like)} likes" +
				" · ${BilibiliToolSupport.number(comment.replyCount)} replies" +
				" · ${BilibiliToolSupport.dateTime(comment.createdAtSeconds)}" +
				if (comment.rpid > 0) " · rpid ${comment.rpid}" else "",
		)
		appendLine("   ${BilibiliToolSupport.oneLine(comment.content, CONTENT_CHARS)}")
		comment.replies.take(MAX_REPLIES).forEach { reply ->
			appendLine(
				"   - ${BilibiliToolSupport.oneLine(reply.author, AUTHOR_CHARS)}: " +
					BilibiliToolSupport.oneLine(reply.content, REPLY_CHARS),
			)
		}
		if (comment.replies.size > MAX_REPLIES) {
			appendLine("   - … ${comment.replies.size - MAX_REPLIES} more replies not shown")
		}
	}

	companion object {
		const val DEFAULT_LIMIT = 15
		const val MAX_LIMIT = 30
		const val MAX_PAGE = 5
		private const val AUTHOR_CHARS = 40
		private const val CONTENT_CHARS = 400
		private const val REPLY_CHARS = 200
		private const val MAX_REPLIES = 2
	}
}
