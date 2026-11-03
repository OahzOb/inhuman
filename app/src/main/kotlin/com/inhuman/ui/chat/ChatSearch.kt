package com.inhuman.ui.chat

/**
 * One message matching a search inside the open conversation.
 *
 * [messageIndex] is the position in the rendered transcript, not a count of
 * matches: the screen scrolls a `LazyColumn` by index, and a match list that
 * only knew its own ordinal would have to search the transcript again to find
 * where to go.
 */
internal data class ChatSearchMatch(
	val messageId: String,
	val messageIndex: Int,
	/** Whether the hit is something the user wrote, for the result label. */
	val fromUser: Boolean,
	val snippet: String,
)

/**
 * One user message, as a target for "jump to turn".
 *
 * [preview] is the first non-empty line, collapsed and bounded. It is empty
 * for a message that contains only attachments; the UI names the turn by its
 * number in that case.
 */
internal data class UserTurn(
	val messageIndex: Int,
	val messageId: String,
	val preview: String,
)

/**
 * Find [rawQuery] in the text the user can actually read.
 *
 * Tool results are excluded on purpose, matching the home screen's global
 * search: they are machinery, and a common word in a `list_files` result would
 * bury the sentence the user remembers writing. Reasoning is not searched
 * either; it is shown in a collapsed block, and a hit there would open a row
 * whose matching text is not visible.
 *
 * A blank query returns nothing rather than every message. The screen uses the
 * empty result to mean "no search yet", and returning everything would turn an
 * accidental space into a jump to the top of the conversation.
 */
internal fun searchConversation(
	messages: List<ChatMessageUi>,
	rawQuery: String,
): List<ChatSearchMatch> {
	val query = rawQuery.trim()
	if (query.isEmpty()) return emptyList()

	return messages.mapIndexedNotNull { index, message ->
		if (message.isToolResult) return@mapIndexedNotNull null
		val at = message.plainText.indexOf(query, ignoreCase = true)
		if (at < 0) return@mapIndexedNotNull null
		ChatSearchMatch(
			messageId = message.id,
			messageIndex = index,
			fromUser = message.fromUser,
			snippet = snippetAround(message.plainText, at, query.length),
		)
	}
}

/** The user messages of [messages], in transcript order. */
internal fun userTurnsOf(messages: List<ChatMessageUi>): List<UserTurn> =
	messages.mapIndexedNotNull { index, message ->
		if (!message.fromUser) return@mapIndexedNotNull null
		UserTurn(
			messageIndex = index,
			messageId = message.id,
			preview = previewOf(message.plainText),
		)
	}

/**
 * A window around the match, with whitespace collapsed and the cut ends marked.
 *
 * The offset is taken from the raw text before collapsing: a newline before the
 * match would move every later index if the text were normalised first, and a
 * snippet pointing at the wrong words is worse than a shorter one.
 */
private fun snippetAround(text: String, at: Int, length: Int): String {
	val start = (at - 32).coerceAtLeast(0)
	val end = (at + length + 48).coerceAtMost(text.length)
	val window = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
	return buildString {
		if (start > 0) append('…')
		append(window)
		if (end < text.length) append('…')
	}
}

/** The first non-empty line, collapsed and capped for a one-row target. */
private fun previewOf(text: String): String =
	text.lineSequence()
		.map { it.trim() }
		.firstOrNull { it.isNotEmpty() }
		?.replace(WHITESPACE, " ")
		?.take(80)
		.orEmpty()

/**
 * One compiled pattern for every preview.
 *
 * `replace(Regex("\\s+"), " ")` compiled a fresh `Pattern` per call, and this
 * runs once per user message every time the transcript index is rebuilt.
 */
private val WHITESPACE = Regex("\\s+")
