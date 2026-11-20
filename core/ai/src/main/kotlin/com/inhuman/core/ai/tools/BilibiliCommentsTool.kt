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
 * Read the top comments on one Bilibili video.
 *
 * ## Why only one page, and why it says so
 *
 * The public comment endpoint returns one page per request and anonymous
 * callers are the ones risk control watches, so this tool reads one page and
 * states its size. A model that thinks it read every comment will summarise the
 * comment section wrongly; a model told "20 shown" knows the difference between
 * a quiet video and an unread page.
 *
 * ## Why the comments are capped per comment
 *
 * A comment can be long and reply threads longer. The useful signal in a
 * comment section is what people are saying, not the full text of any one
 * thread; 400 characters keeps the shape and leaves room for the context the
 * conversation actually needs.
 */
class BilibiliCommentsTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_comments"

	override val mutates = false

	override val description = """
		Read the top comments on a Bilibili video, with like counts and a couple of replies.

		Use this after `bili_video` when the user asks what viewers think, whether a video is
		useful, or how a discussion went. Comments are public and read anonymously; there is no
		account involved and you cannot reply, like or post from here.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"bvid" to mapOf(
				"type" to "string",
				"description" to "The video's BV id, for example BV1das46aEKM.",
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
		),
		"required" to listOf("bvid"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val bvid = BilibiliToolSupport.bvid(arguments)
			?: return ToolResult.error(
				"missing or invalid 'bvid': expected a BV id such as BV1das46aEKM",
			)
		val sort = when (BilibiliToolSupport.string(arguments, "sort")?.lowercase()) {
			null, "hot" -> BilibiliCommentSort.HOT
			"new" -> BilibiliCommentSort.NEW
			else -> return ToolResult.error("'sort' must be either 'hot' or 'new'")
		}
		val limit = BilibiliToolSupport.int(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)

		return try {
			val page = service.comments(bvid, sort, limit)
			if (page.pinned.isEmpty() && page.comments.isEmpty()) {
				// "Empty" is a real answer. The endpoint returning code 0 with no
				// comments is different from a blocked request, which throws.
				ToolResult.ok("No comments are shown for $bvid.")
			} else {
				ToolResult.ok(format(bvid, sort, page.pinned, page.comments))
			}
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili comment read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(
		bvid: String,
		sort: BilibiliCommentSort,
		pinned: List<BilibiliComment>,
		comments: List<BilibiliComment>,
	): String = buildString {
		appendLine(
			"$bvid — comments (${sort.label}), ${comments.size} shown" +
				if (pinned.isEmpty()) "" else " · ${pinned.size} pinned",
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
			appendLine("comments:")
			comments.forEachIndexed { index, comment ->
				appendComment("${index + 1}", comment)
			}
		}
		appendLine()
		appendLine("Only this page is shown; the video may have more comments.")
	}

	private fun StringBuilder.appendComment(label: String, comment: BilibiliComment) {
		val level = if (comment.level > 0) " · Lv${comment.level}" else ""
		appendLine(
			"$label. ${BilibiliToolSupport.oneLine(comment.author, AUTHOR_CHARS)}$level" +
				" · ${BilibiliToolSupport.number(comment.like)} likes" +
				" · ${BilibiliToolSupport.number(comment.replyCount)} replies" +
				" · ${BilibiliToolSupport.dateTime(comment.createdAtSeconds)}",
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
		private const val AUTHOR_CHARS = 40
		private const val CONTENT_CHARS = 400
		private const val REPLY_CHARS = 200
		private const val MAX_REPLIES = 2
	}
}
