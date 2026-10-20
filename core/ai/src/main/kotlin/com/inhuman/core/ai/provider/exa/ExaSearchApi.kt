package com.inhuman.core.ai.provider.exa

import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.ai.tools.SearchProvider
import com.inhuman.core.ai.tools.SearchResult
import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Exa's Search API.
 *
 * Exa returns a page's readable text with each result, which is exactly the
 * "snippet, not just a link" contract [SearchProvider] asks for. The text is
 * capped per result so ten results cannot consume the model's context.
 */
class ExaSearchApi(
	private val apiKey: String,
	private val client: OkHttpClient = DeepSeekProvider.shared,
	private val baseUrl: String = DEFAULT_BASE_URL,
) : SearchProvider {

	override val name: String = "Exa"

	override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
		if (apiKey.isBlank()) throw IOException("Exa API key is not configured.")
		val body = buildJsonObject {
			put("query", query)
			put("numResults", maxResults.coerceIn(1, 25))
			put("type", "auto")
			put("contents", buildJsonObject {
				put("text", buildJsonObject { put("maxCharacters", 1200) })
			})
		}.toString()

		val request = Request.Builder()
			.url("${baseUrl.trimEnd('/')}/search")
			.header("x-api-key", apiKey)
			.header("Content-Type", "application/json")
			.post(body.toRequestBody(JSON))
			.build()

		return withContext(Dispatchers.IO) {
			val started = System.currentTimeMillis()
			try {
				client.newBuilder()
					.callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.build()
					.newCall(request).execute().use { response ->
						val text = response.body.string()
						Diag.i(
							"web",
							"exa search HTTP ${response.code} bytes=${text.length} " +
								"ms=${System.currentTimeMillis() - started}",
						)
						if (!response.isSuccessful) {
							throw IOException("Exa search failed: HTTP ${response.code} ${response.message}")
						}
						parse(text)
					}
			} catch (failure: Exception) {
				Diag.w("web", "exa search failed", failure)
				throw failure
			}
		}
	}

	internal fun parse(body: String): List<SearchResult> {
		val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
			?: throw IOException("Exa search returned unexpected JSON.")
		val results = root["results"]?.let { runCatching { it.jsonArray }.getOrNull() }
			?: return emptyList()
		return results.mapNotNull { element ->
			val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
			val url = obj["url"].asString() ?: return@mapNotNull null
			SearchResult(
				title = obj["title"].asString().orEmpty().ifBlank { url },
				url = url,
				snippet = (obj["text"].asString()
					?: obj["summary"].asString()
					?: obj["author"].asString())?.takeIf { it.isNotBlank() },
				publishedAt = obj["publishedDate"].asString(),
			)
		}
	}

	private fun kotlinx.serialization.json.JsonElement?.asString(): String? {
		val primitive = this as? JsonPrimitive ?: return null
		return primitive.contentOrNull?.takeIf { primitive.isString }
	}

	private companion object {
		val JSON = "application/json".toMediaType()
		const val CALL_TIMEOUT_SECONDS = 30L
		const val DEFAULT_BASE_URL = "https://api.exa.ai"
	}
}
