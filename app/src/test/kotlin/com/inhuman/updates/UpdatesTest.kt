package com.inhuman.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update manifest is the one place where a LAN server can hand the app an
 * APK. These tests pin the refusals: missing hash, public cleartext, wrong
 * scheme, relative URL resolution and the version comparison.
 */
class UpdatesTest {

	@Test
	fun `a private HTTP server is accepted`() {
		assertEquals("http://192.168.1.50:8787", normalizeServerUrl("http://192.168.1.50:8787/"))
		assertEquals("http://100.101.102.103:8787", normalizeServerUrl("http://100.101.102.103:8787"))
		assertEquals("http://desk.local:8787", normalizeServerUrl("http://desk.local:8787"))
		assertEquals("http://desk.tailnet.ts.net", normalizeServerUrl("http://desk.tailnet.ts.net"))
	}

	@Test
	fun `a public HTTP server is refused`() {
		assertNull(normalizeServerUrl("http://example.com:8787"))
		assertNull(normalizeServerUrl("http://8.8.8.8:8787"))
	}

	@Test
	fun `HTTPS is accepted anywhere`() {
		assertEquals("https://example.com/updates", normalizeServerUrl("https://example.com/updates/"))
	}

	@Test
	fun `bad schemes and missing hosts are refused`() {
		assertNull(normalizeServerUrl("ftp://192.168.1.50"))
		assertNull(normalizeServerUrl("http://"))
		assertNull(normalizeServerUrl(""))
		assertNull(normalizeServerUrl("http://user:pass@192.168.1.50:8787"))
		assertNull(normalizeServerUrl("http://192.168.1.50:8787/?x=1"))
	}

	@Test
	fun `a manifest is parsed and its relative URL is resolved`() {
		val info = parseUpdateInfo(
			"""
			{
			  "versionCode": 3,
			  "versionName": "0.2.1",
			  "url": "inhuman-0.2.1.apk",
			  "sha256": "${"a".repeat(64)}",
			  "notes": "Fix the thing."
			}
			""".trimIndent(),
			"http://192.168.1.50:8787",
		)!!

		assertEquals(3L, info.versionCode)
		assertEquals("0.2.1", info.versionName)
		assertEquals("http://192.168.1.50:8787/inhuman-0.2.1.apk", info.apkUrl)
		assertEquals("Fix the thing.", info.notes)
	}

	@Test
	fun `a missing or malformed hash is refused`() {
		val base = "http://192.168.1.50:8787"
		assertNull(parseUpdateInfo("""{"versionCode":3,"url":"a.apk"}""", base))
		assertNull(parseUpdateInfo("""{"versionCode":3,"url":"a.apk","sha256":"abc"}""", base))
		assertNull(parseUpdateInfo("""{"versionCode":3,"url":"a.apk","sha256":"${"g".repeat(64)}"}""", base))
	}

	@Test
	fun `a missing version or URL is refused`() {
		val hash = "b".repeat(64)
		assertNull(parseUpdateInfo("""{"url":"a.apk","sha256":"$hash"}""", "http://192.168.1.50:8787"))
		assertNull(parseUpdateInfo("""{"versionCode":3,"sha256":"$hash"}""", "http://192.168.1.50:8787"))
	}

	@Test
	fun `only a higher version code is an update`() {
		assertTrue(isNewerVersion(3, 2))
		assertFalse(isNewerVersion(2, 2))
		assertFalse(isNewerVersion(1, 2))
	}

	@Test
	fun `the endpoint paths are stable`() {
		assertEquals("http://192.168.1.50:8787/update.json", updateManifestUrl("http://192.168.1.50:8787"))
		assertEquals("http://192.168.1.50:8787/log", logUploadUrl("http://192.168.1.50:8787"))
		assertEquals(
			"http://192.168.1.50:8787/conversation",
			conversationUploadUrl("http://192.168.1.50:8787"),
		)
	}

	@Test
	fun `cleartext is only allowed for local addresses`() {
		assertTrue(isAllowedCleartextUrl("http://192.168.1.50:8787"))
		assertTrue(isAllowedCleartextUrl("http://100.64.0.1:8787"))
		assertTrue(isAllowedCleartextUrl("https://example.com"))
		assertFalse(isAllowedCleartextUrl("http://example.com"))
		assertFalse(isAllowedCleartextUrl("ftp://192.168.1.50"))
	}

	@Test
	fun `built-in update presets are valid HTTPS endpoints on the latest release`() {
		assertEquals(3, BUILT_IN_UPDATE_URLS.size)
		// A duplicate preset would be a dead chip: one tap writes the same
		// address another chip already wrote.
		assertEquals(BUILT_IN_UPDATE_URLS.size, BUILT_IN_UPDATE_URLS.toSet().size)
		for (url in BUILT_IN_UPDATE_URLS) {
			// Choosing a preset must always produce a value the field accepts;
			// otherwise the chip fills in a URL the next check refuses.
			assertEquals(url, normalizeServerUrl(url))
			assertTrue(url, url.startsWith("https://"))
			assertTrue(url, url.endsWith("/releases/latest/download"))
			// The manifest and the APK it names live in the same release, so a
			// release upload only has to place both assets next to each other.
			assertTrue(updateManifestUrl(url).endsWith("/latest/download/update.json"))
			assertEquals(
				"$url/inhuman-0.0.0-0.apk",
				resolveUpdateUrl(url, "inhuman-0.0.0-0.apk"),
			)
		}
	}
}
