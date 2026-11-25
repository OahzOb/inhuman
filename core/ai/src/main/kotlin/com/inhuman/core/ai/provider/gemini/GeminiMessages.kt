package com.inhuman.core.ai.provider.gemini

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.ReasoningType
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.ToolSpec
import com.inhuman.core.ai.stream.SseFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Google Generative AI wire adapter.
 *
 * The request shape is `contents[].parts[]`, not OpenAI's messages. Text,
 * images, functionCall and functionResponse are all part variants; the adapter
 * is the one place that translation happens.
 */
internal object GeminiWire {

	val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
		encodeDefaults = false
	}

	const val DEFAULT_MAX_TOKENS = 4096

	fun base(baseUrl: String): String {
		val root = baseUrl.trimEnd('/')
		return if (root.endsWith("/v1beta") || root.endsWith("/v1")) root else "$root/v1beta"
	}

	fun modelPath(baseUrl: String, model: String): String {
		val clean = model.removePrefix("models/")
		return "${base(baseUrl)}/models/$clean"
	}

	fun requestBody(request: ChatRequest): JsonObject {
		val system = request.messages
			.filter { it.role == Role.System }
			.mapNotNull { it.content.takeIf(String::isNotBlank) }
			.joinToString("\n\n")
		val toolNames = mutableMapOf<String, String>()
		request.messages.forEach { message ->
			message.toolCalls.forEach { toolNames[it.id] = it.name }
		}

		val contents = mutableListOf<JsonObject>()
		fun append(role: String, parts: JsonArray) {
			if (parts.isEmpty()) return
			val last = contents.lastOrNull()
			if (last != null && last["role"]?.jsonPrimitive?.contentOrNull == role) {
				val merged = buildJsonArray {
					last["parts"]?.jsonArray?.forEach { add(it) }
					parts.forEach { add(it) }
				}
				contents[contents.lastIndex] = buildJsonObject {
					put("role", role)
					put("parts", merged)
				}
			} else {
				contents.add(
					buildJsonObject {
						put("role", role)
						put("parts", parts)
					},
				)
			}
		}

		request.messages.forEach { message ->
			when (message.role) {
				Role.System -> Unit
				Role.User -> append("user", userParts(message))
				Role.Assistant -> append("model", assistantParts(message))
				Role.Tool -> append(
					"user",
					toolResponseParts(message, toolNames[message.toolCallId].orEmpty()),
				)
			}
		}

		return buildJsonObject {
			put("contents", buildJsonArray { contents.forEach { add(it) } })
			if (system.isNotBlank()) {
				put(
					"systemInstruction",
					buildJsonObject {
						put(
							"parts",
							buildJsonArray {
								add(buildJsonObject { put("text", system) })
							},
						)
					},
				)
			}
			if (request.tools.isNotEmpty()) {
				put(
					"tools",
					buildJsonArray {
						add(
							buildJsonObject {
								put(
									"functionDeclarations",
									buildJsonArray {
										request.tools.forEach { add(tool(it)) }
									},
								)
							},
						)
					},
				)
			}
			val generation = buildJsonObject {
				put("maxOutputTokens", request.maxTokens ?: DEFAULT_MAX_TOKENS)
				// Gemini's wire names are camelCase inside `generationConfig`, so
				// the Kotlin `topP` maps to `topP` here -- unlike the
				// OpenAI-compatible adapters, which spell it `top_p`.
				if (request.temperature != null) put("temperature", request.temperature)
				if (request.topP != null) put("topP", request.topP)
			}
			if (generation.isNotEmpty()) put("generationConfig", generation)
		}
	}

	private fun userParts(message: ChatMessage): JsonArray = buildJsonArray {
		message.images.forEach { image ->
			add(
				buildJsonObject {
					put(
						"inlineData",
						buildJsonObject {
							put("mimeType", image.mimeType)
							put("data", Base64.getEncoder().encodeToString(image.bytes))
						},
					)
				},
			)
		}
		if (message.content.isNotEmpty()) {
			add(buildJsonObject { put("text", message.content) })
		}
	}

	private fun assistantParts(message: ChatMessage): JsonArray = buildJsonArray {
		if (message.content.isNotEmpty()) {
			add(buildJsonObject { put("text", message.content) })
		}
		message.toolCalls.forEach { call ->
			add(
				buildJsonObject {
					put(
						"functionCall",
						buildJsonObject {
							put("name", call.name)
							put(
								"args",
								runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }
									.getOrElse { buildJsonObject { } },
							)
						},
					)
					// Gemini returns an opaque signature with a function call
					// and rejects a continued tool turn that omits it. The
					// signature belongs to the part, not to `args`.
					call.thoughtSignature?.let { put("thoughtSignature", it) }
				},
			)
		}
	}

	private fun toolResponseParts(message: ChatMessage, name: String): JsonArray = buildJsonArray {
		add(
			buildJsonObject {
				put(
					"functionResponse",
					buildJsonObject {
						put("name", name)
						put(
							"response",
							buildJsonObject {
								put("result", message.content.ifEmpty { " " })
							},
						)
					},
				)
			},
		)
	}

	private fun tool(spec: ToolSpec): JsonObject = buildJsonObject {
		put("name", spec.name)
		put("description", spec.description)
		put("parameters", spec.parameters.toJson())
	}
}

private fun Any?.toJson(): JsonElement = when (this) {
	null -> JsonNull
	is JsonElement -> this
	is String -> JsonPrimitive(this)
	is Boolean -> JsonPrimitive(this)
	is Number -> JsonPrimitive(this)
	is Map<*, *> -> buildJsonObject {
		forEach { (key, value) -> put(key.toString(), value.toJson()) }
	}
	is Iterable<*> -> buildJsonArray {
		forEach { add(it.toJson()) }
	}
	is Array<*> -> buildJsonArray {
		forEach { add(it.toJson()) }
	}
	else -> JsonPrimitive(toString())
}

/**
 * Decode Gemini's SSE chunks.
 *
 * Gemini sends the whole function call in one part rather than streaming its
 * arguments; the neutral stream still gets start/delta/end so the engine has one
 * path for every provider.
 */
internal class GeminiDecoder {

	private var textStarted = false
	private var reasoningStarted = false
	private var toolCalls = 0
	private var finish: FinishReason? = null
	private var promptTokens: Int? = null
	private var completionTokens: Int? = null
	private var totalTokens: Int? = null
	private var cachedTokens: Int? = null
	private var finished = false

	fun decode(frame: SseFrame): List<StreamChunk> {
		if (frame.data.isBlank()) return emptyList()
		val root = runCatching { GeminiWire.json.parseToJsonElement(frame.data).jsonObject }
			.getOrNull() ?: return emptyList()
		root["usageMetadata"]?.jsonObject?.let { usage ->
			promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull
			completionTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull
			totalTokens = usage["totalTokenCount"]?.jsonPrimitive?.intOrNull
			cachedTokens = usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull
		}
		val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
			?: return emptyList()
		val chunks = mutableListOf<StreamChunk>()
		candidate["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { partElement ->
			val part = partElement.jsonObject
			part["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text ->
				if (part["thought"]?.jsonPrimitive?.contentOrNull == "true") {
					if (!reasoningStarted) {
						reasoningStarted = true
						chunks.add(StreamChunk.ReasoningStart(type = ReasoningType.Summary))
					}
					chunks.add(StreamChunk.ReasoningDelta(text))
				} else {
					if (!textStarted) {
						textStarted = true
						chunks.add(StreamChunk.TextStart())
					}
					chunks.add(StreamChunk.TextDelta(text))
				}
			}
			part["functionCall"]?.jsonObject?.let { call ->
				val name = call["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
				val index = toolCalls++
				chunks.add(
					StreamChunk.ToolCallStart(
						index = index,
						id = "gemini_${index}_$name",
						name = name,
					),
				)
				chunks.add(
					StreamChunk.ToolCallDelta(
						index = index,
						inputDelta = call["args"]?.toString() ?: "{}",
					),
				)
				chunks.add(StreamChunk.ToolCallEnd(index))
				val signature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull
					?: call["thoughtSignature"]?.jsonPrimitive?.contentOrNull
				signature?.takeIf { it.isNotBlank() }?.let {
					chunks.add(StreamChunk.ToolCallSignature(index, it))
				}
			}
			part["thoughtSignature"]?.jsonPrimitive?.contentOrNull
				?.takeIf { it.isNotBlank() && part["functionCall"] == null }
				?.let { chunks.add(StreamChunk.ReasoningSignature(it)) }
		}
		candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
			finish = when (reason) {
				"STOP" -> if (toolCalls > 0) FinishReason.ToolCalls else FinishReason.Stop
				"MAX_TOKENS" -> FinishReason.Length
				"SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" ->
					FinishReason.ContentFilter
				"MALFORMED_FUNCTION_CALL" -> FinishReason.ToolCalls
				else -> FinishReason.Unknown
			}
		}
		if (finish != null) {
			chunks.addAll(close())
		}
		return chunks
	}

	fun endOfStream(): List<StreamChunk> {
		if (finished) return emptyList()
		return close()
	}

	private fun close(): List<StreamChunk> {
		if (finished) return emptyList()
		finished = true
		val chunks = mutableListOf<StreamChunk>()
		if (textStarted) chunks.add(StreamChunk.TextEnd())
		if (reasoningStarted) chunks.add(StreamChunk.ReasoningEnd())
		val prompt = promptTokens
		val completion = completionTokens
		if (prompt != null || completion != null || cachedTokens != null) {
			chunks.add(
				StreamChunk.Usage(
					promptTokens = prompt,
					completionTokens = completion,
					totalTokens = totalTokens,
					cachedPromptTokens = cachedTokens,
				),
			)
		}
		chunks.add(
			StreamChunk.Finish(
				reason = finish ?: if (toolCalls > 0) FinishReason.ToolCalls else FinishReason.Unknown,
			),
		)
		return chunks
	}
}
