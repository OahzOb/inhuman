package com.inhuman.core.ai.testing

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress

/**
 * A loopback HTTP server for provider tests: no network, no uptime dependency.
 *
 * Routes are keyed by path; the query string is ignored because the providers
 * under test only need a page body. HTTPS is not offered: the providers take a
 * `requireHttps` flag that the test sets to false, which is the same switch the
 * production code uses to refuse plaintext.
 */
class LocalHttpServer(
	private val routes: Map<String, Route>,
) : Closeable {

	data class Route(
		val status: Int = 200,
		val contentType: String? = "text/html; charset=utf-8",
		val body: String = "",
		val headers: Map<String, String> = emptyMap(),
	)

	private val server: HttpServer =
		HttpServer.create(InetSocketAddress("localhost", 0), 0)

	val baseUrl: String get() = "http://localhost:${server.address.port}"

	init {
		server.createContext("/") { exchange ->
			val route = routes[exchange.requestURI.path]
				?: Route(status = 404, contentType = "text/plain", body = "not found")
			route.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
			route.headers.forEach { (name, value) ->
				exchange.responseHeaders.add(name, value)
			}
			val bytes = route.body.toByteArray(Charsets.UTF_8)
			if (bytes.isEmpty()) {
				exchange.sendResponseHeaders(route.status, -1)
			} else {
				exchange.sendResponseHeaders(route.status, bytes.size.toLong())
			}
			exchange.responseBody.use { it.write(bytes) }
		}
		server.executor = null
		server.start()
	}

	override fun close() = server.stop(0)
}
