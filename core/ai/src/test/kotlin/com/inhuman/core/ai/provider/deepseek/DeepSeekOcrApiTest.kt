package com.inhuman.core.ai.provider.deepseek

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OCR client without a network.
 *
 * The shape that matters is the multipart request (`purpose` then `file`), the
 * page parser, and the locator-token cleaner: the API wraps located text in
 * `<｜ref｜>`/`<｜box｜>` markers whose coordinates are not content.
 */
class DeepSeekOcrApiTest {

	@Test
	fun `the multipart request sends purpose before the file`() = runBlocking {
		SpeechOcrServer { TestResponse(200, "application/json", RESULT) }.use { server ->
			val api = DeepSeekOcrApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val result = api.recognize(byteArrayOf(1, 2, 3, 4), "scan.pdf")

			assertEquals(2, result.pageCount)
			assertEquals(2, result.pages.size)
			val request = server.requests.single()
			assertEquals("POST", request.method)
			assertEquals("/v1/tools/ocr", request.path)
			assertEquals("Bearer test-key", request.authorization)
			val contentType = request.contentType.orEmpty()
			assertTrue("not multipart: $contentType", contentType.startsWith("multipart/form-data"))
			val purposeAt = request.bodyText.indexOf("name=\"purpose\"")
			val fileAt = request.bodyText.indexOf("name=\"file\"")
			assertTrue("no purpose part", purposeAt >= 0)
			assertTrue("no file part", fileAt >= 0)
			assertTrue("purpose must come first", purposeAt < fileAt)
			assertTrue(
				"text_recognition was not requested",
				request.bodyText.contains("text_recognition"),
			)
			assertTrue("the filename is missing", request.bodyText.contains("scan.pdf"))
		}
	}

	@Test
	fun `page text drops locator tags and box coordinates`() = runBlocking {
		SpeechOcrServer { TestResponse(200, "application/json", RESULT) }.use { server ->
			val api = DeepSeekOcrApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val pages = api.recognize(byteArrayOf(9), "scan.png").pages

			assertEquals("标题正文", pages[0].text)
			assertEquals(false, pages[0].truncated)
			assertEquals("第二页", pages[1].text)
			assertEquals(true, pages[1].truncated)
		}
	}

	@Test
	fun `clean text keeps paragraphs and removes tokens`() {
		val raw = "<｜ref｜>第一段<｜/ref｜>\n\n\n<｜box｜>[[52, 40, 948, 96]]<｜/box｜>第二段  \n"
		assertEquals("第一段\n\n第二段", DeepSeekOcrApi.cleanPageText(raw))
	}

	@Test
	fun `an error object inside a 200 response is a failure`() {
		val body = """{"error":{"message":"OCR did not finish in time","type":"gateway_timeout_error"}}"""
		val failure = assertThrows(IOException::class.java) {
			DeepSeekOcrApi.parseResult(body)
		}
		assertTrue(
			"the service message should survive: ${failure.message}",
			failure.message.orEmpty().contains("OCR did not finish in time"),
		)
	}

	@Test
	fun `a refused request carries the service message`() {
		SpeechOcrServer {
			TestResponse(
				400,
				"application/json",
				"""{"error":{"message":"The file is password-protected and cannot be processed."}}""",
			)
		}.use { server ->
			val api = DeepSeekOcrApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val failure = assertThrows(IOException::class.java) {
				runBlocking { api.recognize(byteArrayOf(1), "scan.pdf") }
			}
			assertTrue(
				"the password message should survive: ${failure.message}",
				failure.message.orEmpty().contains("password-protected"),
			)
		}
	}

	@Test
	fun `an empty file is refused before any request`() {
		SpeechOcrServer {
			TestResponse(200, "application/json", RESULT)
		}.use { server ->
			val api = DeepSeekOcrApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val failure = assertThrows(IOException::class.java) {
				runBlocking { api.recognize(ByteArray(0), "scan.pdf") }
			}
			assertTrue(failure.message.orEmpty().contains("empty"))
			assertEquals(0, server.requests.size)
		}
	}

	private data class TestResponse(
		val status: Int,
		val contentType: String?,
		val body: String,
	) {
		constructor(status: Int, contentType: String?, bytes: ByteArray) :
			this(status, contentType, String(bytes, Charsets.ISO_8859_1))
	}

	private class SpeechOcrServer(
		private val handler: (Recorded) -> TestResponse,
	) : AutoCloseable {

		data class Recorded(
			val method: String,
			val path: String,
			val authorization: String?,
			val contentType: String?,
			val bodyBytes: ByteArray,
		) {
			/** ISO-8859-1 keeps multipart file bytes one char per byte. */
			val bodyText: String get() = String(bodyBytes, Charsets.ISO_8859_1)
		}

		private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		val requests = CopyOnWriteArrayList<Recorded>()
		val baseUrl: String

		init {
			http.createContext("/") { exchange ->
				val recorded = Recorded(
					method = exchange.requestMethod,
					path = exchange.requestURI.path,
					authorization = exchange.requestHeaders.getFirst("Authorization"),
					contentType = exchange.requestHeaders.getFirst("Content-Type"),
					bodyBytes = exchange.requestBody.readBytes(),
				)
				requests += recorded
				val response = handler(recorded)
				response.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
				// JSON responses are UTF-8; the request side uses ISO-8859-1
				// only to keep multipart file bytes one char per byte.
				val bytes = response.body.toByteArray(Charsets.UTF_8)
				exchange.sendResponseHeaders(response.status, bytes.size.toLong())
				exchange.responseBody.use { it.write(bytes) }
			}
			http.start()
			baseUrl = "http://127.0.0.1:${http.address.port}"
		}

		override fun close() {
			http.stop(0)
		}
	}

	private companion object {
		const val RESULT = """
			{"id":"ocr-1","object":"ocr.result","page_count":2,"pages":[
			  {"index":0,"text":"<｜ref｜>标题<｜/ref｜><｜box｜>[[52, 40, 948, 96]]<｜/box｜>正文","finish_reason":"stop"},
			  {"index":1,"text":"第二页","finish_reason":"length"}
			],"usage":{"input_tokens":10,"output_tokens":5}}
		"""
	}
}
