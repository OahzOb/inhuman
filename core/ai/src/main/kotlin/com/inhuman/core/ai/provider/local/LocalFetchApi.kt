package com.inhuman.core.ai.provider.local

import com.inhuman.core.ai.tools.DownloadUrl
import com.inhuman.core.ai.tools.FetchedPage
import com.inhuman.core.ai.tools.GuardedHttp
import com.inhuman.core.ai.tools.HtmlText
import com.inhuman.core.ai.tools.PageRenderer
import com.inhuman.core.ai.tools.RenderingFetchProvider
import com.inhuman.core.ai.tools.WebHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Fetch one page directly from the device, without a search/fetch provider.
 *
 * Two paths, one contract:
 *
 * - **static** (default): a guarded anonymous GET, then readable-text extraction
 *   with jsoup. Most articles, docs and blogs are complete here.
 * - **rendered** (`render = true`): the host's browser engine loads the page,
 *   scripts run, and the visible text is extracted afterwards. SPA and
 *   JS-rendered pages need this; everything else should not pay for it.
 *
 * ## What it never does
 *
 * No cookie jar, no credentials, no custom headers from the model, no POST, no
 * file access from the page. The URL passes the same rules as `download_url`
 * (`https` only in production, public addresses only, no embedded credentials),
 * and the WebView path resolves the host and refuses a private answer before
 * the engine opens anything.
 *
 * ## Why extraction runs here
 *
 * The tool caps the output, but the model should never see raw HTML: script and
 * style bodies are a prompt-injection surface with no reading value, and a
 * rendered `innerText` is a different (visible-only) view. The rules live in
 * [HtmlText] so the static and rendered paths return the same shape.
 */
class LocalFetchApi(
	private val renderer: PageRenderer? = null,
	private val http: GuardedHttp = GuardedHttp.default,
	/** False only in tests, which point at a local fixture server. */
	private val requireHttps: Boolean = true,
	private val userAgent: String = WebHeaders.USER_AGENT,
	private val acceptLanguage: String = WebHeaders.acceptLanguage(),
	private val maxBytes: Int = 2 * 1024 * 1024,
	private val maxChars: Int = MAX_CHARS,
) : RenderingFetchProvider {

	override val name: String = "local-fetch"

	override suspend fun fetch(url: String): FetchedPage = fetchPage(url, render = false)

	override suspend fun fetchRendered(url: String): FetchedPage = fetchPage(url, render = true)

	private suspend fun fetchPage(url: String, render: Boolean): FetchedPage =
		withContext(Dispatchers.IO) {
			val uri = DownloadUrl.validate(url, requireHttps)
			val host = uri.host ?: throw IOException("The URL has no host")

			if (render) {
				val pageRenderer = renderer
					?: throw IOException("page rendering is not available on this device")
				// The WebView resolves the name itself; refuse a private answer
				// before the browser engine opens anything.
				http.checkPublicHost(host)
				val page = pageRenderer.render(uri.toString())
				if (page.content.isBlank()) {
					throw IOException("the rendered page had no visible text")
				}
				return@withContext FetchedPage(
					title = page.title.trim(),
					content = page.content.take(maxChars),
				)
			}

			val response = http.getBytes(
				raw = uri.toString(),
				requireHttps = requireHttps,
				userAgent = userAgent,
				acceptLanguage = acceptLanguage,
				maxBytes = maxBytes,
				connectTimeoutSeconds = 10,
				readTimeoutSeconds = 20,
				extraHeaders = mapOf(
					"Accept" to
						"text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8",
				),
			)
			if (!response.isSuccessful) {
				throw IOException("fetch failed: HTTP ${response.status}${response.snippet()}")
			}

			val contentType = response.contentType.orEmpty().lowercase()
			val isHtml = contentType.isEmpty() || "html" in contentType
			val isText = isHtml || contentType.startsWith("text/") ||
				"json" in contentType || "xml" in contentType
			if (!isText) {
				throw IOException(
					"not a text page (${contentType.substringBefore(';')}): " +
						"use download_url + read_document to save its bytes",
				)
			}

			val extracted = if (isHtml) {
				HtmlText.extract(
					bytes = response.bytes,
					contentType = response.contentType,
					baseUri = response.finalUri.toString(),
				)
			} else {
				HtmlText.Extracted(
					title = "",
					text = decodeText(response.bytes, contentType),
				)
			}
			if (extracted.text.isBlank()) {
				throw IOException("fetch returned no readable content from $url")
			}

			val body = buildString {
				append(extracted.text)
				if (response.truncated) {
					appendLine()
					appendLine()
					appendLine("[page truncated at $maxBytes bytes]")
				}
			}
			FetchedPage(title = extracted.title.trim(), content = body.take(maxChars))
		}

	private fun decodeText(bytes: ByteArray, contentType: String): String {
		val charsetName = HtmlText.charsetOf(contentType)
		val charset = charsetName?.let {
			runCatching { java.nio.charset.Charset.forName(it) }.getOrNull()
		} ?: Charsets.UTF_8
		return bytes.toString(charset)
	}

	internal companion object {
		const val MAX_CHARS = 100_000
	}
}

/** First 200 characters of an error body, for a failure the model can read. */
private fun GuardedHttp.Bytes.snippet(): String {
	val text = bytes.toString(Charsets.UTF_8)
		.replace(Regex("\\s+"), " ")
		.trim()
	return if (text.isEmpty()) "" else " — ${text.take(200)}"
}
