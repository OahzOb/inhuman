package com.inhuman.core.ai.bilibili

import java.security.MessageDigest

/**
 * Bilibili's WBI request signature.
 *
 * ## Why this is a pure object
 *
 * Two of the three inputs are boring: the parameters and the current second.
 * The third, the mixin key, comes from a network call and rotates daily. The
 * server answers a wrong signature with `-403`, which is indistinguishable from
 * "this endpoint stopped working", so the arithmetic is the one part worth
 * pinning to a fixed vector in a unit test. It does not depend on a device.
 *
 * ## Why the encoding is hand-written
 *
 * The digest is computed over the same percent-encoding the server parses.
 * `java.net.URLEncoder` is close but not equal to it: it maps `~` to `%7E`
 * while the server leaves `~` alone, and it encodes `*` differently in some
 * JDK versions. A keyword containing either character would then produce a
 * valid-looking request with an invalid signature. [encode] implements the
 * server's rule byte for byte instead of trusting a class whose contract is
 * about HTML forms.
 */
object WbiSigner {

	/**
	 * The permutation the server applies to the concatenated nav key filenames.
	 *
	 * Indexes into that 64-character string; the first 32 answers make the
	 * mixin key. Kept as data because it is a copy of the server's table, not
	 * something derived from it.
	 */
	private val MIXIN_ORDER = intArrayOf(
		46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
		27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
		37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
		22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
	)

	/**
	 * Added to every signed request unless the caller supplied its own.
	 *
	 * The library the endpoint was verified against does this for every WBI
	 * call; some endpoints reject a signature that omits it. It is part of the
	 * signed parameter set, not an afterthought appended to the URL.
	 */
	const val DEFAULT_WEB_LOCATION = "1550101"

	/**
	 * Build the mixin key from the two `wbi_img` URLs in the nav response.
	 *
	 * The keys are the last path segment without its extension; 64 characters
	 * from their concatenation, permuted, cut to 32.
	 */
	fun mixinKey(imgUrl: String, subUrl: String): String {
		val raw = fileName(imgUrl) + fileName(subUrl)
		val mixed = StringBuilder(32)
		for (index in MIXIN_ORDER) {
			if (index < raw.length) mixed.append(raw[index])
			if (mixed.length == 32) break
		}
		return mixed.toString()
	}

	/**
	 * Add `wts`, `web_location` and `w_rid` to a parameter set.
	 *
	 * @param wts seconds since the epoch, supplied by the caller so a test can
	 *   pin the digest. The server rejects a timestamp that is too far from its
	 *   own clock, so production passes the current time.
	 */
	fun sign(
		params: Map<String, String>,
		mixinKey: String,
		wts: Long,
	): Map<String, String> {
		val signed = LinkedHashMap(params)
		signed["wts"] = wts.toString()
		if (signed["web_location"].isNullOrBlank()) {
			signed["web_location"] = DEFAULT_WEB_LOCATION
		}
		val query = signed.entries
			.sortedBy { it.key }
			.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
		signed["w_rid"] = md5(query + mixinKey)
		return signed
	}

	/** The query string for a request. Order is irrelevant once signed. */
	fun queryString(params: Map<String, String>): String =
		params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

	/**
	 * Percent-encode one value the way the server parses it.
	 *
	 * Space becomes `+`; the unreserved set is `A-Z a-z 0-9 - _ . ~`; every
	 * other UTF-8 byte becomes `%XX` with uppercase hex. Working on bytes
	 * rather than characters is what makes a Chinese keyword produce the same
	 * string the server will produce for its digest.
	 */
	internal fun encode(value: String): String {
		val bytes = value.toByteArray(Charsets.UTF_8)
		val out = StringBuilder(bytes.size + 8)
		for (byte in bytes) {
			val b = byte.toInt() and 0xFF
			when {
				b == 0x20 -> out.append('+')
				isUnreserved(b) -> out.append(b.toChar())
				else -> {
					out.append('%')
					// Upper case here, because the server's urlencode uses
					// upper-case percent escapes; the digest below uses lower
					// case because MD5 hex is written that way. Two different
					// alphabets, two different constants, on purpose.
					out.append(HEX_UPPER[b shr 4])
					out.append(HEX_UPPER[b and 0x0F])
				}
			}
		}
		return out.toString()
	}

	private fun isUnreserved(b: Int): Boolean =
		b in 'A'.code..'Z'.code ||
			b in 'a'.code..'z'.code ||
			b in '0'.code..'9'.code ||
			b == '-'.code ||
			b == '_'.code ||
			b == '.'.code ||
			b == '~'.code

	private fun fileName(url: String): String =
		url.substringAfterLast('/').substringBefore('.')

	private fun md5(text: String): String {
		val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
		val out = StringBuilder(digest.size * 2)
		for (byte in digest) {
			val b = byte.toInt() and 0xFF
			out.append(HEX_LOWER[b shr 4])
			out.append(HEX_LOWER[b and 0x0F])
		}
		return out.toString()
	}

	/** For percent escapes, where the server writes upper case. */
	private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

	/** For the MD5 digest, where hexadecimal is conventionally lower case. */
	private val HEX_LOWER = "0123456789abcdef".toCharArray()
}
