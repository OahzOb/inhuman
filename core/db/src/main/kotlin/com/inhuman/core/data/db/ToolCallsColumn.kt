package com.inhuman.core.data.db

import com.inhuman.core.ai.tools.SshArguments
import com.inhuman.core.ai.tools.ToolCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One tool call as the transcript stores it.
 *
 * Separate from the UI type on purpose: this is the durable wire record, and it
 * must survive a process restart without the UI having an opinion about it.
 * [argumentsJson] is kept as the provider sent it -- re-serialising a parsed
 * object would change key order and whitespace, and the request cache compares
 * the transcript it produced.
 */
data class StoredToolCall(
	val index: Int,
	val id: String?,
	val name: String?,
	val argumentsJson: String,
	/** Gemini's opaque `thoughtSignature` for this call, when one was returned. */
	val thoughtSignature: String? = null,
	/** OpenAI Responses' `fc_...` output-item id, when this call has one. */
	val providerItemId: String? = null,
)

/**
 * The `messages.toolCallsJson` column: a small JSON array.
 *
 * The format is the stored shape, so changing it loses the tool calls of an
 * existing conversation. SSH credentials are removed here rather than at the
 * call site so no writer can forget.
 */
object ToolCallsColumn {

	fun encode(calls: List<StoredToolCall>): String =
		calls.joinToString(prefix = "[", postfix = "]") { call ->
			buildJsonObject {
				put("index", call.index)
				call.id?.let { put("id", it) }
				call.name?.let { put("name", it) }
				call.thoughtSignature?.let { put("signature", it) }
				call.providerItemId?.let { put("item_id", it) }
				put(
					"arguments",
					if (isRemote(call.name)) {
						SshArguments.redact(call.argumentsJson)
					} else {
						call.argumentsJson
					},
				)
			}.toString()
		}

	fun decode(raw: String?): List<StoredToolCall> {
		if (raw.isNullOrBlank()) return emptyList()
		return runCatching {
			(Json.parseToJsonElement(raw) as? JsonArray).orEmpty().mapNotNull { element ->
				val obj = element as? JsonObject ?: return@mapNotNull null
				StoredToolCall(
					index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
					id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
					name = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
					argumentsJson = obj["arguments"]?.jsonPrimitive?.content.orEmpty(),
					thoughtSignature = obj["signature"]?.jsonPrimitive?.content
						?.takeIf { it.isNotBlank() },
					providerItemId = obj["item_id"]?.jsonPrimitive?.content
						?.takeIf { it.isNotBlank() },
				)
			}
		}.getOrDefault(emptyList())
	}

	private fun isRemote(name: String?): Boolean =
		name != null && ToolCatalog.all.firstOrNull { it.name == name }?.remote == true
}
