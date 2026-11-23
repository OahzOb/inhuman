package com.inhuman.ui.chat

import com.inhuman.core.data.db.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A conversation exported as one self-describing document.
 *
 * ## Why this format exists
 *
 * The Markdown export is for reading: it loses reasoning signatures, provider
 * state, tool-call JSON and attachment paths, which is the right trade for a
 * document someone opens in another app. This bundle is the opposite trade --
 * every stored column that affects what the model would see is kept as its
 * original text, so the conversation can be inspected or replayed without
 * re-deriving anything from prose.
 *
 * ## Why the JSON is built by hand rather than with a serializer
 *
 * The stored form is already text in several columns (`toolCallsJson`,
 * `attachmentsJson`, `changedFilesJson`, `providerState`), and the point of the
 * format is to keep those strings byte-for-byte. A `@Serializable` DTO would
 * invite parsing and re-serializing them, which is how a tool-call id or a
 * provider signature ends up changed by an export. Reading and writing the tree
 * directly is also how the field names below stay stable: they are the same
 * names as [BundleMessage]'s properties, frozen by [ConversationBundleCodec.FORMAT].
 *
 * ## What it deliberately does not carry
 *
 * Message ids and parent ids. The bundle is one activity branch in order, not a
 * branch tree; ids would have to be rewritten on import anyway, and a merge
 * that trusts an exported id is a merge that can attach to the wrong row.
 * Attachments are listed by path; their bytes live in the zip next to this
 * document.
 */
data class ConversationBundle(
	val title: String,
	val sessionMode: String,
	val exportedAt: Long,
	val messages: List<BundleMessage>,
)

/**
 * One stored message, with every field the transcript row carries.
 *
 * Nullable fields are nullable for the same reason they are in
 * [MessageEntity]: a provider that reports no usage, or a user message that has
 * no reasoning, is not the same as a zero. `mutated` defaults to false because
 * old rows predate the column.
 */
data class BundleMessage(
	val role: String,
	val content: String,
	val reasoning: String?,
	val reasoningSignature: String?,
	val providerState: String?,
	val model: String?,
	val finishReason: String?,
	val createdAt: Long,
	val promptTokens: Int?,
	val completionTokens: Int?,
	val cachedPromptTokens: Int?,
	val reasoningTokens: Int?,
	val timeToFirstTokenMs: Long?,
	val generationMs: Long?,
	val error: String?,
	val toolCallsJson: String?,
	val toolCallId: String?,
	val toolName: String?,
	val attachmentsJson: String?,
	val changedFilesJson: String?,
	val mutated: Boolean,
)

/**
 * Encodes and decodes the bundle document.
 *
 * `format` is required and checked before anything else. Every other field is
 * optional on read: a document written by an older or hand-edited producer may
 * be missing a null field, and a missing nullable is null, not an error. Unknown
 * fields are ignored, and numbers may be either integers or integral floats --
 * some JSON writers emit `1.0` for a count.
 */
object ConversationBundleCodec {

	/** The one format this codec accepts. Bump it when a field changes meaning. */
	const val FORMAT = "inhuman.conversation/1"

	private const val DEFAULT_SESSION_MODE = "classic"

	/** Serialize one activity branch, in the order it is passed. */
	fun encode(
		title: String,
		sessionMode: String,
		exportedAt: Long,
		messages: List<MessageEntity>,
	): String = buildJsonObject {
		put("format", FORMAT)
		put("title", title)
		put("sessionMode", sessionMode)
		put("exportedAt", exportedAt)
		put("messages", buildJsonArray { messages.forEach { add(it.toBundleJson()) } })
	}.toString()

	/** Decode a bundle, or null when the document is not one of ours. */
	fun decode(json: String): ConversationBundle? {
		val root = try {
			Json.parseToJsonElement(json) as? JsonObject
		} catch (_: Exception) {
			null
		} ?: return null
		if (root.string("format") != FORMAT) return null
		val messagesElement = root["messages"]
		val messages = when (messagesElement) {
			null, is JsonNull -> emptyList()
			is JsonArray -> messagesElement.map { element ->
				(element as? JsonObject)?.toBundleMessage() ?: return null
			}
			else -> return null
		}
		return ConversationBundle(
			title = root.string("title").orEmpty(),
			sessionMode = root.string("sessionMode") ?: DEFAULT_SESSION_MODE,
			exportedAt = root.long("exportedAt") ?: 0L,
			messages = messages,
		)
	}

	/**
	 * Attachment paths from a row's `attachmentsJson`, in stored order.
	 *
	 * Anything that is not a `{"path": "..."}` object with a non-blank string
	 * path is skipped rather than guessed. This is the list the zip writer is
	 * handed, so a malformed entry must shrink the bundle's file set rather than
	 * crash the export or invent a path.
	 */
	fun attachmentPaths(attachmentsJson: String?): List<String> {
		if (attachmentsJson.isNullOrBlank()) return emptyList()
		val array = try {
			Json.parseToJsonElement(attachmentsJson) as? JsonArray
		} catch (_: Exception) {
			null
		} ?: return emptyList()
		return array.mapNotNull { element ->
			val obj = element as? JsonObject ?: return@mapNotNull null
			obj.string("path")?.takeIf { it.isNotBlank() }
		}
	}
}

private fun MessageEntity.toBundleJson(): JsonObject = buildJsonObject {
	put("role", role)
	put("content", content)
	reasoning?.let { put("reasoning", it) }
	reasoningSignature?.let { put("reasoningSignature", it) }
	providerState?.let { put("providerState", it) }
	model?.let { put("model", it) }
	finishReason?.let { put("finishReason", it) }
	put("createdAt", createdAt)
	promptTokens?.let { put("promptTokens", it) }
	completionTokens?.let { put("completionTokens", it) }
	cachedPromptTokens?.let { put("cachedPromptTokens", it) }
	reasoningTokens?.let { put("reasoningTokens", it) }
	timeToFirstTokenMs?.let { put("timeToFirstTokenMs", it) }
	generationMs?.let { put("generationMs", it) }
	error?.let { put("error", it) }
	toolCallsJson?.let { put("toolCallsJson", it) }
	toolCallId?.let { put("toolCallId", it) }
	toolName?.let { put("toolName", it) }
	attachmentsJson?.let { put("attachmentsJson", it) }
	changedFilesJson?.let { put("changedFilesJson", it) }
	if (mutated) put("mutated", true)
}

private fun JsonObject.toBundleMessage(): BundleMessage = BundleMessage(
	role = string("role").orEmpty(),
	content = string("content").orEmpty(),
	reasoning = string("reasoning"),
	reasoningSignature = string("reasoningSignature"),
	providerState = string("providerState"),
	model = string("model"),
	finishReason = string("finishReason"),
	createdAt = long("createdAt") ?: 0L,
	promptTokens = int("promptTokens"),
	completionTokens = int("completionTokens"),
	cachedPromptTokens = int("cachedPromptTokens"),
	reasoningTokens = int("reasoningTokens"),
	timeToFirstTokenMs = long("timeToFirstTokenMs"),
	generationMs = long("generationMs"),
	error = string("error"),
	toolCallsJson = string("toolCallsJson"),
	toolCallId = string("toolCallId"),
	toolName = string("toolName"),
	attachmentsJson = string("attachmentsJson"),
	changedFilesJson = string("changedFilesJson"),
	mutated = boolean("mutated") ?: false,
)

/**
 * A string, or null for a missing/null/non-string value.
 *
 * `contentOrNull` is deliberately not used: in kotlinx.serialization it is null
 * for every non-string primitive, which would make a JSON number look like a
 * missing string instead of a malformed one in a field that happens to want
 * text. Here the distinction never matters, but keeping the rule in one place
 * means it cannot become load-bearing by accident.
 */
private fun JsonObject.string(key: String): String? {
	val primitive = this[key] as? JsonPrimitive ?: return null
	if (primitive is JsonNull || !primitive.isString) return null
	return primitive.content
}

/** A missing nullable number, including a float spelling like `12.0`. */
private fun JsonObject.long(key: String): Long? {
	val primitive = this[key] as? JsonPrimitive ?: return null
	if (primitive is JsonNull || primitive.isString) return null
	return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
}

/** A nullable Int; out-of-range or fractional input is truncated like a Long. */
private fun JsonObject.int(key: String): Int? {
	val value = long(key) ?: return null
	return value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

private fun JsonObject.boolean(key: String): Boolean? {
	val primitive = this[key] as? JsonPrimitive ?: return null
	if (primitive is JsonNull || primitive.isString) return null
	return primitive.booleanOrNull
}
