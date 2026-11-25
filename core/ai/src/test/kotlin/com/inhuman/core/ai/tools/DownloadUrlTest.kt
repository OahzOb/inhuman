package com.inhuman.core.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URI

/**
 * The pure decisions behind an app-proxied download.
 *
 * These are string and address questions only. Whether the DNS guard actually
 * runs at connect time is the host's test; the shared object exists so both
 * callers (the Files downloader and the tool) cannot disagree about the rules.
 */
class DownloadUrlTest {

	@Test
	fun `the tool only accepts https`() {
		assertEquals(
			"https://example.com/a",
			DownloadUrl.validate("https://example.com/a").toString(),
		)
		assertTrue(
			"the user-facing downloader still accepts http",
			DownloadUrl.validate("http://example.com/a", requireHttps = false).scheme == "http",
		)
		listOf(
			"",
			"http://example.com/a",
			"ftp://example.com/a",
			"file:///etc/passwd",
			"https://user:pass@example.com/a",
			"https:///no-host",
		).forEach { raw ->
			runCatching { DownloadUrl.validate(raw, requireHttps = true) }
				.onSuccess { throw AssertionError("accepted $raw") }
		}
	}

	@Test
	fun `an absurdly long url is refused before any request`() {
		val longUrl = "https://example.com/" + "a".repeat(DownloadUrl.MAX_CHARS)
		runCatching { DownloadUrl.validate(longUrl) }
			.onSuccess { throw AssertionError("accepted a URL over the length limit") }
	}

	@Test
	fun `private and special addresses are blocked`() {
		listOf(
			"127.0.0.1",
			"10.1.2.3",
			"172.16.0.1",
			"192.168.1.1",
			"169.254.169.254",
			"100.64.0.1",
			"198.18.0.1",
			"240.0.0.1",
			"224.0.0.1",
			"::1",
			"fc00::1",
		).forEach { literal ->
			assertTrue(
				"$literal should be blocked",
				DownloadUrl.isBlockedAddress(InetAddress.getByName(literal)),
			)
		}
		assertFalse(DownloadUrl.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
		assertFalse(DownloadUrl.isBlockedAddress(InetAddress.getByName("1.1.1.1")))
	}

	@Test
	fun `a literal private host in the url is refused, a public literal is not`() {
		runCatching { DownloadUrl.validate("https://127.0.0.1/x") }
			.onSuccess { throw AssertionError("accepted loopback") }
		runCatching { DownloadUrl.validate("https://[::1]/x") }
			.onSuccess { throw AssertionError("accepted IPv6 loopback") }
		assertEquals(
			"8.8.8.8",
			DownloadUrl.validate("https://8.8.8.8/x").host,
		)
	}

	@Test
	fun `file names come from the header, the path, or a fallback`() {
		assertEquals(
			"report.pdf",
			DownloadUrl.fileNameFor(
				URI("https://example.com/ignored"),
				"attachment; filename=\"report.pdf\"",
			),
		)
		assertEquals(
			"résumé.pdf",
			DownloadUrl.fileNameFor(
				URI("https://example.com/ignored"),
				"attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf",
			),
		)
		assertEquals(
			"b.pdf",
			DownloadUrl.fileNameFor(URI("https://example.com/a/b.pdf?token=secret"), null),
		)
		assertEquals(
			"download",
			DownloadUrl.fileNameFor(URI("https://example.com/"), null),
		)
	}

	@Test
	fun `a hostile file name cannot become a path`() {
		val name = DownloadUrl.sanitizeName("../../etc/passwd")
		assertFalse(name.contains('/'))
		assertFalse(name.contains('\\'))
		assertFalse(name.startsWith(".."))
	}
}
