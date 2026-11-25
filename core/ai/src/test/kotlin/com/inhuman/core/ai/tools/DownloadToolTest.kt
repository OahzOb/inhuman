package com.inhuman.core.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The download tool against fakes.
 *
 * The subject is the contract the model sees: which URLs are refused before a
 * socket, what happens when the server or the quota fails, and what the result
 * says. The HTTP client and the SSRF DNS guard are the host's job; the app-side
 * tests cover those, and the pure URL decisions are in [DownloadUrlTest].
 */
class DownloadToolTest {

	private class FakeFetcher(
		private val content: ByteArray = "hello".toByteArray(),
		private val fileName: String = "report.pdf",
		private val mimeType: String = "application/pdf",
		private val declared: Long? = content.size.toLong(),
		private val failure: Exception? = null,
	) : DownloadFetcher {
		var lastUrl: String? = null
		var closed = false

		override suspend fun fetch(url: String): FetchedDownload {
			lastUrl = url
			failure?.let { throw it }
			return FetchedDownload(
				fileName = fileName,
				mimeType = mimeType,
				contentLength = declared,
				body = ByteArrayInputStream(content),
				close = { closed = true },
			)
		}
	}

	private class FakeSink(
		private val result: DownloadSaveResult = DownloadSaveResult.Saved("downloads/report.pdf", 5),
		private val failure: Exception? = null,
	) : DownloadSink {
		var name: String? = null
		var maxBytes: Long? = null
		var bytes: ByteArray? = null

		override suspend fun save(
			suggestedName: String,
			maxBytes: Long,
			body: java.io.InputStream,
		): DownloadSaveResult {
			name = suggestedName
			this.maxBytes = maxBytes
			bytes = body.readBytes()
			failure?.let { throw it }
			return result
		}
	}

	private fun args(url: String? = "https://example.com/report.pdf") = buildJsonObject {
		url?.let { put("url", it) }
	}

	@Test
	fun `a public https url is passed to the fetcher and the bytes are kept`() {
		val fetcher = FakeFetcher()
		val sink = FakeSink()
		val out = runBlocking { DownloadTool(fetcher, sink).execute(args()) }

		assertFalse(out.content, out.isError)
		assertEquals("https://example.com/report.pdf", fetcher.lastUrl)
		assertEquals("report.pdf", sink.name)
		assertEquals(DownloadTool.MAX_BYTES, sink.maxBytes)
		// The body really reached the sink, not just the headers.
		assertEquals("hello", String(sink.bytes!!))
		assertTrue(fetcher.closed)
		assertTrue(out.content, out.content.contains("downloads/report.pdf"))
		assertTrue(out.content, out.content.contains("application/pdf"))
		assertTrue("the result must warn that the content is untrusted", out.content.contains("untrusted"))
	}

	@Test
	fun `a plaintext url is refused before the fetcher runs`() {
		val fetcher = FakeFetcher()
		val sink = FakeSink()
		val out = runBlocking { DownloadTool(fetcher, sink).execute(args("http://example.com/a")) }

		assertTrue(out.isError)
		assertTrue(out.content, out.content.contains("https"))
		assertNull(fetcher.lastUrl)
		assertNull(sink.name)
	}

	@Test
	fun `a loopback or private literal is refused before the fetcher runs`() {
		listOf(
			"https://127.0.0.1/a",
			"https://192.168.1.1/router",
			"https://[::1]/a",
		).forEach { url ->
			val fetcher = FakeFetcher()
			val out = runBlocking {
				DownloadTool(fetcher, FakeSink()).execute(args(url))
			}
			assertTrue("$url should be refused", out.isError)
			assertNull("$url must not reach the fetcher", fetcher.lastUrl)
		}
	}

	@Test
	fun `missing url is a normal error, not a crash`() {
		val out = runBlocking {
			DownloadTool(FakeFetcher(), FakeSink()).execute(args(null))
		}
		assertTrue(out.isError)
		assertTrue(out.content, out.content.contains("url"))
	}

	@Test
	fun `a transport failure becomes a result the model can read`() {
		val fetcher = FakeFetcher(failure = java.io.IOException("HTTP 404 Not Found"))
		val out = runBlocking { DownloadTool(fetcher, FakeSink()).execute(args()) }

		assertTrue(out.isError)
		assertTrue(out.content, out.content.contains("404"))
	}

	@Test
	fun `a declared size over the limit fails before the sink is used`() {
		val fetcher = FakeFetcher(declared = DownloadTool.MAX_BYTES + 1)
		val sink = FakeSink()
		val out = runBlocking { DownloadTool(fetcher, sink).execute(args()) }

		assertTrue(out.isError)
		assertTrue(out.content, out.content.contains("limit"))
		assertNull(sink.name)
		assertTrue(fetcher.closed)
	}

	@Test
	fun `a quota refusal from the sink is reported, not swallowed`() {
		val sink = FakeSink(
			result = DownloadSaveResult.Failed("not enough free space: 10 bytes available"),
		)
		val out = runBlocking { DownloadTool(FakeFetcher(), sink).execute(args()) }

		assertTrue(out.isError)
		assertTrue(out.content, out.content.contains("free space"))
	}

	@Test
	fun `a sink that throws is turned into an error instead of ending the turn`() {
		val sink = FakeSink(failure = IllegalStateException("disk on fire"))
		val out = runBlocking { DownloadTool(FakeFetcher(), sink).execute(args()) }

		assertTrue(out.isError)
		assertTrue(out.content, out.content.contains("disk on fire"))
	}

	@Test
	fun `the default limit is 64 MiB`() {
		assertEquals(64L * 1024 * 1024, DownloadTool.MAX_BYTES)
	}
}
