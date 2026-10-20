package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.common.diag.Diag
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
import java.util.concurrent.TimeUnit

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
			val startedAt = System.currentTimeMillis()
			// The shared client's read timeout is eleven minutes on purpose,
			// because a reasoning reply may legitimately take that long to
			// produce its first token. `GET /models` has no such excuse, and
			// inheriting that timeout is how the picker could spin for eleven
			// minutes on a stalled request. Derived client, same pool and
			// dispatcher; only the whole-call ceiling is new.
			val callClient = client.newBuilder()
				.callTimeout(MODELS_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.build()
			val request = Request.Builder()
				.url("$baseUrl/models")
				.header("Authorization", "Bearer $apiKey")
				.get()
				.build()

			Diag.i("models", "request base=$baseUrl")
			try {
				callClient.newCall(request).execute().use { response ->
					val body = response.body.string()
					Diag.i(
						"models",
						"response HTTP ${response.code} bytes=${body.length} " +
							"ms=${System.currentTimeMillis() - startedAt}",
					)
					if (!response.isSuccessful) {
						throw IOException(
							"listing models failed: HTTP ${response.code} ${response.message}" +
								body.take(300).let { " — $it" },
						)
					}
					parse(body)
				}
			} catch (e: Exception) {
				Diag.w(
					"models",
					"request failed after ${System.currentTimeMillis() - startedAt}ms",
					e,
				)
				throw e
			}
		}

	/**
	 * How long the model list may take before the picker reports an error.
	 *
	 * This is not the chat timeout and must not become it: the chat client's
	 * long read timeout exists for reasoning models, while this endpoint is a
	 * small JSON document. A number is required because "no timeout" is what
	 * made the spinner unbounded.
	 */
	private const val MODELS_CALL_TIMEOUT_SECONDS = 20L

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
			// OpenRouter and several compatible servers describe the same facts
			// under `architecture` instead of DeepSeek's flat fields. Reading
			// both is not cosmetic: without it every custom model looks
			// text-only, so the attachment button is disabled for a model that
			// accepts images.
			val architecture = obj["architecture"]
				?.let { runCatching { it.jsonObject }.getOrNull() }
			RemoteModel(
				id = id,
				// `name` is the human-facing label ("DeepSeek-V4.1-Flash") while
				// `id` is what goes on the wire ("deepseek-flash"). Showing the
				// id would be showing an implementation detail; sending the name
				// would be rejected.
				displayName = obj["name"].asStringOrNull(),
				contextWindow = obj["context_window"].asIntOrNull()
					?: architecture?.get("context_length").asIntOrNull(),
				maxOutputTokens = obj["max_output_tokens"].asIntOrNull(),
				inputModalities = obj["input_modalities"]
					?.let { runCatching { it.jsonArray }.getOrNull() }
					?.mapNotNull { it.asStringOrNull() }
					?: architecture?.get("input_modalities")
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
