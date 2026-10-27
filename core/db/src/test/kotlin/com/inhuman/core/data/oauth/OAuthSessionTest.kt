package com.inhuman.core.data.oauth

import com.inhuman.core.ai.oauth.OAuthConfig
import com.inhuman.core.ai.oauth.OAuthProtocol
import com.inhuman.core.data.secret.SecretStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

/**
 * The session's storage and exchange, with a fake token endpoint.
 *
 * A real OAuth registration needs a browser and an account; what can be proven
 * here is the part that would otherwise leak or corrupt state: the code is
 * exchanged with the stored verifier, the token is written behind the secret
 * store, a wrong callback state does not cancel the real sign-in, and an
 * expired token refreshes with the stored refresh token.
 */
class OAuthSessionTest {

	private class MapSecret : SecretStore {
		val values = mutableMapOf<String, String>()
		override fun put(key: String, value: String) { values[key] = value }
		override fun get(key: String): String? = values[key]
		override fun remove(key: String) { values.remove(key) }
		override fun has(key: String): Boolean = values[key] != null
	}

	private val config = OAuthConfig(
		authorizationEndpoint = "https://auth.example.test/authorize",
		tokenEndpoint = "https://auth.example.test/token",
		clientId = "client-1",
		scopes = listOf("chat"),
	)

	private val forms = mutableListOf<String>()

	private fun client(token: String): OkHttpClient = OkHttpClient.Builder()
		.addInterceptor { chain ->
			val request = chain.request()
			forms += request.body?.let { body ->
				val buffer = okio.Buffer()
				body.writeTo(buffer)
				buffer.readUtf8()
			}.orEmpty()
			Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("OK")
				.body(token.toResponseBody("application/json".toMediaType()))
				.build()
		}
		.build()

	private fun stateFrom(url: String): String =
		URI(url).rawQuery.split('&')
			.first { it.startsWith("state=") }
			.substringAfter("state=")

	@Test
	fun `a signed-in session stores and returns its access token`() = runBlocking {
		val secret = MapSecret()
		val session = OAuthSession(
			secret,
			client("""{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}"""),
		)
		val url = session.begin("profile-1", config)
		val state = URLDecoder.decode(stateFrom(url), "UTF-8")

		assertTrue(secret.has("oauth.pending"))
		val result = session.complete("inhuman://oauth?code=code-1&state=$state")

		assertTrue(result.isSuccess)
		assertEquals("profile-1", result.getOrNull())
		assertFalse(secret.has("oauth.pending"))
		assertEquals("access-1", session.accessToken("profile-1"))
		assertTrue(session.status("profile-1").signedIn)
		val form = forms.single()
		assertTrue(form, form.contains("code_verifier="))
		assertTrue(form, form.contains("code=code-1"))
		assertTrue(form, form.contains("grant_type=authorization_code"))
	}

	@Test
	fun `a wrong callback state does not cancel the pending sign-in`() = runBlocking {
		val secret = MapSecret()
		val session = OAuthSession(
			secret,
			client("""{"access_token":"access-1"}"""),
		)
		val url = session.begin("profile-1", config)
		val state = stateFrom(url)

		val wrong = session.complete("inhuman://oauth?code=code-1&state=forged")
		assertTrue(wrong.isFailure)
		assertTrue(secret.has("oauth.pending"))

		val right = session.complete("inhuman://oauth?code=code-1&state=$state")
		assertTrue(right.isSuccess)
	}

	@Test
	fun `an expired token refreshes and keeps the refresh token`() = runBlocking {
		val secret = MapSecret()
		val session = OAuthSession(
			secret,
			client("""{"access_token":"access-2","expires_in":3600}"""),
		)
		// Complete a flow first so the config and tokens exist.
		val url = session.begin("profile-1", config)
		val state = stateFrom(url)
		session.complete("inhuman://oauth?code=code-1&state=$state")
		forms.clear()

		// Rewrite the stored tokens as expired; the private key format is part
		// of this module and is asserted here rather than guessed at a call site.
		secret.put(
			"oauth.tokens.profile-1",
			"""{"accessToken":"old","refreshToken":"refresh-1","expiresAt":1,"tokenType":"Bearer"}""",
		)
		val refreshed = session.accessToken("profile-1", now = 10_000L)

		assertEquals("access-2", refreshed)
		assertTrue(forms.single().contains("grant_type=refresh_token"))
		assertTrue(forms.single().contains("refresh_token=refresh-1"))
		val stored = session.status("profile-1")
		assertNotNull(stored.expiresAt)
	}

	@Test
	fun `signing out removes tokens but keeps the registration`() = runBlocking {
		val secret = MapSecret()
		val session = OAuthSession(secret, client("""{"access_token":"access-1"}"""))
		val url = session.begin("profile-1", config)
		val state = stateFrom(url)
		session.complete("inhuman://oauth?code=code-1&state=$state")

		session.signOut("profile-1")
		assertNull(session.accessToken("profile-1"))
		assertTrue(session.configured("profile-1"))
		assertFalse(session.status("profile-1").signedIn)

		session.forget("profile-1")
		assertFalse(session.configured("profile-1"))
	}

	@Test
	fun `an incomplete config is refused before a browser is opened`() = runBlocking {
		val session = OAuthSession(MapSecret(), client("{}"))
		val result = runCatching {
			session.begin("profile-1", config.copy(clientId = ""))
		}
		assertTrue(result.isFailure)
	}
}