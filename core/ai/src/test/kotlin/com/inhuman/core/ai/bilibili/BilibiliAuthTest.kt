package com.inhuman.core.ai.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR flow's pure halves: the state mapping, the cookie extraction and the
 * stored shape.
 *
 * These are the three places a wrong line produces a stuck dialog rather than an
 * error: a state code mapped to the wrong branch, a cookie taken from an
 * attribute instead of the first pair, or a credentials blob that decodes into
 * something half-present. None needs a network or an account to test.
 */
class BilibiliAuthTest {

	@Test
	fun `qr state codes map to the four states`() {
		assertEquals(BilibiliQrProtocol.StateKind.DONE, BilibiliQrProtocol.stateKind(0))
		assertEquals(BilibiliQrProtocol.StateKind.WAITING, BilibiliQrProtocol.stateKind(86101))
		assertEquals(BilibiliQrProtocol.StateKind.SCANNED, BilibiliQrProtocol.stateKind(86090))
		assertEquals(BilibiliQrProtocol.StateKind.EXPIRED, BilibiliQrProtocol.stateKind(86038))
		assertEquals(BilibiliQrProtocol.StateKind.UNKNOWN, BilibiliQrProtocol.stateKind(12345))
		assertEquals(BilibiliQrProtocol.StateKind.UNKNOWN, BilibiliQrProtocol.stateKind(null))
	}

	@Test
	fun `set-cookie parsing takes the first pair only`() {
		val cookies = BilibiliQrProtocol.parseCookies(
			listOf(
				"SESSDATA=abc%2Cdef; Path=/; Domain=.bilibili.com; HttpOnly",
				"DedeUserID=42; Path=/",
				"bili_jct=csrf-value; Path=/",
				"malformed",
			),
		)

		assertEquals("abc%2Cdef", cookies["SESSDATA"])
		assertEquals("42", cookies["DedeUserID"])
		assertEquals("csrf-value", cookies["bili_jct"])
		assertEquals(3, cookies.size)
	}

	@Test
	fun `credentials need both cookies and keep no write token`() {
		assertNull(
			"one cookie is not a login",
			BilibiliQrProtocol.credentialsFrom(mapOf("SESSDATA" to "s")),
		)

		val credentials = BilibiliQrProtocol.credentialsFrom(
			mapOf("SESSDATA" to "s", "DedeUserID" to "7", "bili_jct" to "x"),
			savedAtMillis = 5,
		)

		assertTrue(credentials != null)
		assertEquals("s", credentials!!.sessdata)
		assertEquals("7", credentials.dedeUserId)
		assertEquals(5L, credentials.savedAtMillis)
		// There is no field for bili_jct on purpose: the write token is not
		// stored, so a leaked store has nothing to post with.
		assertTrue(credentials.isUsable)
	}

	@Test
	fun `stored credentials round trip and garbage reads as logged out`() {
		val credentials = BilibiliCredentials(
			sessdata = "session",
			dedeUserId = "7",
			userName = "someone",
			savedAtMillis = 123,
		)
		assertEquals(credentials, BilibiliCredentials.fromStoredJson(credentials.toStoredJson()))

		assertNull(BilibiliCredentials.fromStoredJson(null))
		assertNull(BilibiliCredentials.fromStoredJson(""))
		assertNull(BilibiliCredentials.fromStoredJson("not json"))
		assertNull(
			"a half-written value must not read as logged in",
			BilibiliCredentials.fromStoredJson("""{"sessdata":"s"}"""),
		)
	}
}
