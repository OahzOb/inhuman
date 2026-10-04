package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.tools.SearchProvider
import com.inhuman.core.ai.tools.SearchResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Web search through DeepSeek's own service.
 *
 * ## What this is, and what it is not
 *
 * DeepSeek has no search endpoint. Its web search is a **server-side tool** on
 * the Anthropic-compatible Messages API: the request declares
 * `web_search_20250305`, the model decides to use it, the search happens on
 * DeepSeek's side, and the results come back as content blocks. So this makes
 * one model call and reads the sources out of the reply.
 *
 * The cost is stated in DeepSeek's own documentation and is worth repeating
 * because it is not obvious from the outside: **one search costs a whole model
 * turn.** It is not a lookup. That is also why it needs no extra service and no
 * extra key -- the credential is the one already configured for chatting.
 *
 * ## Why the Anthropic endpoint rather than the OpenAI one
 *
 * Measured, not assumed. Declaring the server tool on `/chat/completions`
 * returns **HTTP 422**; it is only understood on `/anthropic/v1/messages`.
 * Declaring it there as an ordinary function tool is accepted but does
 * something different and useless here -- the model asks *this app* to search,
 * which is the thing there is no way to do.
 *
 * ## Why a separate call rather than switching the whole app
 *
 * The conversation streams over the OpenAI-compatible endpoint and everything
 * built on it -- the SSE parser, the tool loop, the reasoning handling -- is
 * shaped for that. Search is a single request and response with nothing to
 * stream, so it takes the endpoint that can do it and leaves the rest alone.
 */
class DeepSeekSearchProvider(
	/**
	 * Read per search rather than held.
	 *
	 * A key captured at construction would be the one configured when the app
	 * started, so a user who added their key afterwards would get a provider
	 * that had been built without one -- and would see "search failed" until
	 * they restarted, with nothing to suggest why.
	 */
	private val apiKey: () -> String?,
	private val model: String = DEFAULT_MODEL,
	private val baseUrl: String = DEFAULT_BASE_URL,
	/**
	 * How many searches the model may run within one request.
	 *
	 * The model chooses whether to search more than once -- two queries on a
	 * vague question is sensible and common -- so this is a ceiling on its
	 * judgement rather than a setting that decides anything on its own.
	 */
	private val maxUses: Int = DEFAULT_MAX_USES,
	private val client: OkHttpClient = defaultClient(),
) : SearchProvider {

	/** Convenience for a key that does not change. */
	constructor(
		apiKey: String,
		model: String = DEFAULT_MODEL,
		baseUrl: String = DEFAULT_BASE_URL,
		maxUses: Int = DEFAULT_MAX_USES,
	) : this({ apiKey }, model, baseUrl, maxUses)

	override val name = "deepseek-official"

	override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
		withContext(Dispatchers.IO) {
			val key = apiKey()?.takeIf { it.isNotBlank() }
				?: throw IOException("no DeepSeek API key is configured")

			val request = Request.Builder()
				.url("${baseUrl.trimEnd('/')}/messages")
				// Both headers. The endpoint is Anthropic-shaped, which reads
				// x-api-key, and DeepSeek also accepts the bearer form; sending
				// both costs nothing and works whichever the deployment expects.
				.header("x-api-key", key)
				.header("Authorization", "Bearer $key")
				.header("anthropic-version", API_VERSION)
				.header("Content-Type", "application/json")
				.post(body(query).toRequestBody(JSON))
				.build()

			client.newCall(request).execute().use { response ->
				val text = response.body.string()
				if (!response.isSuccessful) {
					// The body is included because DeepSeek's errors say what was
					// wrong with the request, and that text is the difference
					// between a diagnosable problem and "search failed".
					throw IOException(
						"search failed: HTTP ${response.code} ${response.message} — " +
							text.take(300),
					)
				}
				// Truncated here rather than asked for: DeepSeek exposes no
				// result-count parameter, so the only way to honour a limit is
				// to cut the list. The caller is told the set was trimmed by
				// `WebSearchTool`, which compares the count against what it asked
				// for.
				DeepSeekSearchResponse.parse(text).take(maxResults)
			}
		}

	/**
	 * The request body.
	 *
	 * The prompt is deliberately plain. It is not asking the model to answer
	 * anything -- only to search and report what it found -- and a prompt that
	 * invited prose would produce prose this class then throws away, at the cost
	 * of the tokens it took to write it.
	 */
	private fun body(query: String): String = buildJsonObject {
		put("model", model)
		put("max_tokens", MAX_TOKENS)
		put(
			"tools",
			JsonArray(
				listOf(
					buildJsonObject {
						put("type", SEARCH_TOOL_TYPE)
						put("name", "web_search")
						put("max_uses", maxUses)
					},
				),
			),
		)
		put(
			"messages",
			JsonArray(
				listOf(
					buildJsonObject {
						put("role", "user")
						put("content", "Search the web for: $query")
					},
				),
			),
		)
	}.toString()

	companion object {
		const val DEFAULT_MODEL = "deepseek-v4-flash"
		const val DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic/v1"
		const val DEFAULT_MAX_USES = 3

		/** Verified against the live endpoint; the version string is required. */
		const val API_VERSION = "2023-06-01"

		/**
		 * The server tool's declared type.
		 *
		 * Dated, and has to be: the Anthropic-shaped tool declarations are
		 * versioned by type name rather than by a separate field, so this string
		 * is the API version for the tool itself.
		 */
		const val SEARCH_TOOL_TYPE = "web_search_20250305"

		private const val MAX_TOKENS = 4096

		private val JSON = "application/json; charset=utf-8".toMediaType()

		/**
		 * Generous, because a search is a whole model turn.
		 *
		 * The default OkHttp read timeout is ten seconds, which a search
		 * regularly exceeds -- the model has to decide to search, run possibly
		 * several queries and then summarise. Timing out at ten seconds would
		 * fail searches that were about to succeed.
		 */
		private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
			.connectTimeout(30, TimeUnit.SECONDS)
			.readTimeout(3, TimeUnit.MINUTES)
			.writeTimeout(30, TimeUnit.SECONDS)
			.build()
	}
}
