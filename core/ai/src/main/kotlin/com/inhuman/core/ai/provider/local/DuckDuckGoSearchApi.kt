package com.inhuman.core.ai.provider.local

import com.inhuman.core.ai.tools.GuardedHttp
import com.inhuman.core.ai.tools.SearchProvider
import com.inhuman.core.ai.tools.SearchResult
import com.inhuman.core.ai.tools.WebHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Web search through DuckDuckGo's server-rendered HTML page, requested by the app.
 *
 * No API, no key, no cookie: this is a normal anonymous GET for the page a
 * browser would get. The result links are DuckDuckGo redirect URLs
 * (`/l/?uddg=<percent-encoded target>`), so they are decoded back to the real
 * destination before the model sees them; a citation that points at a redirect
 * is both noise and a tracking hop.
 *
 * ## Failure contract
 *
 * An HTTP error, a bot-check page or a response that cannot be parsed as a
 * result list is an `IOException` — never an empty list. An empty list means
 * "the engine genuinely found nothing", and a model that sees silence tends to
 * search again with the same query.
 */
class DuckDuckGoSearchApi(
	private val http: GuardedHttp = GuardedHttp.default,
	private val baseUrl: String = DEFAULT_BASE_URL,
	/** False only in tests, which point [baseUrl] at a local fixture server. */
	private val requireHttps: Boolean = true,
	private val userAgent: String = WebHeaders.USER_AGENT,
	private val acceptLanguage: String = WebHeaders.acceptLanguage(),
) : SearchProvider {

	override val name: String = "duckduckgo"

	override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
		withContext(Dispatchers.IO) {
			require(query.isNotBlank()) { "query is blank" }
			val url = "$baseUrl?q=" + URLEncoder.encode(query, "UTF-8")
			val response = http.getBytes(
				raw = url,
				requireHttps = requireHttps,
				userAgent = userAgent,
				acceptLanguage = acceptLanguage,
				maxBytes = MAX_BYTES,
				connectTimeoutSeconds = 10,
				readTimeoutSeconds = 15,
				extraHeaders = mapOf(
					"Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
				),
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
				html = response.bytes.toString(Charsets.UTF_8),
				baseUri = response.finalUri.toString(),
				maxResults = maxResults,
			)
		}

	internal companion object {
		const val DEFAULT_BASE_URL = "https://html.duckduckgo.com/html/"
		const val MAX_BYTES = 1024 * 1024

		/** Parse the public result page. Pure, so fixtures pin the selectors. */
		internal fun parse(html: String, baseUri: String, maxResults: Int): List<SearchResult> {
			val document = Jsoup.parse(html, baseUri)
			val blocks = document.select("div.result, div.web-result")
			if (blocks.isEmpty()) {
				// DuckDuckGo serves a challenge page instead of results when it
				// decides the client is a bot; "no results" and "blocked" are
				// different answers and must not collapse into one.
				val lowered = html.lowercase()
				if ("anomaly" in lowered || "bots use duckduckgo" in lowered) {
					throw IOException(
						"search failed: DuckDuckGo returned a bot-check page " +
							"(try again later, or use another engine)",
					)
				}
				return emptyList()
			}

			val results = mutableListOf<SearchResult>()
			val seen = HashSet<String>()
			for (block in blocks) {
				val link = block.selectFirst("a.result__a") ?: continue
				val title = link.text().trim()
				if (title.isEmpty()) continue
				val url = decodeResultUrl(link.attr("href"), baseUri) ?: continue
				if (!seen.add(url)) continue
				val snippet = block.selectFirst(".result__snippet")
					?.text()
					?.trim()
					?.takeIf { it.isNotEmpty() }
				results += SearchResult(title = title, url = url, snippet = snippet)
				if (results.size >= maxResults) break
			}
			return results
		}

		/**
		 * Resolve the result link to its destination.
		 *
		 * Public DDG results are wrapped in its redirect endpoint; the target is
		 * the `uddg` query parameter, percent-encoded once. Anything that is not
		 * an absolute http(s) URL afterwards is dropped rather than handed to
		 * the model.
		 */
		internal fun decodeResultUrl(href: String, baseUri: String): String? {
			val raw = href.trim()
			if (raw.isEmpty()) return null
			val absolute = runCatching { URI(baseUri).resolve(raw).toString() }
				.getOrNull()
				?: return null
			val query = runCatching { URI(absolute).rawQuery }.getOrNull()
			val target = query
				?.split('&')
				?.firstOrNull { it.startsWith("uddg=") }
				?.substringAfter('=')
				?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
				?: absolute
			return target.takeIf {
				it.startsWith("http://") || it.startsWith("https://")
			}
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
