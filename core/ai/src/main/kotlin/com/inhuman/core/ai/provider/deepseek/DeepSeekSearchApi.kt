package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.tools.SearchProvider
import com.inhuman.core.ai.tools.SearchResult
import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * DeepSeek's Search API, called directly.
 *
 * ## What changed, and why it matters
 *
 * Until 2026-10-17 the app searched through DeepSeek's **server-side tool** on
 * the Anthropic-compatible Messages endpoint: one model call that declared
 * `web_search_20250305` and read the sources out of the reply. That worked, but
 * it meant one search cost a whole model turn in latency and tokens, and the
 * results arrived as content blocks mixed with the model's prose.
 *
 * DeepSeek then opened the search engine as an ordinary JSON API:
 * `POST /v1/tools/search`, described in the
 * [official guide](https://api-docs.deepseek.com/guides/search). It returns
 * structured results -- title, URL, snippet, a long summary, an optional
 * publication date and an authority score -- and it is billed per successful
 * call rather than per token. This class is that API.
 *
 * ## The cost is the product decision
 *
 * Search is a **paid DeepSeek service**: ¥5 per 1,000 successful requests at the
 * time of writing, only for HTTP 200 responses that actually carry results.
 * That is cheap per call, but it is not free, and it is the reason the tool is
 * surfaced to the user as "paid" in the assistant editor rather than added
 * silently.
 *
 * ## Why `need_content` is false
 *
 * The response can carry the page body. The tool needs an excerpt to decide
 * what to open, not the body of every result: fetching ten full pages to answer
 * one question would spend the context window before the model has read
 * anything. `web_fetch` exists for the one page that matters.
 */
class DeepSeekSearchApi(
	/**
	 * Read per call rather than captured, so a key added after a conversation
	 * was opened is picked up without restarting.
	 */
	private val apiKey: () -> String?,
	private val baseUrl: String = DEFAULT_BASE_URL,
	private val client: OkHttpClient = DeepSeekProvider.shared,
) : SearchProvider {

	override val name = "deepseek-search"

	override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
		withContext(Dispatchers.IO) {
			val key = apiKey()?.takeIf { it.isNotBlank() }
				?: throw IOException("no DeepSeek API key is configured")

			val count = maxResults.coerceIn(1, MAX_RESULTS)
			// The query text is user content and is not written; its length is
			// enough to tell two requests apart in the log.
			Diag.i("search", "request api=search count=$count queryChars=${query.length}")

			val payload = buildJsonObject {
				put("query", query)
				put("count", count)
				// Excerpts, not full bodies: see the class comment.
				put("need_content", false)
			}.toString()

			val request = Request.Builder()
				.url("${baseUrl.trimEnd('/')}/v1/tools/search")
				.header("Authorization", "Bearer $key")
				.header("Content-Type", "application/json")
				.post(payload.toRequestBody(JSON))
				.build()

			client.newCall(request).execute().use { response ->
				val text = response.body.string()
				Diag.i(
					"search",
					"response api=search HTTP ${response.code} bytes=${text.length}",
				)
				if (!response.isSuccessful) {
					// The body names the problem ("402 balance", "429 QPS"),
					// which is the difference between a diagnosable failure and
					// "search failed".
					Diag.w(
						"search",
						"HTTP ${response.code} ${response.message}: ${text.take(200)}",
					)
					throw IOException(
						"search failed: HTTP ${response.code} ${response.message} — " +
							text.take(300),
					)
				}

				val results = parseResults(text).take(count)
				Diag.i("search", "results=${results.size}")
				results
			}
		}

	companion object {
		/** The OpenAI-compatible base URL; the tools live under `/v1/tools`. */
		const val DEFAULT_BASE_URL = "https://api.deepseek.com"

		/** The API accepts 1..50; the tool's own default is lower. */
		const val MAX_RESULTS = 50

		private val JSON = "application/json; charset=utf-8".toMediaType()

		/**
		 * Parse the Search API's `results` array.
		 *
		 * `summary` is preferred over `snippet`: it is 600-800 characters and
		 * written to be read by a model, while `snippet` is 100-200 and written
		 * for a person scanning a list. Falling back rather than concatenating
		 * keeps one result to one excerpt.
		 *
		 * Duplicate URLs are dropped after cleaning -- the same page can be
		 * returned twice for a multi-part query, and the model does not need to
		 * read it twice.
		 */
		internal fun parseResults(body: String): List<SearchResult> {
			val root = runCatching {
				DeepSeekJson.parseToJsonElement(body) as? JsonObject
			}.getOrNull() ?: return emptyList()

			val results = root["results"] as? JsonArray ?: return emptyList()
			val seen = HashSet<String>()
			val out = mutableListOf<SearchResult>()

			for (element in results) {
				val item = element as? JsonObject ?: continue
				val rawUrl = item.str("url")?.takeIf { it.isNotBlank() } ?: continue
				val url = cleanUrl(rawUrl)
				// A result the model could be told to open must be a web page.
				// Dropping anything else here is cheaper than teaching the tool
				// layer to distrust its own result list.
				if (!url.startsWith("http://") && !url.startsWith("https://")) continue
				if (!seen.add(url)) continue

				out += SearchResult(
					title = item.str("title")?.takeIf { it.isNotBlank() } ?: url,
					url = url,
					snippet = item.str("summary")?.takeIf { it.isNotBlank() }
						?: item.str("snippet")?.takeIf { it.isNotBlank() },
					publishedAt = item.str("date")?.takeIf { it.isNotBlank() },
				)
			}
			return out
		}

		/**
		 * Strip parameters whose only job is tracking.
		 *
		 * A result URL is cited back to the user, so a campaign parameter is
		 * both noise and a small leak: following the link tells the referrer
		 * which query found it. Only known tracking parameters are removed;
		 * `ref` and `source` are kept because sites use them to select content,
		 * and a broken link is the worse failure.
		 */
		internal fun cleanUrl(raw: String): String {
			// The live API returns some URLs with HTML-escaped separators
			// (`?amp;utm_medium=...&amp;utm_campaign=...`). Splitting on `&`
			// without decoding turns every parameter into `amp;utm_...`, which
			// matches no tracking rule and survives into what the model cites.
			val trimmed = raw.trim().replace("&amp;", "&")
			val queryStart = trimmed.indexOf('?')
			if (queryStart < 0) return trimmed

			val fragmentStart = trimmed.indexOf('#', queryStart)
				.let { if (it < 0) trimmed.length else it }
			val base = trimmed.substring(0, queryStart)
			val fragment = trimmed.substring(fragmentStart)
			val query = trimmed.substring(queryStart + 1, fragmentStart)

			val kept = query.split('&')
				.filter { it.isNotBlank() }
				.filterNot { pair ->
					val name = pair.substringBefore('=').lowercase()
					name in TRACKING || name.startsWith("utm_")
				}

			return if (kept.isEmpty()) base + fragment else "$base?${kept.joinToString("&")}$fragment"
		}

		private val TRACKING = setOf(
			"gclid", "fbclid", "msclkid", "dclid", "yclid", "twclid",
			"mc_cid", "mc_eid", "igshid", "mkt_tok", "vero_id", "vero_conv",
			"_hsenc", "_hsmi", "hsctatracking",
			"spm", "scm", "via", "ref_src", "trk", "trk_params",
		)

		private fun JsonObject.str(key: String): String? =
			(this[key] as? JsonPrimitive)?.contentOrNull
	}
}
