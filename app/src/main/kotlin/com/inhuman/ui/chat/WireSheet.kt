package com.inhuman.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inhuman.core.design.Hairline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A readable projection of a provider request or assistant message.
 *
 * This is deliberately not a second serialization format. The raw JSON is the
 * source of truth and every card can reveal its own raw node; the sheet exists
 * because JSON punctuation is a poor reading surface for a conversation. Long
 * system prompts, message bodies and tool arguments are collapsed by default.
 */
private data class WireParam(
	val name: String,
	val type: String,
	val required: Boolean,
	val description: String?,
	val children: List<WireParam> = emptyList(),
)

private data class WireTool(
	val name: String,
	val description: String,
	val params: List<WireParam>,
	val rawSchema: String,
)

private data class WireCall(
	val id: String,
	val name: String,
	val argumentsRaw: String,
	val fields: List<Pair<String, String>>,
	val parseError: Boolean,
	val results: List<WireMessage> = emptyList(),
)

private data class WireContent(
	val text: String,
	val isImage: Boolean = false,
)

private data class WireMessage(
	val index: Int,
	val role: String,
	val content: List<WireContent>,
	val contentIsNull: Boolean,
	val reasoning: String?,
	val calls: List<WireCall>,
	val toolCallId: String?,
	val rawJson: String,
)

private data class WireRequest(
	val model: String,
	val params: List<Pair<String, String>>,
	val system: List<Pair<String, String>>,
	val tools: List<WireTool>,
	val messages: List<WireMessage>,
	val rawJson: String,
)

private val prettyJson = Json { prettyPrint = true }
private val compactJson = Json

private fun JsonElement.pretty(): String = prettyJson.encodeToString(JsonElement.serializer(), this)

private fun JsonElement.compact(): String = compactJson.encodeToString(JsonElement.serializer(), this)

/** Replace inline base64 with a size marker, so raw mode stays scrollable. */
private fun redactImageData(json: String): String =
	Regex("\"data:([^;]+);base64,[^\"]+\"").replace(json) {
		"\"data:${it.groupValues[1]};base64,<omitted>\""
	}

private fun splitSystem(content: String): List<Pair<String, String>> {
	val marker = "Earlier conversation summary (the messages it covers are omitted from this request):"
	val at = content.indexOf(marker)
	return if (at < 0) {
		listOf("System message" to content)
	} else {
		buildList {
			val prompt = content.substring(0, at).trim()
			if (prompt.isNotEmpty()) add("Assistant prompt" to prompt)
			add("Earlier conversation summary" to content.substring(at + marker.length).trim())
		}
	}
}

private fun parseContent(element: JsonElement?): Pair<List<WireContent>, Boolean> = when (element) {
	null, JsonNull -> emptyList<WireContent>() to true
	is JsonPrimitive -> listOf(WireContent(element.content)) to false
	is JsonArray -> element.mapNotNull { part ->
		val obj = part as? JsonObject ?: return@mapNotNull null
		when (obj["type"]?.jsonPrimitive?.content) {
			"text" -> WireContent(obj["text"]?.jsonPrimitive?.content.orEmpty())
			"image_url" -> WireContent(
				text = "[image data URL · base64 omitted]",
				isImage = true,
			)
			else -> WireContent(part.compact())
		}
	} to false
	else -> listOf(WireContent(element.compact())) to false
}

private fun parseCall(element: JsonElement): WireCall? {
	val obj = element as? JsonObject ?: return null
	val function = obj["function"] as? JsonObject ?: return null
	val name = function["name"]?.jsonPrimitive?.content ?: "?"
	val raw = function["arguments"]?.let {
		when (it) {
			is JsonPrimitive -> it.content
			else -> it.compact()
		}
	}.orEmpty()
	val fields = runCatching {
		val parsed = Json.parseToJsonElement(raw) as JsonObject
		parsed.entries.map { (key, value) -> key to value.displayValue() }
	}.getOrElse { emptyList() }
	return WireCall(
		id = obj["id"]?.jsonPrimitive?.content.orEmpty(),
		name = name,
		argumentsRaw = raw,
		fields = fields,
		parseError = fields.isEmpty() && raw.isNotBlank(),
	)
}

private fun JsonElement.displayValue(): String = when (this) {
	is JsonPrimitive -> if (isString) "\"${content}\"" else content
	is JsonArray, is JsonObject -> compact()
	else -> toString()
}

private fun parseParams(schema: JsonObject, depth: Int = 0): List<WireParam> {
	if (depth > 3) return emptyList()
	val required = (schema["required"] as? JsonArray)
		?.mapNotNull { (it as? JsonPrimitive)?.content }
		?.toSet()
		.orEmpty()
	val properties = schema["properties"] as? JsonObject ?: return emptyList()
	return properties.entries.map { (name, value) ->
		val obj = value as? JsonObject
		if (obj == null) {
			WireParam(name, "any", name in required, null)
		} else {
			val type = obj["type"]?.jsonPrimitive?.content ?: "object"
			WireParam(
				name = name,
				type = type,
				required = name in required,
				description = obj["description"]?.jsonPrimitive?.content,
				children = parseParams(obj, depth + 1),
			)
		}
	}
}

private fun parseTool(element: JsonElement): WireTool? {
	val function = (element as? JsonObject)?.get("function") as? JsonObject ?: return null
	val name = function["name"]?.jsonPrimitive?.content ?: "?"
	val schema = function["parameters"] as? JsonObject
	return WireTool(
		name = name,
		description = function["description"]?.jsonPrimitive?.content.orEmpty(),
		params = schema?.let(::parseParams).orEmpty(),
		rawSchema = schema?.pretty().orEmpty(),
	)
}

private fun parseRequest(json: String): WireRequest {
	val root = Json.parseToJsonElement(json) as? JsonObject
		?: error("Request body is not a JSON object")
	val messages = (root["messages"] as? JsonArray)?.mapIndexedNotNull { index, element ->
		val obj = element as? JsonObject ?: return@mapIndexedNotNull null
		val role = obj["role"]?.jsonPrimitive?.content ?: "?"
		val (content, contentIsNull) = parseContent(obj["content"])
		WireMessage(
			index = index + 1,
			role = role,
			content = content,
			contentIsNull = contentIsNull,
			reasoning = (obj["reasoning_content"] as? JsonPrimitive)?.content,
			calls = (obj["tool_calls"] as? JsonArray)?.mapNotNull(::parseCall).orEmpty(),
			toolCallId = (obj["tool_call_id"] as? JsonPrimitive)?.content,
			rawJson = obj.pretty(),
		)
	}.orEmpty()

	val system = messages
		.filter { it.role == "system" }
		.flatMap { message -> splitSystem(message.content.joinToString("\n") { it.text }) }
	val visibleMessages = messages.filter { it.role != "system" }
	val params = root.entries
		.filter { it.key !in setOf("model", "messages", "tools") }
		.map { (key, value) -> key to value.displayValue() }

	return WireRequest(
		model = root["model"]?.jsonPrimitive?.content.orEmpty(),
		params = params,
		system = system,
		tools = (root["tools"] as? JsonArray)?.mapNotNull(::parseTool).orEmpty(),
		messages = visibleMessages,
		rawJson = json,
	)
}

private fun parseAssistant(
	message: ChatMessageUi,
	toolResults: List<ChatMessageUi>,
): WireMessage {
	val calls = message.toolCalls.map { call ->
		val raw = call.argumentsJson.ifBlank { "{}" }
		val fields = runCatching {
			(Json.parseToJsonElement(raw) as JsonObject).entries.map {
				(it.key to it.value.displayValue())
			}
		}.getOrElse { emptyList() }
		WireCall(
			id = call.id.orEmpty(),
			name = call.displayName,
			argumentsRaw = raw,
			fields = fields,
			parseError = fields.isEmpty() && raw.isNotBlank(),
			results = toolResults
				.filter { it.toolResultFor == call.id }
				.map { result ->
					WireMessage(
						index = 0,
						role = "tool",
						content = listOf(WireContent(result.plainText)),
						contentIsNull = false,
						reasoning = null,
						calls = emptyList(),
						toolCallId = result.toolResultFor,
						rawJson = buildJsonObject {
							put("role", "tool")
							put("tool_call_id", result.toolResultFor)
							put("content", result.plainText)
						}.pretty(),
					)
				},
		)
	}
	val raw = buildJsonObject {
		put("role", "assistant")
		if (calls.isEmpty()) put("content", message.plainText) else put("content", JsonNull)
		message.reasoningText.takeIf { it.isNotBlank() }?.let { put("reasoning_content", it) }
		if (calls.isNotEmpty()) {
			put("tool_calls", buildJsonArray {
				message.toolCalls.forEach { call ->
					add(buildJsonObject {
						put("id", call.id)
						put("type", "function")
						put("function", buildJsonObject {
							put("name", call.displayName)
							put("arguments", call.argumentsJson.ifBlank { "{}" })
						})
					})
				}
			})
		}
	}.pretty()
	return WireMessage(
		index = 0,
		role = "assistant",
		content = if (message.plainText.isBlank()) emptyList() else listOf(WireContent(message.plainText)),
		contentIsNull = calls.isNotEmpty(),
		reasoning = message.reasoningText.takeIf { it.isNotBlank() },
		calls = calls,
		toolCallId = null,
		rawJson = raw,
	)
}

@Composable
fun WireRequestDialog(snapshot: WireSnapshot, onDismiss: () -> Unit) {
	val view = remember(snapshot.requestJson) {
		runCatching { parseRequest(snapshot.requestJson) }
	}
	WireDialog(
		title = if (snapshot.preview) "Next request preview" else "Provider request",
		subtitle = "${snapshot.model} · ${if (snapshot.preview) "not sent" else formatTime(snapshot.capturedAt)}",
		request = view.getOrNull(),
		assistant = null,
		rawJson = redactImageData(view.getOrNull()?.rawJson ?: snapshot.requestJson),
		error = view.exceptionOrNull()?.message,
		onDismiss = onDismiss,
	)
}

@Composable
fun WireResponseDialog(
	message: ChatMessageUi,
	allMessages: List<ChatMessageUi>,
	onDismiss: () -> Unit,
) {
	val assistant = remember(message.id, allMessages) {
		parseAssistant(message, allMessages)
	}
	WireDialog(
		title = "Assistant wire",
		subtitle = "content · reasoning · tool_calls",
		request = null,
		assistant = assistant,
		rawJson = assistant.rawJson,
		error = null,
		onDismiss = onDismiss,
	)
}

@Composable
private fun WireDialog(
	title: String,
	subtitle: String,
	request: WireRequest?,
	assistant: WireMessage?,
	rawJson: String,
	error: String?,
	onDismiss: () -> Unit,
) {
	val clipboard = LocalClipboardManager.current
	var raw by remember { mutableStateOf(false) }
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier
				.fillMaxWidth(0.94f)
				.fillMaxHeight(0.88f),
			color = MaterialTheme.colorScheme.surface,
			shape = RoundedCornerShape(12.dp),
			tonalElevation = 6.dp,
		) {
			Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Column(modifier = Modifier.weight(1f)) {
						Text(title, style = MaterialTheme.typography.titleMedium)
						Text(
							subtitle,
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							fontFamily = FontFamily.Monospace,
						)
					}
					TextButton(onClick = { raw = !raw }) {
						Text(if (raw) "Sheet" else "Raw JSON")
					}
					TextButton(onClick = onDismiss) { Text("Close") }
				}
				Hairline()
				Spacer(Modifier.height(8.dp))
				if (error != null) {
					Text(
						text = error,
						color = MaterialTheme.colorScheme.error,
						style = MaterialTheme.typography.bodyMedium,
					)
				} else if (raw) {
					Box(
						modifier = Modifier
							.weight(1f)
							.fillMaxWidth()
							.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
							.padding(10.dp),
					) {
						SelectionContainer {
							Text(
								text = rawJson,
								style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
								modifier = Modifier
									.fillMaxHeight()
									.verticalScroll(rememberScrollState())
									.horizontalScroll(rememberScrollState()),
							)
						}
					}
				} else {
					Column(
						modifier = Modifier
							.weight(1f)
							.fillMaxWidth()
							.verticalScroll(rememberScrollState()),
						verticalArrangement = Arrangement.spacedBy(10.dp),
					) {
						if (request != null) {
							Text(
								"POST /chat/completions",
								style = MaterialTheme.typography.labelMedium,
								fontFamily = FontFamily.Monospace,
								color = MaterialTheme.colorScheme.primary,
							)
							WireKeyValues(request.params)
							WireSection("SYSTEM · ${request.system.size} block${if (request.system.size == 1) "" else "s"}")
							request.system.forEach { (source, text) ->
								WireSystemBlock(source, text)
							}
							if (request.tools.isNotEmpty()) {
								WireSection("TOOLS · ${request.tools.size}")
								request.tools.forEach { tool -> WireToolCard(tool) }
							}
							WireSection("MESSAGES · ${request.messages.size}")
							request.messages.forEach { message -> WireMessageCard(message) }
						}
						assistant?.let { message -> WireMessageCard(message) }
					}
				}
				Spacer(Modifier.height(8.dp))
				Hairline()
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
				) {
					TextButton(
						onClick = { clipboard.setText(AnnotatedString(rawJson)) },
					) { Text("Copy JSON") }
				}
			}
		}
	}
}

@Composable
private fun WireSection(label: String) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			label,
			style = MaterialTheme.typography.labelSmall,
			fontFamily = FontFamily.Monospace,
			color = MaterialTheme.colorScheme.primary,
		)
		Spacer(Modifier.width(8.dp))
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	}
}

@Composable
private fun WireKeyValues(values: List<Pair<String, String>>) {
	if (values.isEmpty()) return
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		values.forEach { (key, value) ->
			Row {
				Text(
					key,
					modifier = Modifier.width(112.dp),
					style = MaterialTheme.typography.labelSmall,
					fontFamily = FontFamily.Monospace,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				WireLongText(value, maxLines = 2)
			}
		}
	}
}

@Composable
private fun WireSystemBlock(source: String, text: String) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		Text(
			source,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.primary,
			fontFamily = FontFamily.Monospace,
		)
		WireLongText(text, maxLines = 6)
	}
}

@Composable
private fun WireToolCard(tool: WireTool) {
	var showSchema by remember(tool.name) { mutableStateOf(false) }
	Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
		Text(
			tool.name,
			style = MaterialTheme.typography.labelLarge,
			fontFamily = FontFamily.Monospace,
		)
		if (tool.description.isNotBlank()) WireLongText(tool.description, maxLines = 2)
		tool.params.forEach { param ->
			WireParamRow(param)
		}
		if (tool.rawSchema.isNotBlank()) {
			TextButton(
				onClick = { showSchema = !showSchema },
				modifier = Modifier.height(28.dp),
			) {
				Text(
					if (showSchema) "Hide schema JSON" else "Raw schema JSON",
					style = MaterialTheme.typography.labelSmall,
				)
			}
			if (showSchema) {
				WireLongText(tool.rawSchema, maxLines = 8, monospace = true)
			}
		}
	}
}

@Composable
private fun WireParamRow(param: WireParam, depth: Int = 0) {
	Column(modifier = Modifier.padding(start = (depth * 14).dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				param.name + if (param.required) "*" else "",
				style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colorScheme.onSurface,
			)
			Spacer(Modifier.width(8.dp))
			Text(
				param.type,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			param.description?.takeIf { it.isNotBlank() }?.let {
				Spacer(Modifier.width(8.dp))
				Text(
					it,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		param.children.forEach { WireParamRow(it, depth + 1) }
	}
}

@Composable
private fun WireMessageCard(message: WireMessage) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				if (message.role == "assistant") {
					MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
				} else {
					MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
				},
				RoundedCornerShape(6.dp),
			)
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				buildString {
					if (message.index > 0) append(message.index.toString().padStart(2, '0'))
					if (message.index > 0) append("  ")
					append(message.role.uppercase())
				},
				style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colorScheme.primary,
			)
			message.toolCallId?.let {
				Spacer(Modifier.width(8.dp))
				Text(
					"call $it",
					style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		if (!message.contentIsNull) {
			message.content.forEach { content ->
				if (content.isImage) {
					Text(
						content.text,
						style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				} else {
					WireLongText(content.text, maxLines = 6)
				}
			}
		}
		if (message.contentIsNull) {
			Text(
				"content = null (the wire carries no text for an assistant tool call)",
				style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		message.reasoning?.let { reasoning ->
			Text(
				"reasoning_content",
				style = MaterialTheme.typography.labelSmall,
				fontFamily = FontFamily.Monospace,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			WireLongText(reasoning, maxLines = 4)
		}
		message.calls.forEach { call -> WireCallCard(call) }
	}
}

@Composable
private fun WireCallCard(call: WireCall) {
	Column(
		modifier = Modifier.padding(start = 10.dp, top = 2.dp),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		Row {
			Text(
				"call ${call.id.ifBlank { "?" }}",
				style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colorScheme.primary,
			)
			Spacer(Modifier.width(8.dp))
			Text(call.name, style = MaterialTheme.typography.labelLarge)
		}
		if (call.fields.isEmpty()) {
			Text(
				if (call.argumentsRaw.isBlank()) "arguments = \"\"" else "arguments (raw)",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (call.argumentsRaw.isNotBlank()) WireLongText(call.argumentsRaw, maxLines = 3)
		} else {
			call.fields.forEach { (key, value) ->
				Row {
					Text(
						key,
						modifier = Modifier.width(110.dp),
						style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					WireLongText(value, maxLines = 3)
				}
			}
		}
		call.results.forEach { result ->
			Text(
				"TOOL RESULT · call ${result.toolCallId ?: "?"}",
				style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colorScheme.secondary,
			)
			result.content.forEach { WireLongText(it.text, maxLines = 3) }
		}
	}
}

@Composable
private fun WireLongText(
	text: String,
	maxLines: Int,
	monospace: Boolean = false,
) {
	val isLong = text.length > 320 || text.count { it == '\n' } + 1 > maxLines
	var expanded by remember(text, maxLines) { mutableStateOf(false) }
	Column {
		Text(
			text = text,
			style = if (monospace) {
				MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
			} else {
				MaterialTheme.typography.bodySmall
			},
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = if (expanded) Int.MAX_VALUE else maxLines,
			overflow = TextOverflow.Ellipsis,
		)
		if (isLong) {
			TextButton(
				onClick = { expanded = !expanded },
				modifier = Modifier.height(28.dp),
			) {
				Text(if (expanded) "Show less" else "Show more", style = MaterialTheme.typography.labelSmall)
			}
		}
	}
}

private fun formatTime(millis: Long): String {
	val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
	return format.format(java.util.Date(millis))
}
