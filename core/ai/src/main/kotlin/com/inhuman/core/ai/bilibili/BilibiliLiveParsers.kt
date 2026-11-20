package com.inhuman.core.ai.bilibili

import kotlinx.serialization.json.JsonObject

/**
 * Reads one live room, from either of the two endpoints the tool can reach.
 *
 * `room/v1/Room/get_info` is the rich answer (room id, uid, anchor, area and
 * online count) and is used when the caller knows the room id.
 * `room/v1/Room/getRoomInfoOld` answers by uploader uid and is thinner: it has
 * no anchor name and no area, which is why the model keeps the uid it was given
 * rather than inventing the missing fields.
 */
internal object BilibiliLiveParsers {

	fun parseRoom(root: JsonObject): BilibiliLiveRoom {
		val data = root.obj("data")
			?: throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"live room response had no data",
			)
		val roomId = data.long("room_id")
		val anchor = data.obj("anchor_info")?.obj("base_info")?.string("uname").orEmpty()
		return BilibiliLiveRoom(
			roomId = roomId,
			uid = data.long("uid"),
			anchor = anchor,
			title = BilibiliHtml.stripTags(data.string("title").orEmpty()),
			live = data.long("live_status") == 1L,
			areaName = data.string("area_name").orEmpty(),
			parentAreaName = data.string("parent_area_name").orEmpty(),
			online = data.long("online"),
			url = roomUrl(roomId),
		)
	}

	fun parseRoomByUid(root: JsonObject, mid: Long): BilibiliLiveRoom {
		val data = root.obj("data")
			?: throw BilibiliApiException(
				BilibiliApiException.TRANSPORT,
				"live room response had no data",
			)
		val roomId = data.long("roomid")
		return BilibiliLiveRoom(
			roomId = roomId,
			uid = mid,
			anchor = "",
			title = BilibiliHtml.stripTags(data.string("title").orEmpty()),
			live = data.long("liveStatus") == 1L,
			areaName = "",
			parentAreaName = "",
			online = data.long("online"),
			url = data.string("url").orEmpty().ifBlank { roomUrl(roomId) },
		)
	}

	/** A stable public address; the endpoint's own `url` may carry feed flags. */
	private fun roomUrl(roomId: Long): String =
		if (roomId > 0) "https://live.bilibili.com/$roomId" else ""
}
