package com.inhuman.core.ai.bilibili

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Bilibili's QR login, as a small pure-JVM client.
 *
 * ## Why QR only
 *
 * There is no public OAuth for third-party Bilibili clients, and the app refuses
 * a webview or a password field (AGENTS.md, OAuth rules). QR is the one flow
 * where the account is authenticated by Bilibili's own app, the credential never
 * passes through a page we render, and the user sees the confirmation on their
 * own phone.
 *
 * ## Why the cookies are returned, not stored here
 *
 * This module has no platform storage. [poll] hands the confirmed cookies to
 * the caller; the Android host puts them in `SecureStore`. That keeps the plain
 * JVM module testable and keeps secret storage in the layer that already has
 * the Keystore.
 *
 * The protocol states and the `Set-Cookie` parsing are pure functions in
 * [BilibiliQrProtocol] so they can be pinned without a network.
 */
class BilibiliAuth(
	private val http: OkHttpClient = defaultClient(),
) {

	private val json = Json { ignoreUnknownKeys = true; isLenient = true }

	/** Ask for a QR; the caller renders [BilibiliQr.url] and polls [BilibiliQr.key]. */
	suspend fun generateQr(): BilibiliQr {
		val root = get(GENERATE_URL, cookie = null)
		val code = root.code()
		if (code != 0) throw BilibiliApiException(code, root.message())
		val data = root.objectOrNull("data")
			?: throw BilibiliApiException(BilibiliApiException.TRANSPORT, "QR response had no data")
		val key = data.stringOrEmpty("qrcode_key")
		val url = data.stringOrEmpty("url")
		if (key.isBlank() || url.isBlank()) {
			throw BilibiliApiException(BilibiliApiException.TRANSPORT, "QR response was incomplete")
		}
		return BilibiliQr(key = key, url = url)
	}

	/**
	 * Poll one QR state. The caller repeats this every couple of seconds.
	 *
	 * [BilibiliQrState.Confirmed] carries credentials built from the poll
	 * response's own `Set-Cookie` headers, so no second request is needed to
	 * obtain them. The answer is not cached: a QR can be scanned once.
	 */
	suspend fun poll(key: String): BilibiliQrState =
		withContext(Dispatchers.IO) {
			try {
				http.newCall(
					Request.Builder()
						.url("$POLL_URL?" + "qrcode_key=" + encode(key) + "&source=main-fe-header")
						.get()
						.headers(commonHeaders())
						.build(),
				).execute().use { response ->
					val body = response.body.string()
					if (!response.isSuccessful) {
						return@withContext BilibiliQrState.Failed("HTTP ${response.code}")
					}
					val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
						?: return@withContext BilibiliQrState.Failed("unreadable poll response")
					if (root.code() != 0) {
						return@withContext BilibiliQrState.Failed(root.message().ifBlank { "poll refused" })
					}
					val data = root.objectOrNull("data")
						?: return@withContext BilibiliQrState.Failed("poll had no data")
					val kind = BilibiliQrProtocol.stateKind(data.int("code"))
					when (kind) {
						BilibiliQrProtocol.StateKind.WAITING -> BilibiliQrState.Waiting
						BilibiliQrProtocol.StateKind.SCANNED -> BilibiliQrState.Scanned
						BilibiliQrProtocol.StateKind.EXPIRED -> BilibiliQrState.Expired
						BilibiliQrProtocol.StateKind.DONE -> {
							val cookies = BilibiliQrProtocol.parseCookies(
								response.headers.values("Set-Cookie"),
							)
							val credentials = BilibiliQrProtocol.credentialsFrom(cookies)
							if (credentials == null) {
								BilibiliQrState.Failed("login returned no SESSDATA cookie")
							} else {
								BilibiliQrState.Confirmed(credentials)
							}
						}
						BilibiliQrProtocol.StateKind.UNKNOWN ->
							BilibiliQrState.Failed("unexpected login state")
					}
				}
			} catch (failure: IOException) {
				BilibiliQrState.Failed(failure.message ?: failure.javaClass.simpleName)
			}
		}

	/** Read the account name behind a set of credentials, for the settings row. */
	suspend fun fetchProfile(credentials: BilibiliCredentials): BilibiliProfile? {
		val root = get(NAV_URL, cookie = "SESSDATA=${credentials.sessdata}; DedeUserID=${credentials.dedeUserId}")
		if (root.code() != 0) return null
		val data = root.objectOrNull("data") ?: return null
		val name = data.stringOrEmpty("uname")
		val mid = (data["mid"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
		if (name.isBlank() || mid <= 0) return null
		return BilibiliProfile(
			mid = mid,
			name = name,
			vip = (data["vipStatus"] as? JsonPrimitive)?.intOrNull == 1,
		)
	}

	private suspend fun get(url: String, cookie: String?): JsonObject =
		withContext(Dispatchers.IO) {
			val builder = Request.Builder().url(url).get().headers(commonHeaders())
			if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
			try {
				http.newCall(builder.build()).execute().use { response ->
					val body = response.body.string()
					if (!response.isSuccessful) {
						throw BilibiliApiException(BilibiliApiException.TRANSPORT, "HTTP ${response.code}")
					}
					runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
						throw BilibiliApiException(BilibiliApiException.TRANSPORT, "response was not JSON")
					}
				}
			} catch (failure: IOException) {
				throw BilibiliApiException(
					BilibiliApiException.TRANSPORT,
					failure.message ?: failure.javaClass.simpleName,
					failure,
				)
			}
		}

	private fun commonHeaders(): okhttp3.Headers = okhttp3.Headers.headersOf(
		"User-Agent", USER_AGENT,
		"Referer", REFERER,
		"Accept", ACCEPT,
	)

	private fun encode(value: String): String = WbiSigner.encode(value)

	private fun JsonObject.code(): Int = (this["code"] as? JsonPrimitive)?.intOrNull ?: 0

	private fun JsonObject.message(): String =
		stringOrEmpty("message").ifBlank { stringOrEmpty("msg") }

	private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

	private fun JsonObject.stringOrEmpty(name: String): String =
		(this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

	private fun JsonObject.int(name: String): Int =
		(this[name] as? JsonPrimitive)?.intOrNull ?: 0

	companion object {
		private const val GENERATE_URL =
			"https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header"
		private const val POLL_URL =
			"https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
		private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

		private const val USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
		private const val REFERER = "https://www.bilibili.com/"
		private const val ACCEPT = "application/json, text/plain, */*"

		private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS)
			.callTimeout(25, TimeUnit.SECONDS)
			.build()
	}
}

/**
 * The QR protocol's constants and parsing, with no network in sight.
 *
 * The flow has exactly one place where a wrong mapping turns into a stuck
 * dialog, and one place where a cookie can be taken from the wrong header. Both
 * are pure functions here so tests can pin them.
 */
internal object BilibiliQrProtocol {

	enum class StateKind { WAITING, SCANNED, EXPIRED, DONE, UNKNOWN }

	/** The `data.code` values Bilibili's QR poll uses. */
	fun stateKind(code: Int?): StateKind = when (code) {
		0 -> StateKind.DONE
		86101 -> StateKind.WAITING
		86090 -> StateKind.SCANNED
		86038 -> StateKind.EXPIRED
		else -> StateKind.UNKNOWN
	}

	/**
	 * Take `name=value` from each `Set-Cookie` header.
	 *
	 * The first `;` ends the value; attributes after it are not cookies. Only
	 * the two cookies the read path needs are kept by [credentialsFrom].
	 */
	fun parseCookies(setCookieHeaders: List<String>): Map<String, String> = buildMap {
		for (header in setCookieHeaders) {
			val pair = header.substringBefore(';')
			val index = pair.indexOf('=')
			if (index > 0) {
				put(pair.substring(0, index).trim(), pair.substring(index + 1).trim())
			}
		}
	}

	fun credentialsFrom(
		cookies: Map<String, String>,
		userName: String = "",
		savedAtMillis: Long = System.currentTimeMillis(),
	): BilibiliCredentials? {
		val sessdata = cookies["SESSDATA"].orEmpty()
		val dedeUserId = cookies["DedeUserID"].orEmpty()
		if (sessdata.isBlank() || dedeUserId.isBlank()) return null
		return BilibiliCredentials(
			sessdata = sessdata,
			dedeUserId = dedeUserId,
			userName = userName,
			savedAtMillis = savedAtMillis,
		)
	}
}
