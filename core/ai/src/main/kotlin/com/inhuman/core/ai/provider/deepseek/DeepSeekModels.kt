package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.RemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
				parse(response.body.string())
			}
		}

	/**
	 * Read a `/models` body.
	 *
	 * Split out from the request so it can be tested against the provider's real
	 * response without a network. The shape it parses is not ours and not
	 * documented per field, so the only defence against a silent change is a
	 * fixture taken from the live API and a test that asserts against it.
	 *
	 * Every field is read defensively: a missing or wrongly-typed value becomes
	 * null rather than throwing, because a provider adding a field, or changing
	 * one this app does not use, must not make the model list disappear.
	 * An entry with no `id` is dropped -- there is nothing to send without one.
	 */
	internal fun parse(body: String): List<RemoteModel> {
		val root = DeepSeekJson.asObjectOrNull(DeepSeekJson.parseToJsonElement(body))
			?: throw IOException("listing models returned unexpected JSON")

		val data = root["data"]?.let { runCatching { it.jsonArray }.getOrNull() }
			?: return emptyList()

		return data.mapNotNull { element ->
			val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
			val id = obj["id"].asStringOrNull() ?: return@mapNotNull null

			val effort = obj["effort"]?.let { runCatching { it.jsonObject }.getOrNull() }
			RemoteModel(
				id = id,
				// `name` is the human-facing label ("DeepSeek-V4.1-Flash") while
				// `id` is what goes on the wire ("deepseek-flash"). Showing the
				// id would be showing an implementation detail; sending the name
				// would be rejected.
				displayName = obj["name"].asStringOrNull(),
				contextWindow = obj["context_window"].asIntOrNull(),
				maxOutputTokens = obj["max_output_tokens"].asIntOrNull(),
				inputModalities = obj["input_modalities"]
					?.let { runCatching { it.jsonArray }.getOrNull() }
					?.mapNotNull { it.asStringOrNull() }
					.orEmpty(),
				supportedEffortLevels = effort?.get("supported_levels")
					?.let { runCatching { it.jsonArray }.getOrNull() }
					?.mapNotNull { it.asStringOrNull() }
					.orEmpty(),
				defaultEffortLevel = effort?.get("default_level").asStringOrNull(),
			)
		}
	}

	/**
	 * A string, or null if the value is not one.
	 *
	 * **Not `jsonPrimitive.contentOrNull`**, which is what this used first and
	 * which is not a type check: it strips quotes off a string and stringifies
	 * everything else, so `"name": 42` becomes the display name `"42"` instead
	 * of being rejected. Caught by a test asserting that a wrongly typed field
	 * becomes null -- the assertion failed, and the parser was what needed
	 * fixing.
	 *
	 * The distinction matters because these values are shown to a user and sent
	 * back to the API. A number where a name was expected is a provider change
	 * or a proxy rewriting the response, and either way the honest result is to
	 * report nothing rather than to display a plausible-looking wrong value.
	 */
	private fun JsonElement?.asStringOrNull(): String? {
		val primitive = (this as? JsonPrimitive) ?: return null
		return primitive.contentOrNull?.takeIf { primitive.isString }
	}

	private fun JsonElement?.asIntOrNull(): Int? {
		val primitive = (this as? JsonPrimitive) ?: return null
		// An integer field arriving as a string ("1048576") is treated as
		// absent. Being lenient here would mean a value that displays correctly
		// but might not be what the provider meant.
		return if (primitive.isString) null else primitive.intOrNull
	}
}
