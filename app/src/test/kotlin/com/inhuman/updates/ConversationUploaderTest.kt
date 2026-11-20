package com.inhuman.updates

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The conversation upload's wire shape.
 *
 * A real device test cannot assert what actually left the phone without
 * standing up a server, and the dialog only proves the button is wired. This
 * runs the client against a loopback `HttpServer`: the path, the Markdown body
 * and the version header are the three things the server contract depends on.
 */
class ConversationUploaderTest {

	private fun server(handler: (path: String, body: String, version: String?) -> Pair<Int, String>):
		Pair<HttpServer, AtomicReference<String>> {
		val seenPath = AtomicReference<String>()
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			val path = exchange.requestURI.path
			val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
			val version = exchange.requestHeaders.getFirst("X-Inhuman-Version")
			seenPath.set(path)
			val (status, responseText) = handler(path, body, version)
			val response = responseText.toByteArray()
			exchange.sendResponseHeaders(status, response.size.toLong())
			exchange.responseBody.use { it.write(response) }
		}
		server.start()
		return server to seenPath
	}

	@Test
	fun `upload posts Markdown to the conversation endpoint`() {
		val body = AtomicReference<String>()
		val version = AtomicReference<String>()
		val (server, path) = server { _, seenBody, seenVersion ->
			body.set(seenBody)
			version.set(seenVersion.orEmpty())
			200 to "20261105-190000.md"
		}
		try {
			val base = "http://127.0.0.1:${server.address.port}"
			val saved = ConversationUploader.upload(base, "# conversation\n\nhello", "0.9.1 (47)")
			assertEquals("20261105-190000.md", saved)
			assertEquals("/conversation", path.get())
			assertEquals("# conversation\n\nhello", body.get())
			assertEquals("0.9.1 (47)", version.get())
		} finally {
			server.stop(0)
		}
	}

	@Test
	fun `a rejected upload reports the status and body`() {
		val (server, _) = server { _, _, _ -> 413 to "upload too large" }
		try {
			ConversationUploader.upload(
				"http://127.0.0.1:${server.address.port}",
				"# too big",
				"0.9.1 (47)",
			)
			fail("a 413 must not look like a successful upload")
		} catch (failure: IllegalStateException) {
			assertTrue(
				"failure names the status: ${failure.message}",
				failure.message.orEmpty().contains("HTTP 413"),
			)
		} finally {
			server.stop(0)
		}
	}

	@Test
	fun `a body over the server ceiling is refused before connecting`() {
		val huge = "x".repeat(8 * 1024 * 1024 + 1)
		try {
			ConversationUploader.upload("http://127.0.0.1:1", huge, "0.9.1 (47)")
			fail("an oversized conversation must not be written to the socket")
		} catch (failure: IllegalArgumentException) {
			assertTrue(
				"failure names the ceiling: ${failure.message}",
				failure.message.orEmpty().contains("8 MB"),
			)
		}
	}
}
