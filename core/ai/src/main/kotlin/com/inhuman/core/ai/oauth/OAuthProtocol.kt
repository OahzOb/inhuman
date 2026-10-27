package com.inhuman.core.ai.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * An OAuth 2.0 authorization-code configuration, entered by the user.
 *
 * The app deliberately ships no client IDs for Claude/ChatGPT/Copilot/Kimi.
 * Those are registrations tied to a particular application and its redirect
 * URI, and pretending a third-party app already has one would either fail at
 * the provider or misuse somebody else's client. The flow is generic; the
 * registration is user-supplied configuration.
 */
data class OAuthConfig(
	val authorizationEndpoint: String,
	val tokenEndpoint: String,
	val clientId: String,
	val scopes: List<String> = emptyList(),
	val redirectUri: String = OAuthProtocol.DEFAULT_REDIRECT_URI,
	/** Absent for a public PKCE client; some providers require one. */
	val clientSecret: String? = null,
) {
	val usable: Boolean
		get() = authorizationEndpoint.startsWith("https://") &&
			tokenEndpoint.startsWith("https://") &&
			clientId.isNotBlank() &&
			redirectUri.isNotBlank()
}

data class OAuthTokens(
	val accessToken: String,
	val refreshToken: String? = null,
	val expiresAt: Long? = null,
	val tokenType: String = "Bearer",
)

/** What one in-flight sign-in needs after the browser comes back. */
data class OAuthPending(
	val profileId: String,
	val state: String,
	val codeVerifier: String,
	val config: OAuthConfig,
)

data class OAuthCallback(
	val code: String?,
	val error: String?,
	/**
	 * True when the callback did not belong to the pending flow. The caller
	 * keeps the pending request: a forged intent must not cancel a real
	 * sign-in.
	 */
	val keepPending: Boolean = false,
)

/**
 * The parts of OAuth that can be pure.
 *
 * No Android `Uri`, no HTTP and no storage: the challenge, the state check and
 * the form bodies are facts about the protocol, and keeping them here means the
 * difficult half is testable offline.
 */
object OAuthProtocol {

	/** A custom scheme, because Android cannot listen on a loopback port. */
	const val DEFAULT_REDIRECT_URI = "inhuman://oauth"

	const val CALLBACK_SCHEME = "inhuman"
	const val CALLBACK_HOST = "oauth"

	/** How long before expiry a token is refreshed rather than used. */
	const val EXPIRY_SKEW_MS = 60_000L

	private val json = Json { ignoreUnknownKeys = true }

	fun newVerifier(random: SecureRandom = SecureRandom()): String {
		val bytes = ByteArray(32)
		random.nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	fun challenge(verifier: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
	}

	fun newState(random: SecureRandom = SecureRandom()): String {
		val bytes = ByteArray(24)
		random.nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	fun authorizationUrl(config: OAuthConfig, state: String, verifier: String): String {
		val parameters = buildList {
			add("response_type" to "code")
			add("client_id" to config.clientId)
			add("redirect_uri" to config.redirectUri)
			if (config.scopes.isNotEmpty()) add("scope" to config.scopes.joinToString(" "))
			add("state" to state)
			add("code_challenge" to challenge(verifier))
			add("code_challenge_method" to "S256")
		}
		val separator = if ('?' in config.authorizationEndpoint) '&' else '?'
		return config.authorizationEndpoint + separator + parameters.joinToString("&") { (key, value) ->
			"${encode(key)}=${encode(value)}"
		}
	}

	/**
	 * Read a callback URI and verify its state.
	 *
	 * A missing or mismatched state is an error even when a code is present: an
	 * authorization code redemption tied to someone else's flow is exactly what
	 * the state parameter exists to prevent.
	 */
	fun parseCallback(uri: String, expectedState: String): OAuthCallback {
		val parsed = try {
			URI(uri)
		} catch (_: Exception) {
			return OAuthCallback(null, "The sign-in callback was not a valid URI.", keepPending = true)
		}
		if (!parsed.scheme.equals(CALLBACK_SCHEME, ignoreCase = true) ||
			!parsed.host.equals(CALLBACK_HOST, ignoreCase = true)
		) {
			return OAuthCallback(null, "The sign-in callback did not come from this app.", keepPending = true)
		}
		val query = parseQuery(parsed.rawQuery)
		val state = query["state"].orEmpty()
		if (state != expectedState) {
			return OAuthCallback(null, "The sign-in callback did not match the request.", keepPending = true)
		}
		query["error"]?.let { error ->
			val description = query["error_description"]
			return OAuthCallback(null, if (description.isNullOrBlank()) error else "$error: $description")
		}
		val code = query["code"]?.takeIf { it.isNotBlank() }
			?: return OAuthCallback(null, "The sign-in callback carried no authorization code.")
		return OAuthCallback(code, null)
	}

	fun tokenForm(code: String, verifier: String, config: OAuthConfig): String = buildList {
		add("grant_type" to "authorization_code")
		add("code" to code)
		add("redirect_uri" to config.redirectUri)
		add("client_id" to config.clientId)
		add("code_verifier" to verifier)
		config.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret" to it) }
	}.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

	fun refreshForm(refreshToken: String, config: OAuthConfig): String = buildList {
		add("grant_type" to "refresh_token")
		add("refresh_token" to refreshToken)
		add("client_id" to config.clientId)
		config.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret" to it) }
	}.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

	/**
	 * Parse a token response. A refresh response may omit `refresh_token`, in
	 * which case the previous one remains valid and must be kept.
	 */
	fun parseTokens(raw: String, now: Long, previousRefresh: String? = null): OAuthTokens? {
		val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
		val access = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
			?: return null
		val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull
			?: root["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
		return OAuthTokens(
			accessToken = access,
			refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull
				?.takeIf { it.isNotBlank() }
				?: previousRefresh,
			expiresAt = expiresIn?.takeIf { it > 0 }?.let { now + it * 1000L },
			tokenType = root["token_type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
				?: "Bearer",
		)
	}

	fun isExpired(tokens: OAuthTokens, now: Long): Boolean {
		val expiresAt = tokens.expiresAt ?: return false
		return now >= expiresAt - EXPIRY_SKEW_MS
	}

	/** A short, non-secret description of a token response error. */
	fun errorDescription(raw: String, fallback: String): String {
		val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
		val error = root?.get("error")?.jsonPrimitive?.contentOrNull
		val description = root?.get("error_description")?.jsonPrimitive?.contentOrNull
		return when {
			!description.isNullOrBlank() -> if (error.isNullOrBlank()) description else "$error: $description"
			!error.isNullOrBlank() -> error
			else -> fallback
		}
	}

	private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

	private fun parseQuery(rawQuery: String?): Map<String, String> {
		if (rawQuery.isNullOrBlank()) return emptyMap()
		return rawQuery.split('&').mapNotNull { pair ->
			val index = pair.indexOf('=')
			if (index < 0) return@mapNotNull null
			val key = URLDecoder.decode(pair.substring(0, index), "UTF-8")
			val value = URLDecoder.decode(pair.substring(index + 1), "UTF-8")
			key to value
		}.toMap()
	}
}