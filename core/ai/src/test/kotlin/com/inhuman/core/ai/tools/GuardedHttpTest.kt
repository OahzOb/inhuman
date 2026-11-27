package com.inhuman.core.ai.tools

import com.inhuman.core.ai.testing.LocalHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedHttpTest {

	private val userAgent = "test-agent"

	@Test
	fun `a hostname that resolves private is refused at connect time`() = runBlocking {
		// localhost is not a literal IP, so URL validation passes; the DNS
		// check at connect time is what refuses it.
		val failure = runCatching {
			GuardedHttp.default.getBytes(
				raw = "http://localhost:1/",
				requireHttps = false,
				userAgent = userAgent,
			)
		}.exceptionOrNull()

		assertNotNull("localhost should be refused by the default client", failure)
	}

	@Test
	fun `a private literal is refused before any network work`() {
		val failure = runCatching {
			runBlocking {
				GuardedHttp.default.getBytes(
					raw = "http://127.0.0.1:1/",
					requireHttps = false,
					userAgent = userAgent,
				)
			}
		}.exceptionOrNull()

		assertTrue(failure is IllegalArgumentException)
	}

	@Test
	fun `http is refused when https is required`() {
		val server = LocalHttpServer(mapOf("/" to LocalHttpServer.Route(body = "ok")))
		try {
			val failure = runCatching {
				runBlocking {
					GuardedHttp.forTesting().getBytes(
						raw = "${server.baseUrl}/",
						requireHttps = true,
						userAgent = userAgent,
					)
				}
			}.exceptionOrNull()
			assertTrue(failure is IllegalArgumentException)
		} finally {
			server.close()
		}
	}

	@Test
	fun `a body larger than the cap comes back as a flagged prefix`() = runBlocking {
		val server = LocalHttpServer(
			mapOf("/big" to LocalHttpServer.Route(body = "x".repeat(100))),
		)
		try {
			val response = GuardedHttp.forTesting().getBytes(
				raw = "${server.baseUrl}/big",
				requireHttps = false,
				userAgent = userAgent,
				maxBytes = 10,
			)

			assertTrue(response.truncated)
			assertEquals(10, response.bytes.size)
		} finally {
			server.close()
		}
	}

	@Test
	fun `redirects are followed and the final url is reported`() = runBlocking {
		val server = LocalHttpServer(
			mapOf(
				"/a" to LocalHttpServer.Route(
					status = 302,
					body = "",
					headers = mapOf("Location" to "/b"),
				),
				"/b" to LocalHttpServer.Route(body = "landed"),
			),
		)
		try {
			val response = GuardedHttp.forTesting().getBytes(
				raw = "${server.baseUrl}/a",
				requireHttps = false,
				userAgent = userAgent,
			)

			assertEquals("/b", response.finalUri.path)
			assertEquals(200, response.status)
			assertEquals("landed", response.bytes.toString(Charsets.UTF_8))
		} finally {
			server.close()
		}
	}

	@Test
	fun `a redirect to a private literal is refused`() {
		val server = LocalHttpServer(
			mapOf(
				"/a" to LocalHttpServer.Route(
					status = 302,
					body = "",
					headers = mapOf("Location" to "http://127.0.0.1:1/x"),
				),
			),
		)
		try {
			val failure = runCatching {
				runBlocking {
					GuardedHttp.forTesting().getBytes(
						raw = "${server.baseUrl}/a",
						requireHttps = false,
						userAgent = userAgent,
					)
				}
			}.exceptionOrNull()
			assertTrue(failure is IllegalArgumentException)
		} finally {
			server.close()
		}
	}

	@Test
	fun `an http error is returned rather than thrown`() = runBlocking {
		val server = LocalHttpServer(
			mapOf("/missing" to LocalHttpServer.Route(status = 404, body = "nope")),
		)
		try {
			val response = GuardedHttp.forTesting().getBytes(
				raw = "${server.baseUrl}/missing",
				requireHttps = false,
				userAgent = userAgent,
			)

			assertEquals(404, response.status)
			assertTrue(!response.isSuccessful)
		} finally {
			server.close()
		}
	}
}
