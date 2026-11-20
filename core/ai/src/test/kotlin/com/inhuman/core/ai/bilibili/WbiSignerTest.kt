package com.inhuman.core.ai.bilibili

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The signature is pinned to a vector computed by the reference implementation
 * (the Python library the live endpoints were verified against) for a fixed
 * timestamp. The mixin key in the vector is produced by the same function from
 * a fixed pair of nav URLs, so neither half depends on today's server keys:
 * they test arithmetic, not the network.
 *
 * The keyword deliberately contains Chinese, a space between words and a `~`.
 * The tilde is the character where `java.net.URLEncoder` and the server disagree;
 * if [WbiSigner.encode] is ever "simplified" into URLEncoder, this test fails
 * with a signature mismatch instead of the app failing with an opaque `-403`.
 */
class WbiSignerTest {

	private val imgUrl = "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"
	private val subUrl = "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"

	@Test
	fun `mixin key permutes the concatenated file names and keeps 32`() {
		assertEquals(
			"ea1db124af3c7062474693fa704f4ff8",
			WbiSigner.mixinKey(imgUrl, subUrl),
		)
	}

	@Test
	fun `signature matches the reference implementation`() {
		val params = mapOf(
			"keyword" to "原神 test~a b",
			"search_type" to "video",
			"page" to "1",
			"page_size" to "5",
		)
		val signed = WbiSigner.sign(
			params = params,
			mixinKey = "ea1db124af3c7062474693fa704f4ff8",
			wts = 1_777_777_777,
		)

		assertEquals("1777777777", signed["wts"])
		assertEquals(WbiSigner.DEFAULT_WEB_LOCATION, signed["web_location"])
		assertEquals("4c040fa118e4ae17cc9613b9e2b1653b", signed["w_rid"])
	}

	@Test
	fun `caller supplied web_location survives signing`() {
		val signed = WbiSigner.sign(
			params = mapOf("web_location" to "1315875", "keyword" to "x"),
			mixinKey = "ea1db124af3c7062474693fa704f4ff8",
			wts = 1_777_777_777,
		)
		assertEquals("1315875", signed["web_location"])
	}

	@Test
	fun `encoding follows the server and not URLEncoder`() {
		// URLEncoder would emit %7E for the tilde and %2A for an asterisk.
		assertEquals("%E5%8E%9F%E7%A5%9E+test~a+b", WbiSigner.encode("原神 test~a b"))
		assertEquals("a%3Db%26c", WbiSigner.encode("a=b&c"))
		assertEquals("A-Z_a.z~0", WbiSigner.encode("A-Z_a.z~0"))
	}
}
