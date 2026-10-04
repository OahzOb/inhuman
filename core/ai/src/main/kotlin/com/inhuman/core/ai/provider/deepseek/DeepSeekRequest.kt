package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the request body.
 *
 * Hand-built `JsonObject`s rather than `@Serializable` classes because
 * [ChatRequest.tools] carries a raw JSON-Schema map, and the provider passes it
 * through unexamined. Typing that would mean modelling all of JSON Schema to
 * gain nothing.
 */
internal object DeepSeekRequest {

	fun build(request: ChatRequest): JsonObject = buildJsonObject {
		put("model", request.model)
		put("stream", true)

		put("messages", buildJsonArray {
			request.messages.forEach { add(message(it)) }
		})

		// Ask for usage on a streamed response. Without this the final frame
		// carries no token counts, and the counter has nothing to show.
		put("stream_options", buildJsonObject { put("include_usage", true) })

		request.temperature?.let { put("temperature", JsonPrimitive(it)) }
		request.maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
		request.userId?.let { put("user_id", JsonPrimitive(it)) }

		// DeepSeek has thinking on by default, which is the wrong default for a
		// chat app: "hi" should not cost a paragraph of reasoning. Sent
		// explicitly rather than left to the server.
		request.thinking?.let { enabled ->
			put("thinking", buildJsonObject {
				put("type", if (enabled) "enabled" else "disabled")
			})
		}
		request.reasoningEffort?.let { put("reasoning_effort", JsonPrimitive(it)) }

		if (request.tools.isNotEmpty()) {
			put("tools", buildJsonArray {
				request.tools.forEach { tool ->
					add(buildJsonObject {
						put("type", "function")
						put("function", buildJsonObject {
							put("name", tool.name)
							put("description", tool.description)
							put("parameters", toJson(tool.parameters))
						})
					})
				}
			})
		}
	}

	private fun message(m: ChatMessage): JsonObject = buildJsonObject {
		put("role", m.role.wireName)
		// A tool result is addressed to a specific call; without the id the
		// provider cannot match it and rejects the transcript.
		m.toolCallId?.let { put("tool_call_id", it) }

		// An assistant turn that requested tools sends null content rather than
		// an empty string. The wire format distinguishes "no text" from "empty
		// text", and some providers reject the latter.
		if (m.role == Role.Assistant && m.toolCalls.isNotEmpty()) {
			put("content", JsonNull)
			put("tool_calls", buildJsonArray {
				m.toolCalls.forEach { call ->
					add(buildJsonObject {
						put("id", call.id)
						put("type", "function")
						put("function", buildJsonObject {
							put("name", call.name)
							// Sent back exactly as received. Re-serialising a
							// parsed object would reorder keys and change
							// whitespace, and the transcript is compared against
							// what the provider produced.
							put("arguments", call.argumentsJson)
						})
					})
				}
			})
		} else if (m.images.isNotEmpty()) {
			// Content parts, and only for a message that has images.
			//
			// A plain string stays a plain string everywhere else. Both forms
			// are accepted, but the array form is the one that has been
			// measured with an attachment and there is no reason to route text
			// through a shape it does not need.
			put("content", buildJsonArray {
				if (m.content.isNotBlank()) {
					add(buildJsonObject {
						put("type", "text")
						put("text", m.content)
					})
				}
				m.images.forEach { image ->
					// java.util.Base64 rather than android.util.Base64: this
					// module is unit-tested on the JVM, where the Android one is
					// an unimplemented stub. Available since API 26 against a
					// minimum of 34.
					val encoded = java.util.Base64.getEncoder().encodeToString(image.bytes)
					add(buildJsonObject {
						put("type", "image_url")
						// `image_url` is an OBJECT with a `url` field, not a
						// bare string. Verified against the live API: the string
						// form returns 422 with
						// "invalid type: string, expected struct
						// OpenAIChatMessageContentPartImage". The field being
						// named after a URL invites the wrong guess.
						put("image_url", buildJsonObject {
							put("url", "data:${image.mimeType};base64,$encoded")
						})
					})
				}
			})
		} else {
			put("content", m.content)
		}

		// DeepSeek requires prior reasoning back on the wire when the earlier
		// turn used thinking; see ChatMessage.reasoning for the measurements.
		m.reasoning?.let { put("reasoning_content", it) }
	}

	private val Role.wireName: String
		get() = when (this) {
			Role.System -> "system"
			Role.User -> "user"
			Role.Assistant -> "assistant"
			Role.Tool -> "tool"
		}

	/** Convert a plain Kotlin structure into JSON, recursively. */
	private fun toJson(value: Any?): JsonElement = when (value) {
		null -> JsonNull
		is JsonElement -> value
		is String -> JsonPrimitive(value)
		is Boolean -> JsonPrimitive(value)
		is Int -> JsonPrimitive(value)
		is Long -> JsonPrimitive(value)
		is Double -> JsonPrimitive(value)
		is Float -> JsonPrimitive(value)
		is Number -> JsonPrimitive(value)
		is Map<*, *> -> buildJsonObject {
			value.forEach { (k, v) -> if (k is String) put(k, toJson(v)) }
		}
		is Iterable<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
		is Array<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
		// Anything else has no sane JSON representation; stringify rather than
		// throw, so one odd value cannot fail the whole request.
		else -> JsonPrimitive(value.toString())
	}

	/** Kept for readability at the one call site that needs an empty array. */
	val EmptyArray: JsonArray = JsonArray(emptyList())
}
