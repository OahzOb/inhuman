package com.inhuman.ui.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inhuman.R
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.Layout
import com.inhuman.core.design.currentWindowFrame
import kotlinx.coroutines.launch
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

private const val SUMMARY_MARKER =
	"Earlier conversation summary (the messages it covers are omitted from this request):"

/**
 * Split the composed system message for the inspector.
 *
 * The order is assistant prompt, compaction summary, workspace instructions;
 * each is optional. The workspace block is found by its own marker so a
 * summary marker inside the file's text cannot make the block look like part of
 * the summary.
 */
internal fun splitSystem(content: String): List<Pair<String, String>> {
	val workspaceAt = content.indexOf(WORKSPACE_INSTRUCTIONS_MARKER)
	val summaryAt = content.indexOf(SUMMARY_MARKER).takeIf {
		it >= 0 && (workspaceAt < 0 || it < workspaceAt)
	} ?: -1
	return when {
		summaryAt < 0 && workspaceAt < 0 -> listOf("System message" to content)
		summaryAt < 0 -> buildList {
			val prompt = content.substring(0, workspaceAt).trim()
			if (prompt.isNotEmpty()) add("Assistant prompt" to prompt)
			add("Workspace instructions (AGENTS.md)" to content.substring(workspaceAt).trim())
		}
		workspaceAt < 0 -> buildList {
			val prompt = content.substring(0, summaryAt).trim()
			if (prompt.isNotEmpty()) add("Assistant prompt" to prompt)
			add("Earlier conversation summary" to content.substring(summaryAt + SUMMARY_MARKER.length).trim())
		}
		else -> buildList {
			val prompt = content.substring(0, summaryAt).trim()
			if (prompt.isNotEmpty()) add("Assistant prompt" to prompt)
			add(
				"Earlier conversation summary" to content
					.substring(summaryAt + SUMMARY_MARKER.length, workspaceAt)
					.trim(),
			)
			add("Workspace instructions (AGENTS.md)" to content.substring(workspaceAt).trim())
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
	val notSent = stringResource(R.string.wire_not_sent)
	WireDialog(
		title = stringResource(
			if (snapshot.preview) R.string.wire_next_request else R.string.wire_provider_request,
		),
		subtitle = "${snapshot.model} · ${if (snapshot.preview) notSent else formatTime(snapshot.capturedAt)}",
		request = view.getOrNull(),
		assistants = emptyList(),
		rawJson = redactImageData(view.getOrNull()?.rawJson ?: snapshot.requestJson),
		error = view.exceptionOrNull()?.message,
		onDismiss = onDismiss,
	)
}

/**
 * Every assistant round of one turn, in one inspector.
 *
 * The transcript used to put a wire button on each assistant message, so a
 * five-round turn had five. The rounds are still here -- that is the useful
 * part of the detail -- but there is one entrance, at the turn's end.
 */
@Composable
fun WireTurnDialog(
	messages: List<ChatMessageUi>,
	onDismiss: () -> Unit,
) {
	val assistants = remember(messages) {
		messages
			.filter { !it.fromUser && !it.isToolResult }
			.map { parseAssistant(it, messages) }
	}
	val rawJson = remember(assistants) {
		if (assistants.size == 1) {
			assistants.first().rawJson
		} else {
			assistants.joinToString(",\n", prefix = "[\n", postfix = "\n]") { it.rawJson }
		}
	}
	WireDialog(
		title = stringResource(R.string.wire_turn_title),
		subtitle = pluralStringResource(
			R.plurals.wire_turn_rounds,
			assistants.size,
			assistants.size,
		),
		request = null,
		assistants = assistants,
		rawJson = rawJson,
		error = null,
		onDismiss = onDismiss,
	)
}

@Composable
private fun WireDialog(
	title: String,
	subtitle: String,
	request: WireRequest?,
	assistants: List<WireMessage>,
	rawJson: String,
	error: String?,
	onDismiss: () -> Unit,
) {
	val clipboard = LocalClipboard.current
	val clipboardScope = rememberCoroutineScope()
	val clipLabel = stringResource(R.string.wire_clip_label)
	val compact = currentWindowFrame().isCompact
	var raw by remember { mutableStateOf(false) }
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier
				.widthIn(max = Layout.ChatMeasure)
				.fillMaxWidth(if (compact) 0.98f else 0.94f)
				.fillMaxHeight(if (compact) 0.94f else 0.88f),
			color = MaterialTheme.colorScheme.surface,
			shape = RoundedCornerShape(12.dp),
			tonalElevation = 6.dp,
		) {
			Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
				Column {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							title,
							style = MaterialTheme.typography.titleMedium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f),
						)
						TextButton(
							onClick = { raw = !raw },
							contentPadding = PaddingValues(horizontal = 8.dp),
						) {
							Text(
								stringResource(if (raw) R.string.wire_sheet else R.string.wire_raw_json),
								style = MaterialTheme.typography.labelMedium,
							)
						}
						IconButton(onClick = onDismiss) {
							Icon(
								Icons.Filled.Close,
								contentDescription = stringResource(R.string.wire_close_view),
							)
						}
					}
					Text(
						subtitle,
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						fontFamily = FontFamily.Monospace,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
				Spacer(Modifier.height(6.dp))
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
						verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
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
						assistants.forEach { message -> WireMessageCard(message) }
					}
				}
				Spacer(Modifier.height(8.dp))
				Hairline()
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
				) {
					TextButton(
						onClick = {
							clipboardScope.launch {
								clipboard.setClipEntry(
									ClipEntry(ClipData.newPlainText(clipLabel, rawJson)),
								)
							}
						},
					) { Text(stringResource(R.string.wire_copy_json)) }
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
			WireField(key, value, maxLines = 2)
		}
	}
}

/**
 * A key/value pair that chooses one or two columns.
 *
 * On a phone a fixed label column leaves so little width for a search query that
 * the value becomes a narrow ribbon; stacking is clearer. The tablet keeps the
 * denser two-column form.
 */
@Composable
private fun WireField(key: String, value: String, maxLines: Int) {
	val compact = currentWindowFrame().isCompact
	if (compact) {
		Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
			Text(
				key,
				style = MaterialTheme.typography.labelSmall,
				fontFamily = FontFamily.Monospace,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			WireLongText(value, maxLines = maxLines)
		}
	} else {
		Row {
			Text(
				key,
				modifier = Modifier.width(112.dp),
				style = MaterialTheme.typography.labelSmall,
				fontFamily = FontFamily.Monospace,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			WireLongText(value, maxLines = maxLines)
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
					stringResource(
						if (showSchema) R.string.wire_hide_schema else R.string.wire_raw_schema,
					),
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
	Column(
		modifier = Modifier.padding(start = (depth * 14).dp),
		verticalArrangement = Arrangement.spacedBy(1.dp),
	) {
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
		}
		param.description?.takeIf { it.isNotBlank() }?.let {
			Text(
				it,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
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
					shortCallId(it),
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
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				call.name.ifBlank { "tool call" },
				style = MaterialTheme.typography.labelLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Spacer(Modifier.width(8.dp))
			Text(
				shortCallId(call.id),
				style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colorScheme.primary,
			)
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
				WireField(key, value, maxLines = 3)
			}
		}
		call.results.forEach { result ->
			Text(
				"TOOL RESULT · ${shortCallId(result.toolCallId.orEmpty())}",
				style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colorScheme.secondary,
			)
			result.content.forEach { WireLongText(it.text, maxLines = 3) }
		}
	}
}

/**
 * Long provider ids carry a timestamp-like prefix and a random tail; truncating
 * the middle keeps both ends recognisable without letting the id take the whole
 * row. The raw JSON view still has the exact value.
 */
private fun shortCallId(id: String): String = when {
	id.isBlank() -> "call ?"
	id.length <= 22 -> id
	else -> "${id.take(12)}…${id.takeLast(6)}"
}

@Composable
private fun WireLongText(
	text: String,
	maxLines: Int,
	monospace: Boolean = false,
) {
	val isLong = text.length > 320 || text.count { it == '\n' } + 1 > maxLines
	var expanded by remember(text, maxLines) { mutableStateOf(false) }
	val textStyle = if (monospace) {
		MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
	} else {
		MaterialTheme.typography.bodySmall
	}
	Column {
		if (!expanded) {
			Text(
				text = text,
				style = textStyle,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = maxLines,
				overflow = TextOverflow.Ellipsis,
			)
			if (isLong) {
				TextButton(
					onClick = { expanded = true },
					modifier = Modifier.height(28.dp),
				) {
					Text(stringResource(R.string.wire_show_more), style = MaterialTheme.typography.labelSmall)
				}
			}
		} else {
			// The expanded block scrolls inside a bounded box. Leaving the whole
			// body unbounded put stringResource(R.string.wire_show_less) at the bottom of a multi-screen
			// system prompt, so the only way out was to scroll to the end. The
			// control is now always at the top of the expanded region.
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					stringResource(R.string.wire_expanded),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.weight(1f))
				TextButton(
					onClick = { expanded = false },
					modifier = Modifier.height(28.dp),
				) {
					Text(stringResource(R.string.wire_show_less), style = MaterialTheme.typography.labelSmall)
				}
			}
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 240.dp)
					.background(
						MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
						RoundedCornerShape(4.dp),
					)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 8.dp, vertical = 6.dp),
			) {
				Text(
					text = text,
					style = textStyle,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
		}
	}
}

private fun formatTime(millis: Long): String {
	val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
	return format.format(java.util.Date(millis))
}
