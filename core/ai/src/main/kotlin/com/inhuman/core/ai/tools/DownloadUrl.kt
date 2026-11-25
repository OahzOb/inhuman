package com.inhuman.core.ai.tools

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder

/**
 * The checks an app-proxied download must pass before a socket is opened.
 *
 * ## Why this lives here rather than in the Android file screen
 *
 * Two callers now share one answer: the user's Files → Download from a URL, and
 * the `download_url` tool. The tool is the newer and narrower caller (it only
 * accepts `https`), but the SSRF rules are identical and were already written
 * once. A second copy would drift, and the direction it drifts in is unsafe: a
 * missing private-address check is invisible in a working download.
 *
 * ## Why "the user typed the URL" is not a defence
 *
 * A link that points at `127.0.0.1`, a router's admin page or a cloud metadata
 * address is still a link. Every redirect is re-validated by the caller, and the
 * DNS lookup must refuse private, loopback, link-local, multicast and reserved
 * answers before a socket is opened. Without the address check a public hostname
 * could resolve to a private address at connect time (DNS rebinding) even though
 * the string looked public.
 *
 * ## Why the URL is not inspected beyond its shape
 *
 * The tool's URL comes from the model, and a model can encode workspace content
 * into a query string. That is a known and disclosed property of any
 * app-proxied fetch; it cannot be detected by validating the URL. What this
 * object can do is refuse a scheme that is not a web request, refuse embedded
 * credentials, and bound the length.
 */
object DownloadUrl {

	/**
	 * The longest URL the app will act on.
	 *
	 * The model's argument is bounded for the same reason any argument is: an
	 * 8 KiB URL is already far beyond a normal link, and a longer one is more
	 * likely to be an attempt to hide the host from a human reading the
	 * transcript.
	 */
	const val MAX_CHARS = 8192

	/**
	 * Validate a download URL.
	 *
	 * @param requireHttps true for the `download_url` tool, which refuses
	 *   plaintext; the user-facing Files downloader still accepts `http` for
	 *   the old links users actually paste.
	 * @throws IllegalArgumentException with a message the caller can show.
	 */
	fun validate(raw: String, requireHttps: Boolean = true): URI {
		val trimmed = raw.trim()
		require(trimmed.isNotEmpty()) { "URL is empty" }
		require(trimmed.length <= MAX_CHARS) {
			"URL is longer than $MAX_CHARS characters"
		}
		val uri = try {
			URI(trimmed)
		} catch (_: Exception) {
			throw IllegalArgumentException("Not a valid URL")
		}
		val scheme = uri.scheme?.lowercase()
		if (requireHttps) {
			require(scheme == "https") { "Only https URLs can be downloaded" }
		} else {
			require(scheme == "http" || scheme == "https") {
				"Only http and https URLs can be downloaded"
			}
		}
		val host = uri.host?.takeIf { it.isNotBlank() }
			?: throw IllegalArgumentException("The URL has no host")
		require(uri.userInfo == null) {
			"The URL must not contain a username or password"
		}
		require(!isBlockedLiteralHost(host)) {
			"Refusing to download from a private address"
		}
		return uri
	}

	/** A literal IP the user pasted still gets the address check. */
	fun isBlockedLiteralHost(host: String): Boolean {
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
	fun isBlockedAddress(address: InetAddress): Boolean {
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
	 * characters are replaced, and `.`/`..` are refused. Both destinations get
	 * this name, so neither can be steered outside its root by it.
	 */
	fun fileNameFor(uri: URI, contentDisposition: String?): String {
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

	fun sanitizeName(raw: String): String {
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
}
