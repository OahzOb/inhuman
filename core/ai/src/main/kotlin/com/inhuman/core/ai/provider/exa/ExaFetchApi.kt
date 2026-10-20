package com.inhuman.core.ai.provider.exa

import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.ai.tools.FetchProvider
import com.inhuman.core.ai.tools.FetchedPage
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
 * Exa's Contents API, used as the fetch half of the web tools.
 *
 * The model gets readable text rather than raw HTML. The tool above this still
 * caps the result, so a huge page cannot consume the whole context window.
 */
class ExaFetchApi(
	private val apiKey: String,
	private val client: OkHttpClient = DeepSeekProvider.shared,
	private val baseUrl: String = DEFAULT_BASE_URL,
	private val maxCharacters: Int = 20_000,
) : FetchProvider {

	override val name: String = "Exa"

	override suspend fun fetch(url: String): FetchedPage {
		if (apiKey.isBlank()) throw IOException("Exa API key is not configured.")
		val body = buildJsonObject {
			put("urls", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(url)) })
			put("text", buildJsonObject { put("maxCharacters", maxCharacters) })
		}.toString()
		val request = Request.Builder()
			.url("${baseUrl.trimEnd('/')}/contents")
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
							"exa contents HTTP ${response.code} bytes=${text.length} " +
								"ms=${System.currentTimeMillis() - started}",
						)
						if (!response.isSuccessful) {
							throw IOException("Exa fetch failed: HTTP ${response.code} ${response.message}")
						}
						parse(text, url)
					}
			} catch (failure: Exception) {
				Diag.w("web", "exa contents failed", failure)
				throw failure
			}
		}
	}

	internal fun parse(body: String, requestedUrl: String): FetchedPage {
		val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
			?: throw IOException("Exa fetch returned unexpected JSON.")
		val results = root["results"]?.let { runCatching { it.jsonArray }.getOrNull() }
			?: emptyList()
		val first = results.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
			?: throw IOException("Exa returned no content for $requestedUrl.")
		val content = first["text"].asString()
			?: throw IOException("Exa returned no readable text for $requestedUrl.")
		return FetchedPage(
			title = first["title"].asString().orEmpty(),
			content = content,
		)
	}

	private fun kotlinx.serialization.json.JsonElement?.asString(): String? {
		val primitive = this as? JsonPrimitive ?: return null
		return primitive.contentOrNull?.takeIf { primitive.isString }
	}

	private companion object {
		val JSON = "application/json".toMediaType()
		const val CALL_TIMEOUT_SECONDS = 45L
		const val DEFAULT_BASE_URL = "https://api.exa.ai"
	}
}
