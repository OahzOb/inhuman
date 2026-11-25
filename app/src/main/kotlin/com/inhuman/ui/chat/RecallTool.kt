package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read-only search over this conversation's complete record.
 *
 * ## Why it exists
 *
 * A continuous session sends a rolling brief plus recent turns, so the model
 * no longer sees the whole transcript. The brief is lossy on purpose; without a
 * way back to the record, a lost detail is simply gone. This tool is the way
 * back: it searches the active branch and returns message ids, turn numbers and
 * snippets. `message_id` reads one message in full.
 *
 * ## Why it does not keep its own results
 *
 * It is a read, not an event. The full text is useful while the model answers
 * this turn; keeping it in the transcript would mean every lookup grows every
 * later request, which is the problem the brief exists to solve. So the result
 * carries [ToolResult.persistedContent]: the full text goes on the wire for
 * this turn, and the transcript keeps one compact line.
 *
 * ## Why it is not a capability
 *
 * It reads the conversation's own messages, locally, with no network and no
 * write path. It cannot change the record, the brief, the tool list, approvals
 * or SSH consent. It is scoped to the active branch: a branch that was rewound
 * away is not history, and returning it would create a memory of events that
 * never happened on this path.
 *
 * ## Why it is alive with state instead of Room
 *
 * The view model already holds the active branch as [ChatMessageUi]. Searching
 * that list is deterministic, free and exactly as complete as the record shown
 * to the user; a second database query would be a second answer to "what is in
 * this conversation".
 */
class RecallTool(
	private val messages: () -> List<ChatMessageUi>,
) : Tool {

	override val name: String = NAME

	override val description: String = """
		Search this conversation's complete record, read-only. Use it when the
		user refers to something older than the recent turns, or when the
		session brief is not enough: the brief is a lossy summary, the record is
		authoritative. Returns matching message ids, turn numbers and snippets.
		Pass a hit's id as message_id to read that message in full. Never invent
		an older detail before searching for it.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"query" to mapOf(
				"type" to "string",
				"description" to "Text to find in the user and assistant messages.",
			),
			"message_id" to mapOf(
				"type" to "string",
				"description" to "Read this message in full instead of searching.",
			),
			"limit" to mapOf(
				"type" to "integer",
				"minimum" to 1,
				"maximum" to MAX_HITS,
				"description" to "Maximum hits, 1-$MAX_HITS (default $DEFAULT_HITS).",
			),
		),
		"additionalProperties" to false,
	)

	override val mutates: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val readId = arguments["message_id"]?.jsonPrimitive?.contentOrNull?.trim()
		if (!readId.isNullOrEmpty()) return readMessage(readId)

		val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
		if (query.isEmpty()) {
			return ToolResult.error("recall: pass `query` to search, or `message_id` to read one message")
		}
		val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_HITS)
			.coerceIn(1, MAX_HITS)
		return search(query, limit)
	}

	private fun search(query: String, limit: Int): ToolResult {
		val branch = messages()
		val hits = branch.withIndex()
			.filter { (_, message) -> !message.isToolResult && message.plainText.isNotBlank() }
			.filter { (_, message) -> message.plainText.contains(query, ignoreCase = true) }
			.toList()
			.asReversed()
			.take(limit)
		if (hits.isEmpty()) {
			return ToolResult.ok(
				"Record search: no message in this conversation contains \"$query\". " +
					"The record only covers the active branch.",
				persistedContent = "Recall \"$query\": 0 matches (active branch).",
			)
		}

		val shown = StringBuilder()
		shown.append("Record search: \"").append(query).append("\" — ")
			.append(hits.size).append(" match(es), most recent first. ")
			.append("Turn numbers count user messages from 1.\n\n")
		val ids = mutableListOf<String>()
		for ((index, message) in hits) {
			ids += message.id
			shown.append("- id=").append(message.id)
				.append(" turn=").append(turnOf(branch, index))
				.append(" ").append(if (message.fromUser) "user" else "assistant")
				.append(": ").append(snippet(message.plainText, query))
				.append('\n')
		}
		shown.append('\n')
			.append("Use recall with message_id to read one in full. ")
			.append("The brief is a summary; this record is authoritative.")
		val text = shown.toString().take(MAX_RESULT_CHARS)
		return ToolResult.ok(
			content = if (text.length < shown.length) "$text\n[truncated]" else text,
			persistedContent = "Recall \"$query\": ${hits.size} match(es): " +
				ids.joinToString(", ") + ". Full text kept for this turn only.",
		)
	}

	private fun readMessage(id: String): ToolResult {
		val branch = messages()
		val index = branch.indexOfFirst { it.id == id }
		if (index < 0) {
			return ToolResult.error(
				"recall: no message $id on the active branch. Search first; ids come from a hit.",
			)
		}
		val message = branch[index]
		val body = message.plainText.take(MAX_MESSAGE_CHARS)
		val truncated = body.length < message.plainText.length
		val header = "Message $id (turn ${turnOf(branch, index)}, " +
			(if (message.fromUser) "user" else "assistant") + "):\n\n"
		return ToolResult.ok(
			content = header + body + if (truncated) "\n[truncated at $MAX_MESSAGE_CHARS characters]" else "",
			persistedContent = "Read record message $id (turn ${turnOf(branch, index)}); " +
				"full text kept for this turn only.",
		)
	}

	private fun turnOf(branch: List<ChatMessageUi>, index: Int): Int {
		var turn = 0
		// Count turn roots, not every user row: an in-turn insertion shares its
		// turn with the message before it, and recall's numbers have to match the
		// turn strip the user sees.
		for (i in 0..index) if (branch[i].isTurnRoot) turn++
		return turn
	}

	private fun snippet(text: String, query: String): String {
		val at = text.indexOf(query, ignoreCase = true).coerceAtLeast(0)
		val start = (at - SNIPPET_BEFORE).coerceAtLeast(0)
		val end = (at + query.length + SNIPPET_AFTER).coerceAtMost(text.length)
		val body = text.substring(start, end).replace('\n', ' ').trim()
		val prefix = if (start > 0) "…" else ""
		val suffix = if (end < text.length) "…" else ""
		return (prefix + body + suffix).take(SNIPPET_CHARS)
	}

	companion object {
		const val NAME = "recall"
		private const val DEFAULT_HITS = 3
		private const val MAX_HITS = 5
		private const val SNIPPET_BEFORE = 90
		private const val SNIPPET_AFTER = 160
		private const val SNIPPET_CHARS = 320
		private const val MAX_MESSAGE_CHARS = 4_000
		private const val MAX_RESULT_CHARS = 6_000
	}
}
