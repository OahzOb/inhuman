package com.inhuman.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * An image attached to a user message.
 *
 * The bytes stay in the conversation workspace; the row stores only a relative
 * path, MIME type and size. That keeps one source of truth for the image and
 * lets the transcript survive a restart without duplicating screenshots into
 * SQLite.
 *
 * The JSON shape matches the Android `attachmentsJson` (`path`, `mime`,
 * `size`), with an extra display `name` the desktop rail uses. Android ignores
 * unknown fields, so a database written by either host opens in the other.
 */
data class DesktopAttachment(
	val relativePath: String,
	val mimeType: String,
	val sizeBytes: Long,
	val displayName: String = relativePath.substringAfterLast('/'),
)

object DesktopAttachmentCodec {
	private val json = Json { ignoreUnknownKeys = true }

	fun encode(attachments: List<DesktopAttachment>): String? {
		if (attachments.isEmpty()) return null
		return buildJsonArray {
			attachments.forEach { attachment ->
				add(
					buildJsonObject {
						put("path", attachment.relativePath)
						put("mime", attachment.mimeType)
						put("size", attachment.sizeBytes)
						put("name", attachment.displayName)
					},
				)
			}
		}.toString()
	}

	fun decode(raw: String?): List<DesktopAttachment> {
		if (raw.isNullOrBlank()) return emptyList()
		return runCatching {
			json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
				val obj = element as? JsonObject ?: return@mapNotNull null
				val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				DesktopAttachment(
					relativePath = path,
					mimeType = obj["mime"]?.jsonPrimitive?.contentOrNull ?: "image/jpeg",
					sizeBytes = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
					displayName = obj["name"]?.jsonPrimitive?.contentOrNull
						?: path.substringAfterLast('/'),
				)
			}
		}.getOrDefault(emptyList())
	}
}
