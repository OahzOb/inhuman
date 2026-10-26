package com.inhuman.ui.chat

import com.inhuman.core.data.Conversation
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.ToolCallsColumn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The user-facing labels in an exported transcript.
 *
 * Passed in rather than resolved here so this file stays a pure function and a
 * JVM test can assert the exact document without an Android context. The app
 * builds it from string resources, which is why a Chinese export writes 你/助手
 * rather than English headings.
 */
data class MarkdownLabels(
	val user: String,
	val assistant: String,
	val system: String,
	val reasoning: String,
	val toolCalls: String,
	val toolResult: String,
	val error: String,
	val attachments: String,
	val exported: String,
	val model: String,
	val toolsIncluded: String,
	val toolsOmitted: String,
	val attachmentsNotIncluded: String,
)

/**
 * One conversation as a plain Markdown document.
 *
 * ## What is included
 *
 * The active branch only, in transcript order: the same messages the chat screen
 * shows. Reasoning is kept because the app displays it and a reader comparing
 * answers needs it. Tool calls and results are optional -- the flag is the
 * user's choice in the export dialog -- and when they are omitted the document
 * says so rather than leaving a silent gap.
 *
 * ## What is not included
 *
 * Attachment bytes. The transcript stores paths, not copies, and a Markdown
 * file with a broken image link is worse than one that names the files it did
 * not carry. The paths are listed and a note says they are not in the file.
 *
 * Token usage, checkpoints and the database are also absent: this is a
 * conversation to read or share, not a backup. The encrypted backup is the
 * migration path, and the workspace zip carries the files.
 *
 * ## Why content is fenced by its own backtick run
 *
 * A reply can itself contain a fenced code block -- this project's demo
 * transcript deliberately does. A fixed triple-backtick fence around tool output
 * would be closed by the first internal fence and the rest of the document would
 * render as prose. [fenced] chooses a run longer than any backticks in the
 * content, which is the only reliable way to nest Markdown.
 */
fun conversationMarkdown(
	conversation: Conversation,
	labels: MarkdownLabels,
	fallbackTitle: String,
	includeToolResults: Boolean,
	exportedAt: Instant = Instant.now(),
	zone: ZoneId = ZoneId.systemDefault(),
): String {
	val title = conversation.entity.title.ifBlank { fallbackTitle }
	val messages = conversation.messages
	val attachmentPaths = messages.flatMap { attachments(it.attachmentsJson) }
	val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

	val out = StringBuilder()
	out.append("# ").append(title.replace('\n', ' ').trim()).append("\n\n")
	out.append("- ").append(labels.exported).append(": ")
		.append(formatter.format(exportedAt.atZone(zone))).append('\n')
	conversation.entity.model?.takeIf { it.isNotBlank() }?.let {
		out.append("- ").append(labels.model).append(": ").append(it).append('\n')
	}
	out.append("- ")
		.append(if (includeToolResults) labels.toolsIncluded else labels.toolsOmitted)
		.append('\n')
	if (attachmentPaths.isNotEmpty()) {
		out.append("- ").append(labels.attachmentsNotIncluded).append('\n')
	}

	var rendered = 0
	for (message in messages) {
		val section = section(message, labels, includeToolResults)
		if (section.isBlank()) continue
		out.append("\n---\n\n")
		out.append(section.trimEnd()).append('\n')
		rendered++
	}
	if (rendered == 0) out.append("\n---\n\n")
	return out.toString()
}

private fun section(
	message: MessageEntity,
	labels: MarkdownLabels,
	includeToolResults: Boolean,
): String {
	val role = when (message.role) {
		"user" -> labels.user
		"assistant" -> labels.assistant
		"tool" -> labels.toolResult
		else -> labels.system
	}
	if (message.role == "tool" && !includeToolResults) return ""

	val out = StringBuilder()
	val heading = if (message.role == "tool") {
		"### $role: `${message.toolName ?: "tool"}`"
	} else {
		"## $role"
	}
	val content = message.content.trim()
	val reasoning = message.reasoning?.trim().orEmpty()
	val attachments = attachments(message.attachmentsJson)
	val calls = if (includeToolResults) ToolCallsColumn.decode(message.toolCallsJson) else emptyList()
	val error = message.error?.trim()

	if (content.isEmpty() && reasoning.isEmpty() && attachments.isEmpty() &&
		calls.isEmpty() && error.isNullOrEmpty()
	) {
		return ""
	}

	out.append(heading).append("\n\n")
	if (reasoning.isNotEmpty()) {
		out.append("> **").append(labels.reasoning).append("**\n>\n")
		out.append(blockquote(reasoning)).append("\n\n")
	}
	if (message.role == "tool") {
		// The result is machinery, not prose: fence it even when it looks like
		// Markdown, or a result containing a heading would become a section of
		// the exported document.
		out.append(fenced(content, "text")).append("\n\n")
	} else if (content.isNotEmpty()) {
		out.append(content).append("\n\n")
	}
	if (attachments.isNotEmpty()) {
		out.append("**").append(labels.attachments).append("**\n\n")
		for (attachment in attachments) {
			out.append("- `").append(attachment.first).append("`")
			attachment.second?.takeIf { it.isNotBlank() }?.let {
				out.append(" (").append(it).append(")")
			}
			out.append('\n')
		}
		out.append('\n')
	}
	if (calls.isNotEmpty()) {
		out.append("**").append(labels.toolCalls).append("**\n\n")
		for (call in calls) {
			out.append("#### `").append(call.name ?: "tool").append("`\n\n")
			val arguments = call.argumentsJson.trim()
			if (arguments.isNotEmpty()) {
				out.append(fenced(arguments, "json")).append("\n\n")
			}
		}
	}
	if (!error.isNullOrEmpty()) {
		out.append("> **").append(labels.error).append(":** ").append(error).append("\n\n")
	}
	return out.toString()
}

/**
 * A backtick fence longer than any run in [content].
 *
 * `maxOf(3, longest + 1)` is the whole rule: a triple fence closes on a triple
 * run, so four backticks are needed around content that contains three, and so
 * on. The language tag is omitted when there is none.
 */
internal fun fenced(content: String, language: String): String {
	val longest = Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0
	val ticks = "`".repeat(maxOf(3, longest + 1))
	val body = content.trimEnd()
	return buildString {
		append(ticks)
		if (language.isNotEmpty()) append(language)
		append('\n')
		append(body)
		append('\n')
		append(ticks)
	}
}

private fun blockquote(text: String): String =
	text.trimEnd().lineSequence().joinToString("\n") { line ->
		if (line.isEmpty()) ">" else "> $line"
	}

private fun attachments(raw: String?): List<Pair<String, String?>> {
	if (raw.isNullOrBlank()) return emptyList()
	return runCatching {
		(Json.parseToJsonElement(raw) as? JsonArray).orEmpty().mapNotNull { element ->
			val obj = element as? JsonObject ?: return@mapNotNull null
			val path = obj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			path to obj["mime"]?.jsonPrimitive?.contentOrNull
		}
	}.getOrDefault(emptyList())
}