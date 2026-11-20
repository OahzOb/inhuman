package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliErrors
import com.inhuman.core.ai.bilibili.BilibiliLiveRoom
import com.inhuman.core.ai.bilibili.BilibiliReadService
import kotlinx.serialization.json.JsonObject

/**
 * One Bilibili live room: is it on, what is it about, and how big is it.
 *
 * ## Why this is a separate tool
 *
 * "Is this person streaming now" is a live question about a live state, not a
 * page read: the answer changes while the conversation is open, and the tool is
 * the only way to ask it. `bili_up` also prints the live state it sees in the
 * uploader page, but that is a snapshot of a different endpoint and not always
 * current.
 *
 * ## Why the id can be a room or an uploader
 *
 * People arrive with either: a room URL contains `live.bilibili.com/<room id>`,
 * while a question names an uploader. The two endpoint answers are slightly
 * different (the room-by-uid one carries no anchor name or area), so the result
 * only claims what it has.
 */
class BilibiliLiveTool(
	private val service: BilibiliReadService,
) : Tool {

	override val name = "bili_live"

	override val mutates = false

	override val description = """
		Read one Bilibili live room's state: live or offline, title, streamer, area and viewer
		count.

		Pass `room_id` (the number in a live.bilibili.com/12345 link) or `mid` (the
		uploader's id) when only the person is known. This is public metadata and needs no
		account: it does not play the stream and cannot read live chat, which is a different
		permission than these read tools have.

		${BilibiliToolSupport.UNTRUSTED_NOTE}
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"room_id" to mapOf(
				"type" to "integer",
				"description" to "The live room id, for example 22159237.",
			),
			"mid" to mapOf(
				"type" to "integer",
				"description" to "The streamer's uploader id, when the room id is not known.",
			),
		),
		"required" to emptyList<String>(),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val roomId = BilibiliToolSupport.long(arguments, "room_id").takeIf { it > 0 }
		val mid = BilibiliToolSupport.long(arguments, "mid").takeIf { it > 0 }
		if (roomId == null && mid == null) {
			return ToolResult.error("pass either 'room_id' or 'mid'")
		}
		return try {
			ToolResult.ok(format(service.live(roomId ?: 0L, mid ?: 0L), roomId == null))
		} catch (error: BilibiliApiException) {
			ToolResult.error(BilibiliErrors.describe(error))
		} catch (error: Exception) {
			ToolResult.error("Bilibili live read failed: ${error.message ?: error.javaClass.simpleName}")
		}
	}

	private fun format(room: BilibiliLiveRoom, answeredByUid: Boolean): String = buildString {
		appendLine(
			"room ${room.roomId} — " + BilibiliToolSupport.oneLine(room.title, TITLE_CHARS),
		)
		appendLine("url: ${BilibiliToolSupport.liveUrl(room.roomId)}")
		appendLine(if (room.live) "status: live now" else "status: offline")
		if (room.anchor.isNotBlank()) {
			appendLine(
				"streamer: ${BilibiliToolSupport.oneLine(room.anchor, AUTHOR_CHARS)}" +
					if (room.uid > 0) " (uid ${room.uid})" else "",
			)
		} else if (answeredByUid && room.uid > 0) {
			appendLine("streamer: uid ${room.uid}")
		}
		if (room.areaName.isNotBlank() || room.parentAreaName.isNotBlank()) {
			appendLine(
				"area: " +
					listOf(room.parentAreaName, room.areaName)
						.filter { it.isNotBlank() }
						.joinToString(" · "),
			)
		}
		if (room.online > 0) {
			appendLine("viewers: ${BilibiliToolSupport.number(room.online)}")
		}
		if (!answeredByUid && room.roomId > 0 && !room.live) {
			appendLine("The room exists but is not streaming right now.")
		}
	}

	companion object {
		private const val TITLE_CHARS = 200
		private const val AUTHOR_CHARS = 60
	}
}
