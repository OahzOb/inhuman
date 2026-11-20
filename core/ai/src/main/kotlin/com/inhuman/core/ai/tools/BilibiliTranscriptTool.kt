package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliReadService
import com.inhuman.core.ai.bilibili.BilibiliTranscript
import com.inhuman.core.ai.bilibili.BilibiliTranscriptPreference
import com.inhuman.core.ai.bilibili.BilibiliTranscriptSource
import kotlinx.serialization.json.JsonObject

/**
 * Read what one Bilibili video actually says.
 *
 * ## Why one tool instead of two
 *
 * The useful question is "what is in this video", and Bilibili answers it with
 * two sources of very different fidelity: subtitles (what was said) and its own
 * AI summary (a digest of what was said, made by Bilibili). The model does not
 * know which exists before asking; making it call a "subtitles" tool and, on
 * failure, a "summary" tool would spend a round to discover that and would
 * teach it that failure is the normal path. One tool tries the sources in the
 * requested order and labels whichever answered.
 *
 * ## Why the source is printed
 *
 * A summary is not a transcript. A model that quotes a summary as if it were
 * the speaker's words misrepresents the video, so the result's first line says
 * which source it is, and the AI summary is additionally marked as Bilibili's.
 *
 * ## Why "no text" is a normal result
 *
 * Plenty of Bilibili videos are music, gameplay or silent — they have neither
 * subtitles nor a summary. That is a real answer, not an error: the tool says
 * so plainly and points at the metadata and comments, which still work.
 */
class BilibiliTranscriptTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_transcript"

	override val mutates = false

	override val description = """
		Read a Bilibili video's speech as text: its subtitles when Bilibili has them, or
		Bilibili's own AI summary when it does not.

		Use this when the question depends on what is said in a video rather than its title
		or comments. `prefer` chooses which source is tried first: "text" (subtitles, the
		default) or "summary" (shorter, Bilibili's digest). The result's header names the
		source, and an AI summary is Bilibili's opinion of the video, not a transcript — do
		not present it as the speaker's words.

		This tool needs the optional Bilibili login linked in Settings; it is not available
		anonymously. A video with neither subtitles nor a summary returns that answer
		explicitly; report it instead of guessing at the content.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"bvid" to mapOf(
				"type" to "string",
				"description" to "The video's BV id, for example BV1das46aEKM.",
			),
			"page" to mapOf(
				"type" to "integer",
				"description" to "Which part of a multi-part video, starting at 1. Defaults to 1.",
			),
			"prefer" to mapOf(
				"type" to "string",
				"enum" to listOf("text", "summary"),
				"description" to "Try subtitles first (default) or Bilibili's AI summary first.",
			),
		),
		"required" to listOf("bvid"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val bvid = BilibiliToolSupport.bvid(arguments)
			?: return ToolResult.error(
				"missing or invalid 'bvid': expected a BV id such as BV1das46aEKM",
			)
		val page = BilibiliToolSupport.int(arguments, "page", 1, 1, MAX_PAGE)
		val prefer = when (BilibiliToolSupport.string(arguments, "prefer")?.lowercase()) {
			null, "text" -> BilibiliTranscriptPreference.TEXT
			"summary" -> BilibiliTranscriptPreference.SUMMARY
			else -> return ToolResult.error("'prefer' must be either 'text' or 'summary'")
		}
		return try {
			val transcript = service.transcript(bvid, page, prefer)
			if (transcript == null) {
				ToolResult.ok(
					"No subtitles and no Bilibili AI summary are available for $bvid" +
						(if (page > 1) " (part $page)" else "") +
						". The video may have no speech text Bilibili has processed; " +
						"say so rather than guessing. `bili_video` and `bili_comments` " +
						"still describe and quote it.",
				)
			} else {
				ToolResult.ok(format(bvid, page, transcript))
			}
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili transcript read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(
		bvid: String,
		page: Int,
		transcript: BilibiliTranscript,
	): String = buildString {
		val source = when (transcript.source) {
			BilibiliTranscriptSource.SUBTITLE ->
				if (transcript.aiGenerated) "AI-generated subtitles" else "uploaded subtitles"

			BilibiliTranscriptSource.AI_SUMMARY -> "Bilibili AI summary"
		}
		appendLine(
			"$bvid — $source" +
				transcript.language?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() +
				if (page > 1) " · part $page" else "",
		)
		if (transcript.segments > 0) appendLine("segments: ${transcript.segments}")
		appendLine()
		appendLine(transcript.text.take(MAX_CHARS).trimEnd())
		if (transcript.text.length > MAX_CHARS) {
			appendLine()
			appendLine("[truncated at $MAX_CHARS characters]")
		}
	}

	companion object {
		/** The same ceiling the other text tools use, around 5k tokens. */
		const val MAX_CHARS = 20_000
		const val MAX_PAGE = 100
	}
}
