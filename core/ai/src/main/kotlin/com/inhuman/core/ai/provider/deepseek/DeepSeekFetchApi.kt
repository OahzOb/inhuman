package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.tools.FetchProvider
import com.inhuman.core.ai.tools.FetchedPage
import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.net.URI

/**
 * DeepSeek's Fetch API, called directly.
 *
 * `POST /v1/tools/fetch` takes a URL and returns the page's main content as
 * Markdown. It is the companion to [DeepSeekSearchApi]: search returns excerpts,
 * fetch reads the page those excerpts came from. The call is billed by DeepSeek
 * per successful request (¥5 per 1,000 at the time of writing), not per token.
 *
 * ## Why the app does not fetch the page itself
 *
 * The agent's sandbox has no network access, and it should not grow one for
 * this. DeepSeek performs the request; the app sends a URL to an endpoint it
 * already trusts with the conversation and receives Markdown. A page cannot
 * reach the device's network stack, files or other apps through this path.
 *
 * ## What validation is still worth doing
 *
 * The endpoint accepts only absolute http/https URLs without credentials and
 * limits the length to 8192 characters. Rejecting the obvious cases here turns
 * a paid round trip into an immediate, readable tool error.
 */
class DeepSeekFetchApi(
	private val apiKey: () -> String?,
	private val baseUrl: String = DEFAULT_BASE_URL,
	private val client: OkHttpClient = DeepSeekProvider.shared,
) : FetchProvider {

	override val name = "deepseek-fetch"

	override suspend fun fetch(url: String): FetchedPage = withContext(Dispatchers.IO) {
		val key = apiKey()?.takeIf { it.isNotBlank() }
			?: throw IOException("no DeepSeek API key is configured")

		val target = validateUrl(url)
		Diag.i("fetch", "request chars=${target.length}")

		val payload = buildJsonObject { put("url", target) }.toString()
		val request = Request.Builder()
			.url("${baseUrl.trimEnd('/')}/v1/tools/fetch")
			.header("Authorization", "Bearer $key")
			.header("Content-Type", "application/json")
			.post(payload.toRequestBody(JSON))
			.build()

		client.newCall(request).execute().use { response ->
			val text = response.body.string()
			Diag.i("fetch", "response HTTP ${response.code} bytes=${text.length}")
			if (!response.isSuccessful) {
				Diag.w(
					"fetch",
					"HTTP ${response.code} ${response.message}: ${text.take(200)}",
				)
				throw IOException(
					"fetch failed: HTTP ${response.code} ${response.message} — " +
						text.take(300),
				)
			}

			val page = parsePage(text)
			Diag.i(
				"fetch",
				"page titleChars=${page.title.length} contentChars=${page.content.length}",
			)
			page
		}
	}

	companion object {
		const val DEFAULT_BASE_URL = "https://api.deepseek.com"

		/** The API's own limit; longer URLs are rejected before the call. */
		const val MAX_URL_LENGTH = 8192

		private val JSON = "application/json; charset=utf-8".toMediaType()

		/**
		 * Normalise and check a URL for the Fetch API.
		 *
		 * @throws IllegalArgumentException with a message the model can act on.
		 */
		internal fun validateUrl(raw: String): String {
			val trimmed = raw.trim()
			require(trimmed.isNotEmpty()) { "url is empty" }
			require(trimmed.length <= MAX_URL_LENGTH) {
				"url is longer than $MAX_URL_LENGTH characters"
			}

			val uri = try {
				URI(trimmed)
			} catch (_: Exception) {
				throw IllegalArgumentException("not a valid URL: $trimmed")
			}

			val scheme = uri.scheme?.lowercase()
			require(scheme == "http" || scheme == "https") {
				"only http and https URLs can be fetched"
			}
			require(!uri.host.isNullOrBlank()) { "url has no host" }
			require(uri.userInfo == null) { "url must not contain a username or password" }
			return trimmed
		}

		internal fun parsePage(body: String): FetchedPage {
			val root = runCatching {
				DeepSeekJson.parseToJsonElement(body) as? JsonObject
			}.getOrNull() ?: throw IOException("fetch returned a response that could not be read")

			val title = (root["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
			val content = (root["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
			return FetchedPage(title = title, content = content)
		}
	}
}
