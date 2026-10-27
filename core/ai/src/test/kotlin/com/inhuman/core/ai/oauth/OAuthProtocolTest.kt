package com.inhuman.core.ai.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * PKCE, state and token parsing. These are the parts of sign-in that can be
 * proven without a browser or an account; the Android callback and the token
 * endpoint are exercised by the host and by an end-to-end sign-in with a real
 * registration.
 */
class OAuthProtocolTest {

	private val config = OAuthConfig(
		authorizationEndpoint = "https://auth.example.test/authorize",
		tokenEndpoint = "https://auth.example.test/token",
		clientId = "client-123",
		scopes = listOf("openid", "chat"),
		redirectUri = "inhuman://oauth",
	)

	@Test
	fun `the RFC 7636 challenge example matches`() {
		assertEquals(
			"E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
			OAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
		)
	}

	@Test
	fun `an authorization URL carries PKCE, state and scopes`() {
		val url = OAuthProtocol.authorizationUrl(config, "state-value", "verifier-value")
		val query = URI(url).rawQuery

		assertTrue(url.startsWith("https://auth.example.test/authorize?"))
		assertTrue(query, query.contains("response_type=code"))
		assertTrue(query, query.contains("client_id=client-123"))
		assertTrue(query, query.contains("redirect_uri=inhuman%3A%2F%2Foauth"))
		assertTrue(query, query.contains("scope=openid+chat"))
		assertTrue(query, query.contains("state=state-value"))
		assertTrue(query, query.contains("code_challenge_method=S256"))
		assertTrue(query, query.contains("code_challenge="))
		assertFalse("the verifier is never in the URL", query.contains("verifier-value"))
	}

	@Test
	fun `an endpoint with a query keeps its own parameters`() {
		val url = OAuthProtocol.authorizationUrl(
			config.copy(authorizationEndpoint = "https://auth.example.test/auth?tenant=one"),
			"state",
			"verifier",
		)
		assertTrue(url.contains("tenant=one&response_type=code"))
	}

	@Test
	fun `a valid callback yields the code`() {
		val callback = OAuthProtocol.parseCallback(
			"inhuman://oauth?code=abc%20123&state=expected",
			"expected",
		)

		assertEquals("abc 123", callback.code)
		assertNull(callback.error)
	}

	@Test
	fun `a mismatched state is refused`() {
		val callback = OAuthProtocol.parseCallback(
			"inhuman://oauth?code=abc&state=other",
			"expected",
		)

		assertNull(callback.code)
		assertTrue(callback.error!!.contains("did not match"))
	}

	@Test
	fun `an error callback carries its description`() {
		val callback = OAuthProtocol.parseCallback(
			"inhuman://oauth?error=access_denied&error_description=User%20said%20no&state=expected",
			"expected",
		)

		assertNull(callback.code)
		assertTrue(callback.error!!.contains("User said no"))
	}

	@Test
	fun `a foreign callback scheme is refused`() {
		val callback = OAuthProtocol.parseCallback(
			"evil://oauth?code=abc&state=expected",
			"expected",
		)
		assertNull(callback.code)
		assertTrue(callback.error!!.contains("did not come from this app"))
	}

	@Test
	fun `token and refresh forms carry the public client fields`() {
		val token = OAuthProtocol.tokenForm("abc", "verifier", config)
		assertTrue(token.contains("grant_type=authorization_code"))
		assertTrue(token.contains("code=abc"))
		assertTrue(token.contains("code_verifier=verifier"))
		assertTrue(token.contains("client_id=client-123"))
		assertTrue(token.contains("redirect_uri=inhuman%3A%2F%2Foauth"))
		assertFalse(token.contains("client_secret"))

		val secret = OAuthProtocol.tokenForm("abc", "verifier", config.copy(clientSecret = "s3cret"))
		assertTrue(secret.contains("client_secret=s3cret"))

		val refresh = OAuthProtocol.refreshForm("refresh", config)
		assertTrue(refresh.contains("grant_type=refresh_token"))
		assertTrue(refresh.contains("refresh_token=refresh"))
	}

	@Test
	fun `expires_in becomes an absolute expiry and refresh is preserved`() {
		val now = 1_000_000L
		val tokens = OAuthProtocol.parseTokens(
			"""{"access_token":"a","expires_in":3600,"token_type":"bearer"}""",
			now,
			previousRefresh = "old-refresh",
		)!!

		assertEquals("a", tokens.accessToken)
		assertEquals("old-refresh", tokens.refreshToken)
		assertEquals(now + 3_600_000L, tokens.expiresAt)
		assertEquals("bearer", tokens.tokenType)
		assertFalse(OAuthProtocol.isExpired(tokens, now))
		assertTrue(OAuthProtocol.isExpired(tokens, now + 3_600_000L - OAuthProtocol.EXPIRY_SKEW_MS))
	}

	@Test
	fun `a string expires_in is accepted`() {
		val now = 1_000L
		val tokens = OAuthProtocol.parseTokens(
			"""{"access_token":"a","expires_in":"120"}""",
			now,
		)!!
		assertEquals(now + 120_000L, tokens.expiresAt)
	}

	@Test
	fun `a token response without an access token is not accepted`() {
		assertNull(OAuthProtocol.parseTokens("""{"error":"invalid_grant"}""", 0))
		assertNull(OAuthProtocol.parseTokens("not json", 0))
	}

	@Test
	fun `an insecure endpoint is not usable`() {
		assertTrue(config.usable)
		assertFalse(config.copy(authorizationEndpoint = "http://auth.example.test").usable)
		assertFalse(config.copy(clientId = " ").usable)
	}
}