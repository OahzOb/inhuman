package com.inhuman.core.ai.tools

import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The one HTTP client the app-proxied web reads share.
 *
 * ## Why this exists
 *
 * `download_url` and the user-facing Files downloader already had the same
 * answer twice: scheme/length/credential checks ([DownloadUrl]), a DNS lookup
 * that refuses private answers, and a redirect loop that re-validates every hop
 * instead of letting OkHttp hand the next URL straight to a socket. The local
 * web tools (search pages and one page's text) are app-proxied reads with the
 * same risks, so they must use the same answer rather than a second copy that
 * drifts in the direction of "a missing check is invisible until it is not".
 *
 * ## Why no cookie jar
 *
 * These are anonymous reads. A cookie jar would make the app a session holder,
 * which is exactly the boundary the downloader and the Bilibili reader keep.
 *
 * ## What this is not
 *
 * It is not an SSRF guard for *arbitrary* connections: it refuses a resolved
 * private address at connect time and re-validates each redirect, and callers
 * still pass model-supplied URLs through [DownloadUrl.validate] first. A local
 * proxy or a platform VPN is outside its view by design (the user's own egress).
 */
class GuardedHttp internal constructor(
	private val dns: Dns,
	/**
	 * False only for the local fixture server in tests. In production a private
	 * answer is refused at connect time and by [checkPublicHost].
	 */
	private val enforcePublicAddresses: Boolean,
) {

	/** A response whose body is still open. The caller must close it. */
	class Opened internal constructor(
		val finalUri: URI,
		val response: Response,
	) : Closeable {
		override fun close() = response.close()
	}

	/**
	 * A fully read response. `truncated` is true when [maxBytes] stopped the
	 * read; the bytes are then a prefix, not the whole body.
	 */
	class Bytes(
		val finalUri: URI,
		val status: Int,
		val contentType: String?,
		val bytes: ByteArray,
		val truncated: Boolean,
	) {
		val isSuccessful: Boolean get() = status in 200..299
	}

	private val client: OkHttpClient = OkHttpClient.Builder()
		.dns(if (enforcePublicAddresses) BlockingDns(dns) else dns)
		// Redirects are followed by hand so each Location goes through the same
		// scheme/host/address checks. `followRedirects(true)` would hand the
		// next hop to the socket without them.
		.followRedirects(false)
		.followSslRedirects(false)
		.cookieJar(CookieJar.NO_COOKIES)
		.build()

	/**
	 * Validate [raw] and open a GET for it.
	 *
	 * @param requireHttps true for anything the model names; the Files screen
	 *   keeps accepting `http` for the links people actually paste.
	 * @throws IllegalArgumentException for a URL this app refuses before any
	 *   network work, with a message the caller can show.
	 * @throws IOException for transport and redirect failures.
	 */
	suspend fun open(
		raw: String,
		requireHttps: Boolean,
		userAgent: String,
		acceptLanguage: String? = null,
		extraHeaders: Map<String, String> = emptyMap(),
		connectTimeoutSeconds: Long = CONNECT_TIMEOUT_SECONDS,
		readTimeoutSeconds: Long = READ_TIMEOUT_SECONDS,
	): Opened = withContext(Dispatchers.IO) {
		var target = DownloadUrl.validate(raw, requireHttps)
		repeat(MAX_REDIRECTS + 1) {
			val request = try {
				Request.Builder()
					.url(target.toString())
					.get()
					.header("User-Agent", userAgent)
					.apply { acceptLanguage?.let { header("Accept-Language", it) } }
					.apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
					.build()
			} catch (e: IllegalArgumentException) {
				throw IOException("invalid URL: ${e.message}", e)
			}

			val response = try {
				client.newBuilder()
					.connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
					.readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
					.build()
					.newCall(request)
					.execute()
			} catch (e: Exception) {
				Diag.w("web", "request failed host=${target.host}", e)
				throw IOException(e.message ?: "request failed", e)
			}

			val location = response.header("Location")
			if (response.code in 300..399 && location != null) {
				response.close()
				target = DownloadUrl.validate(
					target.resolve(location).toString(),
					requireHttps,
				)
				return@repeat
			}
			Diag.i("web", "response HTTP ${response.code} host=${target.host}")
			return@withContext Opened(target, response)
		}
		throw IOException("request failed: more than $MAX_REDIRECTS redirects")
	}

	/**
	 * Open a GET and read at most [maxBytes] of the body.
	 *
	 * Non-2xx is returned, not thrown: the caller decides what to say, and a
	 * provider needs the body to say it. A body larger than [maxBytes] comes
	 * back as a prefix with `truncated = true` rather than being silently
	 * shortened.
	 */
	suspend fun getBytes(
		raw: String,
		requireHttps: Boolean,
		userAgent: String,
		acceptLanguage: String? = null,
		maxBytes: Int = MAX_BYTES,
		connectTimeoutSeconds: Long = CONNECT_TIMEOUT_SECONDS,
		readTimeoutSeconds: Long = READ_TIMEOUT_SECONDS,
		extraHeaders: Map<String, String> = emptyMap(),
	): Bytes {
		require(maxBytes > 0) { "maxBytes must be positive" }
		open(
			raw = raw,
			requireHttps = requireHttps,
			userAgent = userAgent,
			acceptLanguage = acceptLanguage,
			extraHeaders = extraHeaders,
			connectTimeoutSeconds = connectTimeoutSeconds,
			readTimeoutSeconds = readTimeoutSeconds,
		).use { opened ->
			val body = opened.response.body
			val stream = body.byteStream()
			val buffer = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
			val chunk = ByteArray(16 * 1024)
			var total = 0
			var truncated = false
			while (true) {
				val read = stream.read(chunk)
				if (read < 0) break
				val remaining = maxBytes - total
				if (read > remaining) {
					buffer.write(chunk, 0, remaining)
					truncated = true
					break
				}
				buffer.write(chunk, 0, read)
				total += read
				if (total >= maxBytes) {
					// The body may be exactly `maxBytes` long; only a further
					// byte proves truncation.
					truncated = stream.read() >= 0
					break
				}
			}
			return Bytes(
				finalUri = opened.finalUri,
				status = opened.response.code,
				contentType = opened.response.header("Content-Type"),
				bytes = buffer.toByteArray(),
				truncated = truncated,
			)
		}
	}

	/**
	 * Resolve [host] and refuse it if any answer is private.
	 *
	 * The WebView renderer resolves the URL itself, so this is the only place a
	 * name can be checked before the browser engine opens the page.
	 */
	fun checkPublicHost(host: String) {
		if (host.isBlank()) throw IllegalArgumentException("The URL has no host")
		if (!enforcePublicAddresses) return
		val addresses = try {
			dns.lookup(host)
		} catch (e: UnknownHostException) {
			throw IllegalArgumentException(
				"Refusing to open a private address for $host",
				e,
			)
		}
		if (addresses.isEmpty() || addresses.any { DownloadUrl.isBlockedAddress(it) }) {
			throw IllegalArgumentException("Refusing to open a private address for $host")
		}
	}

	companion object {
		/** The shared default: private answers are refused at connect time. */
		val default: GuardedHttp = GuardedHttp(Dns.SYSTEM, enforcePublicAddresses = true)

		/** Test seam: the local fixture server is not a public address. */
		internal fun forTesting(dns: Dns = Dns.SYSTEM): GuardedHttp =
			GuardedHttp(dns, enforcePublicAddresses = false)

		const val MAX_REDIRECTS = 5
		const val MAX_BYTES = 2 * 1024 * 1024
		const val CONNECT_TIMEOUT_SECONDS = 20L
		const val READ_TIMEOUT_SECONDS = 60L

		private class BlockingDns(private val delegate: Dns) : Dns {
			override fun lookup(hostname: String): List<InetAddress> {
				val addresses = delegate.lookup(hostname)
				if (addresses.isEmpty() ||
					addresses.any { DownloadUrl.isBlockedAddress(it) }
				) {
					throw UnknownHostException(
						"refusing to connect to a private address for $hostname",
					)
				}
				return addresses
			}
		}
	}
}

/**
 * Headers a browser-like anonymous GET sends.
 *
 * The user agent is deliberately a phone browser's: some search engines serve
 * a different (or no) result page to an unfamiliar agent. There is no cookie
 * and no credential behind it.
 */
object WebHeaders {

	const val USER_AGENT =
		"Mozilla/5.0 (Linux; Android 15; Pixel Tablet) AppleWebKit/537.36 " +
			"(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

	/** `en-US,en` style, from the device locale; a blank country is dropped. */
	fun acceptLanguage(locale: java.util.Locale = java.util.Locale.getDefault()): String {
		val language = locale.language.ifBlank { "en" }
		val country = locale.country
		return if (country.isBlank()) language else "$language-$country,$language"
	}
}
