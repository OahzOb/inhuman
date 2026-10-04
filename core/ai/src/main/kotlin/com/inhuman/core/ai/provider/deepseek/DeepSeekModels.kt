package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.RemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Reads `GET /models`.
 *
 * The list is fetched rather than hardcoded because DeepSeek reports
 * per-model capabilities the app actually needs: `input_modalities` decides
 * whether an image attachment is even offered (`deepseek-v4-pro` has no
 * vision), `context_window` bounds the transcript, and `effort.supported_levels`
 * is what a reasoning-effort control would be populated from.
 *
 * Hardcoding would work until the provider ships a model, at which point the
 * app either omits it or offers it wrongly.
 */
internal object DeepSeekModels {

	suspend fun fetch(client: OkHttpClient, baseUrl: String, apiKey: String): List<RemoteModel> =
		withContext(Dispatchers.IO) {
			val request = Request.Builder()
				.url("$baseUrl/models")
				.header("Authorization", "Bearer $apiKey")
				.get()
				.build()

			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					throw IOException(
						"listing models failed: HTTP ${response.code} ${response.message}" +
							response.body.string().take(300).let { " — $it" },
					)
				}
				val body = response.body.string()

				val root = DeepSeekJson.asObjectOrNull(DeepSeekJson.parseToJsonElement(body))
					?: throw IOException("listing models returned unexpected JSON")

				val data = root["data"]?.let { runCatching { it.jsonArray }.getOrNull() }
					?: return@withContext emptyList()

				data.mapNotNull { element ->
					val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
					val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

					val effort = obj["effort"]?.let { runCatching { it.jsonObject }.getOrNull() }
					RemoteModel(
						id = id,
						displayName = obj["name"]?.jsonPrimitive?.contentOrNull,
						contextWindow = obj["context_window"]?.jsonPrimitive?.intOrNull,
						maxOutputTokens = obj["max_output_tokens"]?.jsonPrimitive?.intOrNull,
						inputModalities = obj["input_modalities"]
							?.let { runCatching { it.jsonArray }.getOrNull() }
							?.mapNotNull { it.jsonPrimitive.contentOrNull }
							.orEmpty(),
						supportedEffortLevels = effort?.get("supported_levels")
							?.let { runCatching { it.jsonArray }.getOrNull() }
							?.mapNotNull { it.jsonPrimitive.contentOrNull }
							.orEmpty(),
						defaultEffortLevel = effort?.get("default_level")?.jsonPrimitive?.contentOrNull,
					)
				}
			}
		}
}
