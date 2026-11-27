package com.inhuman.ui.files

import com.inhuman.core.ai.tools.DownloadUrl
import com.inhuman.core.ai.tools.GuardedHttp
import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI

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

	private const val USER_AGENT = "inhuman/0.1"

	/**
	 * Validate [raw] and open a GET for it.
	 *
	 * @param requireHttps true for the `download_url` tool, which refuses
	 *   plaintext; this screen still accepts `http` for the links users paste.
	 * @throws IllegalArgumentException for a URL this app refuses before any
	 *   network work, with a message the dialog can show.
	 * @throws IOException for transport and HTTP failures.
	 */
	suspend fun open(
		raw: String,
		requireHttps: Boolean = false,
	): DownloadResponse = withContext(Dispatchers.IO) {
		// The guard lives in :core:ai so the agent's local web tools and the
		// user's download path cannot drift: public addresses only, no cookie
		// jar, and every redirect re-validated before the next socket.
		val opened = try {
			GuardedHttp.default.open(
				raw = raw,
				requireHttps = requireHttps,
				userAgent = USER_AGENT,
				connectTimeoutSeconds = 20,
				readTimeoutSeconds = 60,
			)
		} catch (e: IOException) {
			Diag.w("download", "request failed", e)
			throw e
		}

		val response = opened.response
		val target = opened.finalUri
		try {
			if (!response.isSuccessful) {
				val detail = runCatching { response.body.string().take(200) }.getOrNull()
				Diag.w("download", "response HTTP ${response.code} host=${target.host}")
				throw IOException(
					"download failed: HTTP ${response.code} ${response.message}" +
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
			DownloadResponse(
				fileName = fileNameFor(target, response.header("Content-Disposition")),
				mimeType = mimeTypeOf(response.header("Content-Type")),
				contentLength = body.contentLength().takeIf { it >= 0 },
				body = body.byteStream(),
				source = response,
			)
		} catch (e: IOException) {
			response.close()
			throw e
		}
	}

	/**
	 * `http`/`https`, a host, no user-info, and no literal private IP.
	 *
	 * Package-visible for tests. The rules live in
	 * [com.inhuman.core.ai.tools.DownloadUrl] so the agent's `download_url` tool
	 * and this user-facing path cannot disagree; the tool passes
	 * `requireHttps = true`, this screen keeps accepting `http` for the links
	 * people actually paste. The host's *resolved* addresses are checked by
	 * [safeDns] at connect time, not here: a name is allowed to resolve, but
	 * only to public addresses.
	 */
	internal fun validateUrl(raw: String, requireHttps: Boolean = false): URI =
		DownloadUrl.validate(raw, requireHttps)

	/** Private and special addresses are refused by the shared download rules. */
	internal fun isBlockedAddress(address: InetAddress): Boolean =
		DownloadUrl.isBlockedAddress(address)

	/**
	 * `Content-Disposition` first, then the last path segment, then "download".
	 *
	 * The result is a display name, not a path: separators and control
	 * characters are replaced, and `.`/`..` are refused. The workspace and SAF
	 * both get this name, so neither can be steered outside its root by it.
	 */
	internal fun fileNameFor(uri: URI, contentDisposition: String?): String =
		DownloadUrl.fileNameFor(uri, contentDisposition)

	internal fun sanitizeName(raw: String): String = DownloadUrl.sanitizeName(raw)

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
