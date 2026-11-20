package com.inhuman.core.ai.tools

import com.inhuman.core.ai.bilibili.BilibiliArticle
import com.inhuman.core.ai.bilibili.BilibiliBangumi
import com.inhuman.core.ai.bilibili.BilibiliComments
import com.inhuman.core.ai.bilibili.BilibiliCommentSort
import com.inhuman.core.ai.bilibili.BilibiliDanmaku
import com.inhuman.core.ai.bilibili.BilibiliLiveRoom
import com.inhuman.core.ai.bilibili.BilibiliReadService
import com.inhuman.core.ai.bilibili.BilibiliRankingCategory
import com.inhuman.core.ai.bilibili.BilibiliSearchFilters
import com.inhuman.core.ai.bilibili.BilibiliSearchHit
import com.inhuman.core.ai.bilibili.BilibiliSearchKind
import com.inhuman.core.ai.bilibili.BilibiliTranscript
import com.inhuman.core.ai.bilibili.BilibiliTranscriptPreference
import com.inhuman.core.ai.bilibili.BilibiliTrendingItem
import com.inhuman.core.ai.bilibili.BilibiliTrendingKind
import com.inhuman.core.ai.bilibili.BilibiliUp
import com.inhuman.core.ai.bilibili.BilibiliUpOrder
import com.inhuman.core.ai.bilibili.BilibiliVideoInfo
import com.inhuman.core.ai.bilibili.BilibiliVideoSummary

/**
 * A configurable Bilibili service for the tool tests.
 *
 * Why one shared fake rather than one per test file: the tool layer is where
 * argument handling, caps and result wording live, and every tool needs a
 * service that never touches the network. Duplicating the interface per file
 * made adding a method a four-file edit, which is how a fake drifts from the
 * contract it stands in for.
 */
internal class FakeBilibiliService : BilibiliReadService {

	var searchResult: List<BilibiliSearchHit> = emptyList()
	var videoResult: BilibiliVideoInfo? = null
	var relatedResult: List<BilibiliVideoSummary> = emptyList()
	var commentsResult: BilibiliComments = BilibiliComments(emptyList(), emptyList())
	var threadResult: BilibiliComments = BilibiliComments(emptyList(), emptyList())
	var articleResult: BilibiliArticle? = null
	var articleCommentsResult: BilibiliComments = BilibiliComments(emptyList(), emptyList())
	var transcriptResult: BilibiliTranscript? = null
	var upResult: BilibiliUp? = null
	var trendingResult: List<BilibiliTrendingItem> = emptyList()
	var bangumiResult: BilibiliBangumi? = null
	var liveResult: BilibiliLiveRoom? = null
	var danmakuResult: BilibiliDanmaku? = null
	var failure: Exception? = null
	override var loggedIn: Boolean = true

	var searchCalls = 0
	var videoCalls = 0
	var commentCalls = 0
	var articleCommentCalls = 0
	var transcriptCalls = 0
	var upCalls = 0
	var trendingCalls = 0
	var bangumiCalls = 0
	var liveCalls = 0
	var danmakuCalls = 0

	var lastLimit = 0
	var lastPage = 0
	var lastCommentPage = 0
	var lastBvid = ""
	var lastSort: BilibiliCommentSort? = null
	var lastCvId = 0L
	var lastPrefer: BilibiliTranscriptPreference? = null
	var lastKind: BilibiliSearchKind? = null
	var lastFilters: BilibiliSearchFilters? = null
	var lastRoot = 0L
	var lastLanguage: String? = null
	var lastFrom = -1L
	var lastTo = -1L
	var lastMid = 0L
	var lastUpPage = 0
	var lastUpOrder: BilibiliUpOrder? = null
	var lastTrendingKind: BilibiliTrendingKind? = null
	var lastTrendingCategory: BilibiliRankingCategory? = null
	var lastSeasonId = 0L
	var lastEpId = 0L
	var lastRoomId = 0L
	var lastLiveMid = 0L

	override suspend fun search(
		keyword: String,
		kind: BilibiliSearchKind,
		order: String,
		page: Int,
		limit: Int,
		filters: BilibiliSearchFilters,
	): List<BilibiliSearchHit> {
		failure?.let { throw it }
		searchCalls++
		lastKind = kind
		lastPage = page
		lastLimit = limit
		lastFilters = filters
		return searchResult
	}

	override suspend fun video(bvid: String): BilibiliVideoInfo {
		failure?.let { throw it }
		videoCalls++
		lastBvid = bvid
		return videoResult ?: error("video result not configured")
	}

	override suspend fun related(bvid: String, limit: Int): List<BilibiliVideoSummary> {
		failure?.let { throw it }
		lastBvid = bvid
		return relatedResult.take(limit)
	}

	override suspend fun comments(
		bvid: String,
		sort: BilibiliCommentSort,
		limit: Int,
		page: Int,
		root: Long,
	): BilibiliComments {
		failure?.let { throw it }
		commentCalls++
		lastSort = sort
		lastCommentPage = page
		lastRoot = root
		return if (root > 0) threadResult else commentsResult
	}

	override suspend fun article(cvId: Long): BilibiliArticle {
		failure?.let { throw it }
		lastCvId = cvId
		return articleResult ?: error("article result not configured")
	}

	override suspend fun articleComments(
		cvId: Long,
		sort: BilibiliCommentSort,
		limit: Int,
		page: Int,
	): BilibiliComments {
		failure?.let { throw it }
		articleCommentCalls++
		lastCvId = cvId
		lastSort = sort
		lastCommentPage = page
		return articleCommentsResult
	}

	override suspend fun transcript(
		bvid: String,
		page: Int,
		prefer: BilibiliTranscriptPreference,
		language: String?,
		fromSeconds: Long,
		toSeconds: Long,
	): BilibiliTranscript? {
		failure?.let { throw it }
		transcriptCalls++
		lastBvid = bvid
		lastPage = page
		lastPrefer = prefer
		lastLanguage = language
		lastFrom = fromSeconds
		lastTo = toSeconds
		return transcriptResult
	}

	override suspend fun uploader(
		mid: Long,
		page: Int,
		order: BilibiliUpOrder,
	): BilibiliUp {
		failure?.let { throw it }
		upCalls++
		lastMid = mid
		lastUpPage = page
		lastUpOrder = order
		return upResult ?: error("uploader result not configured")
	}

	override suspend fun trending(
		kind: BilibiliTrendingKind,
		limit: Int,
		category: BilibiliRankingCategory,
	): List<BilibiliTrendingItem> {
		failure?.let { throw it }
		trendingCalls++
		lastTrendingKind = kind
		lastTrendingCategory = category
		lastLimit = limit
		return trendingResult.take(limit)
	}

	override suspend fun bangumi(seasonId: Long, epId: Long): BilibiliBangumi {
		failure?.let { throw it }
		bangumiCalls++
		lastSeasonId = seasonId
		lastEpId = epId
		return bangumiResult ?: error("bangumi result not configured")
	}

	override suspend fun live(roomId: Long, mid: Long): BilibiliLiveRoom {
		failure?.let { throw it }
		liveCalls++
		lastRoomId = roomId
		lastLiveMid = mid
		return liveResult ?: error("live result not configured")
	}

	override suspend fun danmaku(
		bvid: String,
		page: Int,
		fromSeconds: Long,
		toSeconds: Long,
	): BilibiliDanmaku {
		failure?.let { throw it }
		danmakuCalls++
		lastBvid = bvid
		lastPage = page
		lastFrom = fromSeconds
		lastTo = toSeconds
		return danmakuResult ?: error("danmaku result not configured")
	}
}
