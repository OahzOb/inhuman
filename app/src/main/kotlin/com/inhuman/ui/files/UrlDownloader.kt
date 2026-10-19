package com.inhuman.ui.files

import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * One URL, opened as a stream the caller chooses where to put.
 *
 * ## Why this is not a tool
 *
 * The agent has no network path; that is the property the sandbox exists for.
 * This class is behind a user pressing "Download" in Files, so the URL is the
 * user's choice, not a model's. The tool catalogue does not mention it, and a
 * prompt cannot reach it.
 *
 * ## Why the SSRF checks are not optional
 *
 * "The user typed the URL" is not a defence against a link that points at
 * `127.0.0.1`, a router's admin page, or a cloud metadata address. Every
 * redirect is re-validated, and the DNS lookup refuses private, loopback,
 * link-local and multicast addresses before a socket is opened. Without the
 * custom [Dns] a public hostname could resolve to a private address at connect
 * time (DNS rebinding) even though the string looked public.
 *
 * ## No size cap
 *
 * The caller streams to its destination and decides what to do with the bytes;
 * the dialog shows the reported size and the bytes received. The workspace
 * quota still applies when the destination is the conversation, and the file
 * system is the limit for a folder the user picked. There is deliberately no
 * arbitrary `MAX_DOWNLOAD_BYTES` here.
 */
object UrlDownloader {

	/** A response whose body is still open. The caller must close it. */
	class DownloadResponse internal constructor(
		val fileName: String,
		val mimeType: String,
		val contentLength: Long?,
		val body: InputStream,
		private val source: okhttp3.Response,
	) : Closeable {
		override fun close() {
			source.close()
		}
	}

	private const val MAX_REDIRECTS = 5

	private val safeDns = object : Dns {
		override fun lookup(hostname: String): List<InetAddress> {
			val addresses = Dns.SYSTEM.lookup(hostname)
			if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
				throw UnknownHostException("refusing to connect to a private address for $hostname")
			}
			return addresses
		}
	}

	private val client: OkHttpClient by lazy {
		OkHttpClient.Builder()
			.dns(safeDns)
			// Redirects are followed by hand so each Location goes through the
			// same scheme/host/address checks. `followRedirects(true)` would
			// hand the next hop to the socket without them.
			.followRedirects(false)
			.followSslRedirects(false)
			.cookieJar(CookieJar.NO_COOKIES)
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.build()
	}

	/**
	 * Validate [raw] and open a GET for it.
	 *
	 * @throws IllegalArgumentException for a URL this app refuses before any
	 *   network work, with a message the dialog can show.
	 * @throws IOException for transport and HTTP failures.
	 */
	suspend fun open(raw: String): DownloadResponse = withContext(Dispatchers.IO) {
		var target = validateUrl(raw)
		repeat(MAX_REDIRECTS + 1) {
			val request = Request.Builder()
				.url(target.toString())
				.get()
				.header("User-Agent", "inhuman/0.1")
				.build()

			val response = try {
				client.newCall(request).execute()
			} catch (e: Exception) {
				Diag.w("download", "request failed host=${target.host}", e)
				throw IOException(e.message ?: "download failed", e)
			}

			val location = response.header("Location")
			if (response.code in 300..399 && location != null) {
				response.close()
				target = validateUrl(target.resolve(location).toString())
				return@repeat
			}

			if (!response.isSuccessful) {
				val code = response.code
				val message = response.message
				val detail = runCatching { response.body.string().take(200) }.getOrNull()
				response.close()
				Diag.w("download", "response HTTP $code host=${target.host}")
				throw IOException(
					"download failed: HTTP $code $message" +
						if (detail.isNullOrBlank()) "" else " — $detail",
				)
			}

			// OkHttp 5's `body` is non-null; the previous nullable handling was
			// a leftover from the 4.x API and produced two compiler warnings.
			val body = response.body
			Diag.i(
				"download",
				"response HTTP ${response.code} host=${target.host} bytes=${body.contentLength()}",
			)
			return@withContext DownloadResponse(
				fileName = fileNameFor(target, response.header("Content-Disposition")),
				mimeType = mimeTypeOf(response.header("Content-Type")),
				contentLength = body.contentLength().takeIf { it >= 0 },
				body = body.byteStream(),
				source = response,
			)
		}
		throw IOException("download failed: more than $MAX_REDIRECTS redirects")
	}

	/**
	 * `http`/`https`, a host, no user-info, and no literal private IP.
	 *
	 * Package-visible for tests. The host's *resolved* addresses are checked by
	 * [safeDns] at connect time, not here: a name is allowed to resolve, but
	 * only to public addresses.
	 */
	internal fun validateUrl(raw: String): URI {
		val trimmed = raw.trim()
		require(trimmed.isNotEmpty()) { "URL is empty" }
		val uri = try {
			URI(trimmed)
		} catch (_: Exception) {
			throw IllegalArgumentException("Not a valid URL")
		}
		val scheme = uri.scheme?.lowercase()
		require(scheme == "http" || scheme == "https") { "Only http and https URLs can be downloaded" }
		val host = uri.host?.takeIf { it.isNotBlank() }
			?: throw IllegalArgumentException("The URL has no host")
		require(uri.userInfo == null) { "The URL must not contain a username or password" }
		require(!isBlockedLiteralHost(host)) { "Refusing to download from a private address" }
		return uri
	}

	/** A literal IP the user pasted still gets the address check. */
	private fun isBlockedLiteralHost(host: String): Boolean {
		val looksV4 = host.matches(Regex("""^\d{1,3}(?:\.\d{1,3}){3}$"""))
		val looksV6 = host.contains(':')
		if (!looksV4 && !looksV6) return false
		val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
		return isBlockedAddress(address)
	}

	/**
	 * Private, loopback, link-local, multicast, "this network", CGNAT and the
	 * reserved ranges. Also the IPv6 unique-local `fc00::/7`, which
	 * [InetAddress.isSiteLocalAddress] does not cover on Android.
	 */
	internal fun isBlockedAddress(address: InetAddress): Boolean {
		if (address.isAnyLocalAddress || address.isLoopbackAddress ||
			address.isLinkLocalAddress || address.isSiteLocalAddress ||
			address.isMulticastAddress
		) {
			return true
		}
		val bytes = address.address
		if (address is Inet4Address && bytes.size == 4) {
			val a = bytes[0].toInt() and 0xff
			val b = bytes[1].toInt() and 0xff
			// 100.64.0.0/10 carrier-grade NAT, 198.18.0.0/15 benchmarking,
			// 240.0.0.0/4 reserved.
			if (a == 100 && b in 64..127) return true
			if (a == 198 && b in 18..19) return true
			if (a >= 240) return true
		}
		if (address is Inet6Address && bytes.isNotEmpty()) {
			val a = bytes[0].toInt() and 0xff
			if ((a and 0xfe) == 0xfc) return true
		}
		return false
	}

	/**
	 * `Content-Disposition` first, then the last path segment, then "download".
	 *
	 * The result is a display name, not a path: separators and control
	 * characters are replaced, and `.`/`..` are refused. The workspace and SAF
	 * both get this name, so neither can be steered outside its root by it.
	 */
	internal fun fileNameFor(uri: URI, contentDisposition: String?): String {
		val fromHeader = contentDisposition?.let(::fromContentDisposition)
		val fromPath = uri.path
			?.substringAfterLast('/')
			?.takeIf { it.isNotBlank() }
			?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
		return sanitizeName(fromHeader ?: fromPath ?: "download")
	}

	private fun fromContentDisposition(header: String): String? {
		// RFC 5987 form wins over the legacy filename= form.
		val extended = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
			.find(header)?.groupValues?.get(1)
		if (extended != null) {
			return runCatching { URLDecoder.decode(extended.trim().trim('"'), "UTF-8") }
				.getOrNull()
				?.takeIf { it.isNotBlank() }
		}
		val plain = Regex("""filename\s*=\s*("([^"]*)"|[^;]+)""", RegexOption.IGNORE_CASE)
			.find(header) ?: return null
		val value = plain.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
			?: plain.groupValues[1].trim().trim('"')
		return value.takeIf { it.isNotBlank() }
	}

	internal fun sanitizeName(raw: String): String {
		val cleaned = raw
			.map { ch ->
				if (ch.isISOControl() || ch in "/\\:*?\"<>|") '_' else ch
			}
			.joinToString("")
			.trim()
			.trim('.')
			.take(120)
		return cleaned.ifBlank { "download" }
	}

	private fun mimeTypeOf(header: String?): String =
		header?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
			?: "application/octet-stream"
}

/** Counts bytes as they are read, without buffering or changing the stream. */
internal class CountingInputStream(
	private val delegate: InputStream,
	private val onBytes: (Long) -> Unit,
) : InputStream() {

	private var total = 0L

	override fun read(): Int {
		val value = delegate.read()
		if (value >= 0) {
			total++
			onBytes(total)
		}
		return value
	}

	override fun read(b: ByteArray, off: Int, len: Int): Int {
		val read = delegate.read(b, off, len)
		if (read > 0) {
			total += read
			onBytes(total)
		}
		return read
	}

	override fun available(): Int = delegate.available()

	override fun close() = delegate.close()
}
