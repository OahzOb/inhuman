package com.inhuman.core.data.oauth

import com.inhuman.core.ai.oauth.OAuthCallback
import com.inhuman.core.ai.oauth.OAuthConfig
import com.inhuman.core.ai.oauth.OAuthPending
import com.inhuman.core.ai.oauth.OAuthProtocol
import com.inhuman.core.ai.oauth.OAuthTokens
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.secret.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the provider editor may show about a sign-in, without exposing tokens. */
data class OAuthStatus(
	val configured: Boolean,
	val signedIn: Boolean,
	val expiresAt: Long?,
	val canRefresh: Boolean,
)

/**
 * OAuth sign-in state, stored in the app's secret store.
 *
 * ## Why a custom scheme and PKCE, not a client secret
 *
 * Android cannot listen on a loopback port, so the redirect is
 * `inhuman://oauth`. That is a public client redirect; the code exchange is
 * protected by PKCE. A `client_secret` is supported when the provider requires
 * one, but its presence is configuration, not the security model.
 *
 * ## What is stored where
 *
 * `oauth.config.<profile>` is the user-entered registration;
 * `oauth.tokens.<profile>` is the access/refresh pair; `oauth.pending` exists
 * only between launching the browser and receiving the callback. All three live
 * behind [SecretStore], so an unreadable device credential means "sign in
 * again", not a crash. Nothing here is logged: a token, code or verifier in the
 * flight recorder would be a credential leak with a retention policy.
 */
class OAuthSession(
	private val secure: SecretStore,
	private val client: OkHttpClient = defaultClient(),
) {

	private val lock = Mutex()

	fun config(profileId: String): OAuthConfig? = readConfig(secure.get(configKey(profileId)))

	fun configured(profileId: String): Boolean = config(profileId)?.usable == true

	/** Save a validated config. Returns false when the config is not usable. */
	fun saveConfig(profileId: String, config: OAuthConfig): Boolean {
		if (!config.usable) return false
		secure.put(configKey(profileId), encodeConfig(config))
		return true
	}

	/**
	 * Start a sign-in: persist the verifier and state, and return the URL the
	 * browser should open.
	 */
	suspend fun begin(profileId: String, config: OAuthConfig): String = lock.withLock {
		require(config.usable) { "The OAuth endpoints and client id are incomplete." }
		val verifier = OAuthProtocol.newVerifier()
		val state = OAuthProtocol.newState()
		saveConfig(profileId, config)
		secure.put(
			PENDING_KEY,
			buildJsonObject {
				put("profile", profileId)
				put("state", state)
				put("verifier", verifier)
				put("config", encodeConfigObject(config))
			}.toString(),
		)
		Diag.i("oauth", "sign-in started")
		OAuthProtocol.authorizationUrl(config, state, verifier)
	}

	/**
	 * Finish a sign-in from the callback URI.
	 *
	 * A state mismatch does **not** clear the pending flow: anyone can send an
	 * intent with the app's scheme, and letting a forged callback cancel a
	 * legitimate sign-in would be a denial of service. A valid callback that
	 * carries an error, or a code whose exchange fails, does clear it because
	 * that flow cannot be retried.
	 */
	suspend fun complete(callbackUri: String): Result<String> = lock.withLock {
		val pending = readPending(secure.get(PENDING_KEY))
			?: return@withLock Result.failure(IllegalStateException("No sign-in is waiting."))
		val callback: OAuthCallback = OAuthProtocol.parseCallback(callbackUri, pending.state)
		val code = callback.code
		if (code == null) {
			val message = callback.error ?: "The sign-in did not return a code."
			if (!callback.keepPending) secure.remove(PENDING_KEY)
			return@withLock Result.failure(IllegalStateException(message))
		}
		val tokens = exchange(pending.config, code, pending.codeVerifier)
			.getOrElse { failure ->
				secure.remove(PENDING_KEY)
				return@withLock Result.failure(failure)
			}
		secure.put(tokenKey(pending.profileId), encodeTokens(tokens))
		secure.remove(PENDING_KEY)
		Diag.i("oauth", "sign-in completed")
		Result.success(pending.profileId)
	}

	/**
	 * The access token to use now, refreshing first when it is near expiry.
	 *
	 * Returns null rather than throwing: the caller treats "no usable token" as
	 * "this provider is not signed in", and the UI asks for sign-in again.
	 */
	suspend fun accessToken(profileId: String, now: Long = System.currentTimeMillis()): String? =
		lock.withLock {
			val tokens = readTokens(secure.get(tokenKey(profileId))) ?: return@withLock null
			if (!OAuthProtocol.isExpired(tokens, now)) return@withLock tokens.accessToken
			val refresh = tokens.refreshToken ?: return@withLock null
			val config = config(profileId) ?: return@withLock null
			val refreshed = refresh(config, refresh)
			if (refreshed == null) {
				Diag.w("oauth", "refresh failed")
				return@withLock null
			}
			secure.put(tokenKey(profileId), encodeTokens(refreshed))
			Diag.i("oauth", "access token refreshed")
			refreshed.accessToken
		}

	fun status(profileId: String, now: Long = System.currentTimeMillis()): OAuthStatus {
		val config = config(profileId)
		val tokens = readTokens(secure.get(tokenKey(profileId)))
		return OAuthStatus(
			configured = config?.usable == true,
			// An expired access token with a refresh token is still a usable
			// sign-in: the next request refreshes it. Without a refresh token it
			// is not, and the UI should offer sign-in again.
			signedIn = tokens != null &&
				(!OAuthProtocol.isExpired(tokens, now) || !tokens.refreshToken.isNullOrBlank()),
			expiresAt = tokens?.expiresAt,
			canRefresh = !tokens?.refreshToken.isNullOrBlank() && config?.usable == true,
		)
	}

	/** Forget the tokens, keeping the registration for a later sign-in. */
	fun signOut(profileId: String) {
		secure.remove(tokenKey(profileId))
		secure.remove(PENDING_KEY)
	}

	/** Delete everything, for a deleted provider profile. */
	fun forget(profileId: String) {
		signOut(profileId)
		secure.remove(configKey(profileId))
	}

	private suspend fun exchange(config: OAuthConfig, code: String, verifier: String): Result<OAuthTokens> =
		post(config.tokenEndpoint, OAuthProtocol.tokenForm(code, verifier, config))

	private suspend fun refresh(config: OAuthConfig, refreshToken: String): OAuthTokens? =
		post(config.tokenEndpoint, OAuthProtocol.refreshForm(refreshToken, config), previousRefresh = refreshToken)
			.getOrNull()

	private suspend fun post(
		endpoint: String,
		form: String,
		previousRefresh: String? = null,
	): Result<OAuthTokens> = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(endpoint)
			.header("Accept", "application/json")
			.post(form.toRequestBody(FORM_MEDIA_TYPE))
			.build()
		try {
			client.newCall(request).execute().use { response ->
				val body = response.body.string()
				if (!response.isSuccessful) {
					// The description is provider data, not a token; do not log
					// the body itself, which could contain one on a malformed
					// success-shaped error.
					val message = OAuthProtocol.errorDescription(
						body,
						"Token endpoint returned HTTP ${response.code}.",
					)
					Diag.w("oauth", "token endpoint failed HTTP ${response.code}")
					return@withContext Result.failure(IllegalStateException(message))
				}
				val tokens = OAuthProtocol.parseTokens(body, System.currentTimeMillis(), previousRefresh)
					?: return@withContext Result.failure(
						IllegalStateException("The token endpoint returned no access token."),
					)
				Result.success(tokens)
			}
		} catch (e: IOException) {
			Diag.w("oauth", "token request failed (${e.javaClass.simpleName})")
			Result.failure(IllegalStateException("Could not reach the token endpoint."))
		}
	}

	private fun readConfig(raw: String?): OAuthConfig? {
		if (raw.isNullOrBlank()) return null
		return runCatching {
			val root = JSON.parseToJsonElement(raw).jsonObject
			OAuthConfig(
				authorizationEndpoint = root["authorizationEndpoint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				tokenEndpoint = root["tokenEndpoint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				clientId = root["clientId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				scopes = (root["scopes"] as? JsonArray)?.mapNotNull {
					it.jsonPrimitive.contentOrNull
				}.orEmpty(),
				redirectUri = root["redirectUri"]?.jsonPrimitive?.contentOrNull
					?: OAuthProtocol.DEFAULT_REDIRECT_URI,
				clientSecret = root["clientSecret"]?.jsonPrimitive?.contentOrNull,
			).takeIf { it.usable }
		}.getOrNull()
	}

	private fun encodeConfig(config: OAuthConfig): String = encodeConfigObject(config).toString()

	private fun encodeConfigObject(config: OAuthConfig): JsonObject = buildJsonObject {
		put("authorizationEndpoint", config.authorizationEndpoint)
		put("tokenEndpoint", config.tokenEndpoint)
		put("clientId", config.clientId)
		put("scopes", buildJsonArray { config.scopes.forEach { add(JsonPrimitive(it)) } })
		put("redirectUri", config.redirectUri)
		config.clientSecret?.let { put("clientSecret", it) }
	}

	private fun readPending(raw: String?): OAuthPending? {
		if (raw.isNullOrBlank()) return null
		return runCatching {
			val root = JSON.parseToJsonElement(raw).jsonObject
			OAuthPending(
				profileId = root["profile"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				state = root["state"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				codeVerifier = root["verifier"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				config = readConfig(root["config"]?.toString()) ?: return null,
			)
		}.getOrNull()?.takeIf { it.profileId.isNotBlank() && it.state.isNotBlank() && it.codeVerifier.isNotBlank() }
	}

	private fun readTokens(raw: String?): OAuthTokens? {
		if (raw.isNullOrBlank()) return null
		return runCatching {
			val root = JSON.parseToJsonElement(raw).jsonObject
			OAuthTokens(
				accessToken = root["accessToken"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				refreshToken = root["refreshToken"]?.jsonPrimitive?.contentOrNull,
				expiresAt = root["expiresAt"]?.jsonPrimitive?.longOrNull,
				tokenType = root["tokenType"]?.jsonPrimitive?.contentOrNull ?: "Bearer",
			).takeIf { it.accessToken.isNotBlank() }
		}.getOrNull()
	}

	private fun encodeTokens(tokens: OAuthTokens): String = buildJsonObject {
		put("accessToken", tokens.accessToken)
		tokens.refreshToken?.let { put("refreshToken", it) }
		tokens.expiresAt?.let { put("expiresAt", it) }
		put("tokenType", tokens.tokenType)
	}.toString()

	private fun configKey(id: String) = "oauth.config.$id"

	private fun tokenKey(id: String) = "oauth.tokens.$id"

	private companion object {
		const val PENDING_KEY = "oauth.pending"
		val JSON = Json { ignoreUnknownKeys = true }
		val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

		fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
			.callTimeout(30, TimeUnit.SECONDS)
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.build()
	}
}