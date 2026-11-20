package com.inhuman.core.ai.bilibili

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The logged-in read path against the live service.
 *
 * ## Why this is skipped by default
 *
 * It needs a real Bilibili session — which is an account credential — and a
 * network. A committed test cannot carry either. The session is read from a
 * file path named by `INHUMAN_BILI_SESSION`, whose content is the local
 * `.tmp/bili-login/session.json` shape (a `cookies` object). That file is
 * gitignored and deleted after a research session; the test is skipped when the
 * variable is absent.
 *
 * Run deliberately:
 *
 * ```
 * INHUMAN_BILI_SESSION=C:\path\to\session.json \
 *   ./gradlew :core:ai:test --tests '*BilibiliLoginLiveTest'
 * ```
 *
 * What it adds over the fixtures: whether the player/summary/comment-page-two
 * requests, headers and cookies are shaped right.
 */
class BilibiliLoginLiveTest {

	private val sessionPath: String? = System.getenv("INHUMAN_BILI_SESSION")

	@Test
	fun `logged-in transcript and comment page two`() = runBlocking {
		val file = sessionPath?.let(::File)
		assumeTrue(
			"INHUMAN_BILI_SESSION not set; skipping the logged-in live call",
			file != null && file.isFile,
		)
		val credentials = readSession(file!!)
		assumeTrue("the session file carries no SESSDATA/DedeUserID", credentials != null)

		val client = BilibiliClient(credentials = { credentials })
		assertTrue("the client did not consider the session usable", client.loggedIn)

		val transcript = client.transcript(
			bvid = "BV1kMxM6FECM",
			page = 1,
			prefer = BilibiliTranscriptPreference.TEXT,
		)
		assertNotNull("the live transcript returned nothing", transcript)
		assertTrue("the transcript text is empty", transcript!!.text.isNotBlank())
		assertEquals(BilibiliTranscriptSource.SUBTITLE, transcript.source)
		println(
			"=== live transcript: ${transcript.source} ${transcript.language} " +
				"${transcript.segments} segments, ${transcript.text.length} chars ===",
		)

		val pageTwo = client.comments("BV1f4411M7QC", BilibiliCommentSort.HOT, 20, page = 2)
		assertTrue(
			"comment page 2 came back empty; the login may not be reaching the request",
			pageTwo.comments.isNotEmpty() || pageTwo.pinned.isNotEmpty(),
		)
		println("=== live comments page 2: ${pageTwo.comments.size} comments ===")
	}

	/** Read the scratch login file produced by the QR research helper. */
	private fun readSession(file: File): BilibiliCredentials? {
		val root = runCatching {
			Json.parseToJsonElement(file.readText()).jsonObject
		}.getOrNull() ?: return null
		val cookies = root["cookies"] as? JsonObject ?: return null
		val sessdata = (cookies["SESSDATA"] as? JsonPrimitive)?.content ?: return null
		val dedeUserId = (cookies["DedeUserID"] as? JsonPrimitive)?.content ?: return null
		return BilibiliCredentials(
			sessdata = sessdata,
			dedeUserId = dedeUserId,
		).takeIf { it.isUsable }
	}
}
