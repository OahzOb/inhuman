package com.inhuman.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URI

/**
 * The checks that keep a user-initiated download from becoming an SSRF gadget.
 *
 * The network itself is not exercised here: a unit test cannot pretend to be a
 * public host, and the mirror of that is that a test which only checks string
 * parsing would not prove the DNS guard runs. These cover the pure decisions;
 * the dialog is the device path.
 */
class UrlDownloaderTest {

	@Test
	fun `download URLs must be http or https without credentials`() {
		assertEquals("https://example.com/a", UrlDownloader.validateUrl("https://example.com/a").toString())
		listOf(
			"",
			"ftp://example.com/a",
			"file:///etc/passwd",
			"https://user:pass@example.com/a",
			"https:///no-host",
		).forEach { raw ->
			runCatching { UrlDownloader.validateUrl(raw) }
				.onSuccess { throw AssertionError("accepted $raw") }
		}
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
				UrlDownloader.isBlockedAddress(InetAddress.getByName(literal)),
			)
		}
		assertFalse(UrlDownloader.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
		assertFalse(UrlDownloader.isBlockedAddress(InetAddress.getByName("1.1.1.1")))
	}

	@Test
	fun `file names come from the header, the path, or a fallback`() {
		assertEquals(
			"report.pdf",
			UrlDownloader.fileNameFor(
				URI("https://example.com/ignored"),
				"attachment; filename=\"report.pdf\"",
			),
		)
		assertEquals(
			"résumé.pdf",
			UrlDownloader.fileNameFor(
				URI("https://example.com/ignored"),
				"attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf",
			),
		)
		assertEquals(
			"b.pdf",
			UrlDownloader.fileNameFor(URI("https://example.com/a/b.pdf?token=secret"), null),
		)
		assertEquals(
			"download",
			UrlDownloader.fileNameFor(URI("https://example.com/"), null),
		)
	}

	@Test
	fun `a hostile file name cannot become a path`() {
		val name = UrlDownloader.sanitizeName("../../etc/passwd")
		assertFalse(name.contains('/'))
		assertFalse(name.contains('\\'))
		assertFalse(name.startsWith(".."))
	}
}
