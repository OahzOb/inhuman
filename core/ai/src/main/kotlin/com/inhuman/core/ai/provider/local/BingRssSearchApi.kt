package com.inhuman.core.ai.provider.local

import com.inhuman.core.ai.tools.GuardedHttp
import com.inhuman.core.ai.tools.HtmlText
import com.inhuman.core.ai.tools.SearchProvider
import com.inhuman.core.ai.tools.SearchResult
import com.inhuman.core.ai.tools.WebHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLEncoder

/**
 * Web search through Bing's RSS output, requested by the app.
 *
 * `https://www.bing.com/search?q=...&format=rss` is the same organic result list
 * the HTML page carries, as a small XML document (measured at 5–7 KB for ten
 * results). That makes it a much better scrape target than the HTML page: no
 * CSS-wrapped markup, no `bing.com/ck/a` redirect links to unwrap, no 100 KB
 * document on a phone.
 *
 * ## Why DOCTYPE is refused
 *
 * An XML parser that expands external entities is an XXE gadget, and a search
 * response never needs a doctype. Refusing the document is simpler than trusting
 * the parser's settings on every platform.
 */
class BingRssSearchApi(
	private val http: GuardedHttp = GuardedHttp.default,
	private val baseUrl: String = DEFAULT_BASE_URL,
	/** False only in tests, which point [baseUrl] at a local fixture server. */
	private val requireHttps: Boolean = true,
	private val userAgent: String = WebHeaders.USER_AGENT,
	private val acceptLanguage: String = WebHeaders.acceptLanguage(),
) : SearchProvider {

	override val name: String = "bing-rss"

	override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
		withContext(Dispatchers.IO) {
			require(query.isNotBlank()) { "query is blank" }
			val url = "$baseUrl?q=" + URLEncoder.encode(query, "UTF-8") + "&format=rss"
			val response = http.getBytes(
				raw = url,
				requireHttps = requireHttps,
				userAgent = userAgent,
				acceptLanguage = acceptLanguage,
				maxBytes = MAX_BYTES,
				connectTimeoutSeconds = 10,
				readTimeoutSeconds = 15,
				extraHeaders = mapOf("Accept" to "application/rss+xml,text/xml;q=0.9,*/*;q=0.8"),
			)
			if (!response.isSuccessful) {
				throw IOException(
					"search failed: HTTP ${response.status}${response.snippet()}",
				)
			}
			if (response.truncated) {
				throw IOException("search failed: response exceeded $MAX_BYTES bytes")
			}
			parse(
				xml = response.bytes.toString(Charsets.UTF_8),
				maxResults = maxResults,
			)
		}

	internal companion object {
		const val DEFAULT_BASE_URL = "https://www.bing.com/search"
		const val MAX_BYTES = 1024 * 1024

		/** Parse the RSS result list. Pure, so fixtures pin the field mapping. */
		internal fun parse(xml: String, maxResults: Int): List<SearchResult> {
			if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
				throw IOException("search failed: unexpected DOCTYPE in RSS response")
			}
			val document = Jsoup.parse(xml, "", Parser.xmlParser())
			if (document.selectFirst("rss") == null) {
				// A consent/challenge page is not an empty result set.
				throw IOException("search failed: response was not a Bing RSS feed")
			}

			val results = mutableListOf<SearchResult>()
			val seen = HashSet<String>()
			for (item in document.select("item")) {
				val title = item.selectFirst("title")?.text()?.trim().orEmpty()
				val rawLink = item.selectFirst("link")?.text()?.trim().orEmpty()
				val link = rawLink.takeIf {
					it.startsWith("http://") || it.startsWith("https://")
				} ?: continue
				if (title.isEmpty()) continue
				if (!seen.add(link)) continue
				val description = item.selectFirst("description")
					?.text()
					?.let(HtmlText::text)
					?.takeIf { it.isNotEmpty() }
				results += SearchResult(title = title, url = link, snippet = description)
				if (results.size >= maxResults) break
			}
			return results
		}
	}
}

/** First 200 characters of an error body, for a failure the model can read. */
private fun GuardedHttp.Bytes.snippet(): String {
	val text = bytes.toString(Charsets.UTF_8)
		.replace(Regex("\\s+"), " ")
		.trim()
	return if (text.isEmpty()) "" else " — ${text.take(200)}"
}
