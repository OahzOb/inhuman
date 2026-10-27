package com.inhuman.core.ai.provider.anthropic

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Anthropic Messages wire adapter.
 *
 * This is deliberately a translation of the documented wire format, not a
 * second agent loop: tool execution, approvals and persistence stay in
 * `:core:engine`. The only state this file owns is the current content block
 * index while a response is streaming.
 */
internal object AnthropicWire {

	val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
		encodeDefaults = false
	}

	const val API_VERSION = "2023-06-01"
	const val DEFAULT_MAX_TOKENS = 4096

	/**
	 * The smallest budget Anthropic accepts for extended thinking.
	 *
	 * The budget must also be smaller than `max_tokens`; [thinkingBudgetFor]
	 * returns null rather than a value the API would reject.
	 */
	const val THINKING_BUDGET = 2048

	private fun thinkingBudgetFor(maxTokens: Int): Int? = when {
		maxTokens >= 4096 -> 2048
		maxTokens >= 2048 -> 1024
		else -> null
	}

	fun endpoint(baseUrl: String, path: String): String {
		val root = baseUrl.trimEnd('/')
		return if (root.endsWith("/v1")) "$root$path" else "$root/v1$path"
	}

	fun requestBody(request: ChatRequest): JsonObject {
		val system = request.messages
			.filter { it.role == Role.System }
			.mapNotNull { it.content.takeIf(String::isNotBlank) }
			.joinToString("\n\n")
		val maxTokens = request.maxTokens ?: DEFAULT_MAX_TOKENS
		// Anthropic cannot be told to think and then handed a tool turn whose
		// thinking block has no signature: the replay is rejected. So thinking
		// is enabled only when every tool-using assistant turn in the history
		// has both its reasoning and its signature. An old transcript (or a
		// turn recorded while thinking was off) falls back to a non-thinking
		// request rather than failing the next call.
		val canReplayThinking = request.messages
			.filter {
				it.role == Role.Assistant &&
					(it.toolCalls.isNotEmpty() || !it.reasoning.isNullOrBlank())
			}
			.all { !it.reasoning.isNullOrBlank() && !it.reasoningSignature.isNullOrBlank() }
		val thinkingBudget = thinkingBudgetFor(maxTokens)?.takeIf {
			request.thinking == true && canReplayThinking
		}
		val messages = mutableListOf<JsonObject>()
		var lastRole: String? = null

		fun append(role: String, blocks: JsonArray) {
			if (blocks.isEmpty()) return
			if (lastRole == role && messages.isNotEmpty()) {
				val last = messages.last()
				val merged = buildJsonArray {
					last["content"]?.let { content ->
						if (content is JsonArray) content.forEach { add(it) }
					}
					blocks.forEach { add(it) }
				}
				messages[messages.lastIndex] = buildJsonObject {
					put("role", role)
					put("content", merged)
				}
			} else {
				messages.add(
					buildJsonObject {
						put("role", role)
						put("content", blocks)
					},
				)
				lastRole = role
			}
		}

		request.messages.forEach { message ->
			when (message.role) {
				Role.System -> Unit
				Role.User -> append("user", userBlocks(message))
				Role.Assistant -> append("assistant", assistantBlocks(message, includeThinking = thinkingBudget != null))
				Role.Tool -> append("user", toolResultBlocks(message))
			}
		}

		return buildJsonObject {
			put("model", request.model)
			put("max_tokens", maxTokens)
			put("stream", true)
			if (system.isNotBlank()) put("system", system)
			// Anthropic rejects a temperature other than 1 while extended thinking is
// enabled. The app has no temperature control today, but a future caller must
// not be able to build a request that 400s just because thinking was on.
			if (request.temperature != null && thinkingBudget == null) {
				put("temperature", request.temperature)
			}
			put(
				"messages",
				buildJsonArray {
					messages.forEach { add(it) }
				},
			)
			if (request.tools.isNotEmpty()) {
				put(
					"tools",
					buildJsonArray {
						request.tools.forEach { add(tool(it)) }
					},
				)
			}
			thinkingBudget?.let { budget ->
				put(
					"thinking",
					buildJsonObject {
						put("type", "enabled")
						put("budget_tokens", budget)
					},
				)
			}
		}
	}

	private fun userBlocks(message: ChatMessage): JsonArray = buildJsonArray {
		message.images.forEach { image ->
			add(
				buildJsonObject {
					put("type", "image")
					put(
						"source",
						buildJsonObject {
							put("type", "base64")
							put("media_type", image.mimeType)
							put("data", Base64.getEncoder().encodeToString(image.bytes))
						},
					)
				},
			)
		}
		if (message.content.isNotEmpty()) {
			add(
				buildJsonObject {
					put("type", "text")
					put("text", message.content)
				},
			)
		}
	}

	private fun assistantBlocks(message: ChatMessage, includeThinking: Boolean): JsonArray = buildJsonArray {
		// The signed thinking block must come first and be byte-for-byte the
		// thinking the model produced; only the signature is opaque to us.
		if (
			includeThinking &&
			!message.reasoning.isNullOrBlank() &&
			!message.reasoningSignature.isNullOrBlank()
		) {
			add(
				buildJsonObject {
					put("type", "thinking")
					put("thinking", message.reasoning)
					put("signature", message.reasoningSignature)
				},
			)
		}
		if (message.content.isNotEmpty()) {
			add(
				buildJsonObject {
					put("type", "text")
					put("text", message.content)
				},
			)
		}
		message.toolCalls.forEach { call ->
			add(
				buildJsonObject {
					put("type", "tool_use")
					put("id", call.id)
					put("name", call.name)
					put(
						"input",
						runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }
							.getOrElse { buildJsonObject { } },
					)
				},
			)
		}
	}

	private fun toolResultBlocks(message: ChatMessage): JsonArray = buildJsonArray {
		add(
			buildJsonObject {
				put("type", "tool_result")
				put("tool_use_id", message.toolCallId.orEmpty())
				put("content", message.content.ifEmpty { " " })
			},
		)
	}

	private fun tool(spec: ToolSpec): JsonObject = buildJsonObject {
		put("name", spec.name)
		put("description", spec.description)
		put("input_schema", spec.parameters.toJson())
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
 * Decode Anthropic's named SSE events into the provider-neutral chunk stream.
 *
 * Block indexes are preserved because the engine uses them as the provider's
 * ordering key for parallel tool calls. The decoder does not parse partial
 * `input_json_delta` fragments; [StreamChunk.ToolCallEnd] is where the engine
 * parses the accumulated document once.
 */
internal class AnthropicDecoder {

	private val blockTypes = mutableMapOf<Int, String>()
	private var responseId: String? = null
	private var model: String? = null
	private var finish: FinishReason? = null
	private var promptTokens: Int? = null
	private var completionTokens: Int? = null
	private var cachedTokens: Int? = null
	private var finished = false

	fun decode(frame: SseFrame): List<StreamChunk> {
		val data = frame.data
		if (data.isBlank()) return emptyList()
		val root = runCatching { AnthropicWire.json.parseToJsonElement(data).jsonObject }
			.getOrNull() ?: return emptyList()
		val type = frame.event ?: root["type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
		return when (type) {
			"message_start" -> {
				val message = root["message"]?.jsonObject
				responseId = message?.get("id")?.jsonPrimitive?.contentOrNull
				model = message?.get("model")?.jsonPrimitive?.contentOrNull
				message?.get("usage")?.jsonObject?.let { usage ->
					promptTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull
					cachedTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull
				}
				emptyList()
			}

			"content_block_start" -> {
				val index = root["index"]?.jsonPrimitive?.intOrNull ?: return emptyList()
				val block = root["content_block"]?.jsonObject ?: return emptyList()
				val blockType = block["type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
				blockTypes[index] = blockType
				when (blockType) {
					"text" -> listOf(StreamChunk.TextStart(index.toString()))
					"thinking", "redacted_thinking" -> listOf(
						StreamChunk.ReasoningStart(index.toString(), com.inhuman.core.ai.ReasoningType.Summary),
					)
					"tool_use" -> listOf(
						StreamChunk.ToolCallStart(
							index = index,
							id = block["id"]?.jsonPrimitive?.contentOrNull,
							name = block["name"]?.jsonPrimitive?.contentOrNull,
						),
					)
					else -> emptyList()
				}
			}

			"content_block_delta" -> {
				val index = root["index"]?.jsonPrimitive?.intOrNull ?: return emptyList()
				val delta = root["delta"]?.jsonObject ?: return emptyList()
				when (delta["type"]?.jsonPrimitive?.contentOrNull) {
					"text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull
						?.takeIf { it.isNotEmpty() }
						?.let { listOf(StreamChunk.TextDelta(it)) }
						.orEmpty()
					"thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull
						?.takeIf { it.isNotEmpty() }
						?.let { listOf(StreamChunk.ReasoningDelta(it)) }
						.orEmpty()
					"signature_delta" -> delta["signature"]?.jsonPrimitive?.contentOrNull
						?.takeIf { it.isNotEmpty() }
						?.let { listOf(StreamChunk.ReasoningSignature(it)) }
						.orEmpty()
					"input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull
						?.let { listOf(StreamChunk.ToolCallDelta(index, inputDelta = it)) }
						.orEmpty()
					else -> emptyList()
				}
			}

			"content_block_stop" -> {
				val index = root["index"]?.jsonPrimitive?.intOrNull ?: return emptyList()
				when (blockTypes.remove(index)) {
					"text" -> listOf(StreamChunk.TextEnd(index.toString()))
					"thinking", "redacted_thinking" ->
						listOf(StreamChunk.ReasoningEnd(index.toString()))
					"tool_use" -> listOf(StreamChunk.ToolCallEnd(index))
					else -> emptyList()
				}
			}

			"message_delta" -> {
				val delta = root["delta"]?.jsonObject
				finish = when (delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull) {
					"end_turn", "stop_sequence" -> FinishReason.Stop
					"tool_use" -> FinishReason.ToolCalls
					"max_tokens" -> FinishReason.Length
					"refusal" -> FinishReason.ContentFilter
					"pause_turn" -> FinishReason.Stop
					else -> finish
				}
				root["usage"]?.jsonObject?.let { usage ->
					completionTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull
				}
				emptyList()
			}

			"message_stop" -> {
				finished = true
				buildList {
					addAll(usage())
					add(
						StreamChunk.Finish(
							reason = finish ?: FinishReason.Stop,
							responseId = responseId,
							model = model,
						),
					)
				}
			}

			"error" -> {
				val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
					?: "Anthropic stream error"
				listOf(StreamChunk.Failure(message))
			}

			else -> emptyList()
		}
	}

	fun endOfStream(): List<StreamChunk> {
		if (finished) return emptyList()
		finished = true
		return buildList {
			addAll(usage())
			add(StreamChunk.Finish(finish ?: FinishReason.Unknown, responseId, model))
		}
	}

	private fun usage(): List<StreamChunk> {
		if (promptTokens == null && completionTokens == null && cachedTokens == null) {
			return emptyList()
		}
		val total = listOfNotNull(promptTokens, completionTokens).sum().takeIf { it > 0 }
		return listOf(
			StreamChunk.Usage(
				promptTokens = promptTokens,
				completionTokens = completionTokens,
				totalTokens = total,
				cachedPromptTokens = cachedTokens,
			),
		)
	}
}
