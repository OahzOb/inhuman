package com.inhuman.core.ai.provider.openai

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
 * OpenAI's Responses API wire adapter.
 *
 * This is not Chat Completions with a different path. Responses models the
 * transcript as typed **input items** (`message`, `function_call`,
 * `function_call_output`), puts the system prompt in `instructions`, calls tools
 * `function` definitions at the top level, and streams a different event set
 * (`response.output_text.delta`, `response.function_call_arguments.delta`, …).
 * The engine still sees the provider-neutral [StreamChunk] stream.
 *
 * ## What is deliberately narrow
 *
 * - `store = false`: the app does not ask the provider to retain a response for
 *   later `previous_response_id` continuation. The transcript is sent whole,
 *   like every other adapter.
 * - Reasoning summaries are displayed; completed reasoning items (with
 *   `encrypted_content`) are collected into the assistant row's provider state
 *   and replayed before the earlier function call only when reasoning is
 *   enabled again. The app never interprets the encrypted content.
 */
internal object OpenAiResponsesWire {

	val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
		encodeDefaults = false
	}

	fun endpoint(baseUrl: String, path: String): String {
		val root = baseUrl.trimEnd('/')
		val api = if (root.endsWith("/v1")) root else "$root/v1"
		return "$api$path"
	}

	fun requestBody(request: ChatRequest): JsonObject {
		val instructions = request.messages
			.filter { it.role == Role.System }
			.mapNotNull { it.content.takeIf(String::isNotBlank) }
			.joinToString("\n\n")
		val reasoning = when {
			request.reasoningEffort != null -> buildJsonObject {
				put("effort", request.reasoningEffort)
			}
			request.thinking == true -> buildJsonObject {
				put("effort", "medium")
			}
			else -> null
		}

		val input = buildJsonArray {
			request.messages.forEach { message ->
				when (message.role) {
					Role.System -> Unit
					Role.User -> userItem(message)?.let(::add)
					Role.Assistant -> assistantItems(message, includeState = reasoning != null).forEach(::add)
					Role.Tool -> {
						add(
							buildJsonObject {
								put("type", "function_call_output")
								put("call_id", message.toolCallId.orEmpty())
								put("output", message.content.ifEmpty { " " })
							},
						)
					}
				}
			}
		}

		return buildJsonObject {
			put("model", request.model)
			put("input", input)
			put("stream", true)
			// The app keeps the transcript; it does not rely on server-side
			// response storage or `previous_response_id`.
			put("store", false)
			// The engine runs calls one at a time; asking for parallel calls
			// would only queue them after the fact.
			put("parallel_tool_calls", false)
			if (instructions.isNotBlank()) put("instructions", instructions)
			if (reasoning != null) {
				put("reasoning", reasoning)
				// Without the encrypted reasoning item, a continuation after a
				// function call has nothing to replay with store=false.
				put("include", buildJsonArray { add("reasoning.encrypted_content") })
			}
			if (request.temperature != null && reasoning == null) {
				put("temperature", request.temperature)
			}
			request.maxTokens?.let { put("max_output_tokens", it) }
			if (request.tools.isNotEmpty()) {
				put(
					"tools",
					buildJsonArray {
						request.tools.forEach { add(tool(it)) }
					},
				)
			}
		}
	}

	private fun userItem(message: ChatMessage): JsonObject? {
		if (message.content.isBlank() && message.images.isEmpty()) return null
		return buildJsonObject {
			put("type", "message")
			put("role", "user")
			put(
				"content",
				buildJsonArray {
					if (message.content.isNotEmpty()) {
						add(
							buildJsonObject {
								put("type", "input_text")
								put("text", message.content)
							},
						)
					}
					message.images.forEach { image ->
						add(
							buildJsonObject {
								put("type", "input_image")
								put(
									"image_url",
									"data:${image.mimeType};base64," +
										Base64.getEncoder().encodeToString(image.bytes),
								)
							},
						)
					}
				},
			)
		}
	}

	private fun assistantItems(message: ChatMessage, includeState: Boolean): JsonArray = buildJsonArray {
		// Opaque reasoning items must precede the call they came with. They are
		// copied verbatim; the app never interprets encrypted_content. They are
		// replayed only when this request enables reasoning: a reasoning item on
		// a non-reasoning request is not the same turn the provider produced.
		if (includeState) {
			message.providerState?.let { raw ->
				runCatching { OpenAiResponsesWire.json.parseToJsonElement(raw) }
					.getOrNull()
					?.let { it as? JsonArray }
					?.forEach { add(it) }
			}
		}
		if (message.content.isNotEmpty()) {
			add(
				buildJsonObject {
					put("type", "message")
					put("role", "assistant")
					put(
						"content",
						buildJsonArray {
							add(
								buildJsonObject {
									put("type", "output_text")
									put("text", message.content)
								},
							)
						},
					)
				},
			)
		}
		message.toolCalls.forEach { call ->
			add(
				buildJsonObject {
					put("type", "function_call")
					// A replayed function_call must keep the provider's item id
					// (`fc_...`), not just the call id its result answers.
					call.providerItemId?.let { put("id", it) }
					put("call_id", call.id)
					put("name", call.name)
					put("arguments", call.argumentsJson)
				},
			)
		}
	}

	private fun tool(spec: ToolSpec): JsonObject = buildJsonObject {
		put("type", "function")
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
 * Decode the Responses SSE event set.
 *
 * Function calls are not streamed as `tool_calls` deltas; they are output items
 * identified by an opaque `item_id`, with arguments arriving under a separate
 * event. The decoder maps `item_id` to the provider's own call index, and turns
 * the item events back into the same start/delta/end shape every other adapter
 * produces.
 */
internal class OpenAiResponsesDecoder {

	private val callIndexes = mutableMapOf<String, Int>()
	private val closedCalls = mutableSetOf<Int>()
	private val stateItems = mutableSetOf<String>()
	private var textStarted = false
	private var reasoningStarted = false
	private var calls = 0
	private var finish: FinishReason? = null
	private var responseId: String? = null
	private var model: String? = null
	private var promptTokens: Int? = null
	private var completionTokens: Int? = null
	private var totalTokens: Int? = null
	private var cachedTokens: Int? = null
	private var reasoningTokens: Int? = null
	private var finished = false

	fun decode(frame: SseFrame): List<StreamChunk> {
		if (frame.data.isBlank() || frame.data == "[DONE]") return emptyList()
		val root = runCatching { OpenAiResponsesWire.json.parseToJsonElement(frame.data).jsonObject }
			.getOrNull() ?: return emptyList()
		val type = root["type"]?.jsonPrimitive?.contentOrNull
			?: frame.event
			?: return emptyList()
		val chunks = mutableListOf<StreamChunk>()
		when (type) {
			"response.output_item.added" -> {
				val item = root["item"]?.jsonObject ?: return emptyList()
				when (item["type"]?.jsonPrimitive?.contentOrNull) {
					"function_call" -> startCall(item, chunks)
					"reasoning" -> Unit
				}
			}

			"response.output_text.delta" -> {
				val delta = root["delta"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
				if (delta.isEmpty()) return emptyList()
				if (!textStarted) {
					textStarted = true
					chunks.add(StreamChunk.TextStart())
				}
				chunks.add(StreamChunk.TextDelta(delta))
			}

			"response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
				val delta = root["delta"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
				if (delta.isEmpty()) return emptyList()
				if (!reasoningStarted) {
					reasoningStarted = true
					chunks.add(StreamChunk.ReasoningStart(type = ReasoningType.Summary))
				}
				chunks.add(StreamChunk.ReasoningDelta(delta))
			}

			"response.function_call_arguments.delta" -> {
				val itemId = root["item_id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
				val index = callIndexes[itemId] ?: return emptyList()
				val delta = root["delta"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
				chunks.add(StreamChunk.ToolCallDelta(index, inputDelta = delta))
			}

			"response.function_call_arguments.done" -> {
				val itemId = root["item_id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
				callIndexes[itemId]?.let { closeCall(it, chunks) }
			}

			"response.output_item.done" -> {
				val item = root["item"]?.jsonObject ?: return emptyList()
				when (item["type"]?.jsonPrimitive?.contentOrNull) {
					"function_call" -> {
						val itemId = item["id"]?.jsonPrimitive?.contentOrNull
						if (itemId != null) {
							if (itemId !in callIndexes) startCall(item, chunks)
							callIndexes[itemId]?.let { closeCall(it, chunks) }
						}
					}

					"reasoning" -> stateOf(item)?.let { chunks.add(StreamChunk.ProviderState(it)) }
				}
			}

			"response.completed", "response.incomplete" -> {
				val response = root["response"]?.jsonObject
				responseId = response?.get("id")?.jsonPrimitive?.contentOrNull ?: responseId
				model = response?.get("model")?.jsonPrimitive?.contentOrNull ?: model
				response?.get("usage")?.jsonObject?.let { usage ->
					promptTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull
					completionTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull
					totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull
					cachedTokens = usage["input_tokens_details"]?.jsonObject
						?.get("cached_tokens")?.jsonPrimitive?.intOrNull
					reasoningTokens = usage["output_tokens_details"]?.jsonObject
						?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull
				}
				val incomplete = response?.get("incomplete_details")?.jsonObject
					?.get("reason")?.jsonPrimitive?.contentOrNull
				// A proxy may drop item events; the completed response is
				// authoritative, so fill in calls and reasoning state once.
				response?.get("output")?.jsonArray?.forEach { element ->
					val item = element.jsonObject
					when (item["type"]?.jsonPrimitive?.contentOrNull) {
						"function_call" -> {
							val itemId = item["id"]?.jsonPrimitive?.contentOrNull
							if (itemId != null && itemId !in callIndexes) startCall(item, chunks)
							itemId?.let { callIndexes[it] }?.let { closeCall(it, chunks) }
						}

						"reasoning" -> stateOf(item)?.let {
							chunks.add(StreamChunk.ProviderState(it))
						}
					}
				}
				finish = when {
					incomplete == "max_output_tokens" -> FinishReason.Length
					incomplete == "content_filter" -> FinishReason.ContentFilter
					calls > 0 -> FinishReason.ToolCalls
					else -> FinishReason.Stop
				}
				chunks.addAll(close())
			}

			"response.failed" -> {
				val message = root["response"]?.jsonObject?.get("error")?.jsonObject
					?.get("message")?.jsonPrimitive?.contentOrNull
					?: root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
					?: "OpenAI Responses request failed"
				chunks.add(StreamChunk.Failure(message))
				finished = true
			}

			"error" -> {
				val message = root["message"]?.jsonPrimitive?.contentOrNull
					?: root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
					?: "OpenAI Responses error"
				chunks.add(StreamChunk.Failure(message))
				finished = true
			}
		}
		return chunks
	}

	fun endOfStream(): List<StreamChunk> {
		if (finished) return emptyList()
		if (finish == null) finish = if (calls > 0) FinishReason.ToolCalls else FinishReason.Unknown
		return close()
	}

	private fun startCall(item: JsonObject, chunks: MutableList<StreamChunk>) {
		val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: return
		if (itemId in callIndexes) return
		val index = calls++
		callIndexes[itemId] = index
		val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
			?: item["id"]?.jsonPrimitive?.contentOrNull
			?: "call-$index"
		chunks.add(
			StreamChunk.ToolCallStart(
				index = index,
				id = callId,
				name = item["name"]?.jsonPrimitive?.contentOrNull,
				itemId = itemId,
			),
		)
		val arguments = item["arguments"]?.jsonPrimitive?.contentOrNull
		if (!arguments.isNullOrEmpty()) {
			chunks.add(StreamChunk.ToolCallDelta(index, inputDelta = arguments))
		}
	}

	private fun closeCall(index: Int, chunks: MutableList<StreamChunk>) {
		if (!closedCalls.add(index)) return
		chunks.add(StreamChunk.ToolCallEnd(index))
	}

	private fun stateOf(item: JsonObject): String? {
		val id = item["id"]?.jsonPrimitive?.contentOrNull
			?: item.toString().hashCode().toString()
		if (!stateItems.add(id)) return null
		return item.toString()
	}

	private fun close(): List<StreamChunk> {
		if (finished) return emptyList()
		finished = true
		val chunks = mutableListOf<StreamChunk>()
		callIndexes.values.forEach { closeCall(it, chunks) }
		if (textStarted) chunks.add(StreamChunk.TextEnd())
		if (reasoningStarted) chunks.add(StreamChunk.ReasoningEnd())
		if (promptTokens != null || completionTokens != null || cachedTokens != null) {
			chunks.add(
				StreamChunk.Usage(
					promptTokens = promptTokens,
					completionTokens = completionTokens,
					totalTokens = totalTokens,
					cachedPromptTokens = cachedTokens,
					reasoningTokens = reasoningTokens,
				),
			)
		}
		chunks.add(
			StreamChunk.Finish(
				reason = finish ?: if (calls > 0) FinishReason.ToolCalls else FinishReason.Stop,
				responseId = responseId,
				model = model,
			),
		)
		return chunks
	}
}