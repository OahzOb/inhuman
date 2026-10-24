package com.inhuman.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.Role
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.engine.ChatEngine
import com.inhuman.core.engine.EngineEvent
import com.inhuman.core.engine.EngineTurn
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

private val Graphite = Color(0xFF151311)
private val GraphiteRaised = Color(0xFF1D1A17)
private val Paper = Color(0xFFF2EDE7)
private val Muted = Color(0xFFA79C91)
private val Hairline = Color(0xFF3A332D)
private val Signal = Color(0xFFE56B3F)

private val DesktopColors = darkColorScheme(
	primary = Signal,
	onPrimary = Color(0xFF22140E),
	background = Graphite,
	onBackground = Paper,
	surface = GraphiteRaised,
	onSurface = Paper,
	surfaceVariant = Color(0xFF26211D),
	onSurfaceVariant = Muted,
	outline = Hairline,
	error = Color(0xFFFF8A7A),
)

private data class Line(val role: Role, val text: String, val reasoning: String = "")

fun main() {
	val store = DesktopStore.open()
	application {
		Window(
			onCloseRequest = {
				store.close()
				exitApplication()
			},
			title = "inhuman · desktop",
			state = rememberWindowState(width = 900.dp, height = 620.dp),
		) {
			MaterialTheme(colorScheme = DesktopColors) {
				DesktopSpike(store)
			}
		}
	}
}

@Composable
private fun DesktopSpike(store: DesktopStore) {
	val lines = remember { mutableStateListOf<Line>() }
	val scope = rememberCoroutineScope()
	val listState = rememberLazyListState()
	var conversations by remember { mutableStateOf<List<ConversationSummary>>(emptyList()) }
	var selectedId by remember { mutableStateOf<String?>(null) }
	var assistant by remember { mutableStateOf<com.inhuman.core.data.db.AgentEntity?>(null) }
	var providerLabel by remember { mutableStateOf("Demo / demo-model") }
	var showSettings by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	var input by remember { mutableStateOf("") }
	var streaming by remember { mutableStateOf(false) }
	var reasoning by remember { mutableStateOf("") }
	var answer by remember { mutableStateOf("") }
	// The newest row in the persisted conversation; each new message hangs off
	// it so the stored tree, not just the list on screen, is a transcript.
	var lastId by remember { mutableStateOf<String?>(null) }
	var loaded by remember { mutableStateOf(false) }

	LaunchedEffect(Unit) {
		assistant = store.defaultAssistant()
		var all = store.list()
		if (all.isEmpty()) {
			store.create()
			all = store.list()
		}
		conversations = all
		selectedId = all.firstOrNull()?.id
	}

	// Switching is a load, not a filter: the transcript is cleared before the
	// read so a slow query cannot leave the previous conversation's text under
	// the new title.
	LaunchedEffect(selectedId) {
		val id = selectedId ?: return@LaunchedEffect
		loaded = false
		lines.clear()
		lastId = null
		val stored = store.load(id)
		lines.addAll(
			stored.map { message ->
				Line(
					role = if (message.fromUser) Role.User else Role.Assistant,
					text = message.text,
					reasoning = message.reasoning,
				)
			},
		)
		lastId = stored.lastOrNull()?.id
		loaded = true
	}

	fun select(id: String) {
		if (streaming || id == selectedId) return
		selectedId = id
	}

	fun newConversation() {
		if (streaming) return
		scope.launch {
			val created = store.create()
			conversations = store.list()
			selectedId = created.id
		}
	}

	fun send() {
		val conversationId = selectedId ?: return
		val text = input.trim()
		if (text.isEmpty() || streaming || !loaded) return
		input = ""
		reasoning = ""
		answer = ""
		error = null
		streaming = true
		val parentId = lastId
		val firstMessage = lines.isEmpty()
		scope.launch {
			try {
				val resolved = store.resolve(assistant)
				if (resolved.error != null) {
					error = resolved.error
					return@launch
				}
				providerLabel = "${resolved.label} / ${resolved.model}"

				// Persist first: the request history below is built from `lines`,
				// and a message that is on screen but not in the database would
				// make the next launch a different conversation.
				val user = store.appendUser(conversationId, text, parentId)
				lastId = user.id
				lines += Line(Role.User, user.text)
				if (firstMessage) {
					store.autoTitle(conversationId, text)
					conversations = store.list()
				}
				val turn = EngineTurn(
					model = resolved.model,
					messages = lines
						.filter { it.role == Role.User || it.role == Role.Assistant }
						.map { ChatMessage(role = it.role, content = it.text) },
					thinking = assistant?.thinking ?: false,
					reasoningEffort = assistant?.reasoningEffort,
					userId = conversationId,
				)
				try {
					ChatEngine(resolved.provider).run(turn).collect { event ->
						when (event) {
							is EngineEvent.ReasoningDelta -> reasoning += event.text
							is EngineEvent.TextDelta -> answer += event.text
							is EngineEvent.Failed -> {
								answer += "\n\n[stream failure] ${event.message}"
							}
							else -> Unit
						}
					}
				} finally {
					if (answer.isNotBlank() || reasoning.isNotBlank()) {
						val reply = store.appendAssistant(
							conversationId = conversationId,
							text = answer,
							reasoning = reasoning,
							parentId = lastId,
						)
						lastId = reply.id
						lines += Line(Role.Assistant, answer, reasoning)
					}
					conversations = store.list()
				}
			} catch (failure: Exception) {
				error = failure.message ?: failure::class.simpleName
			} finally {
				// Also covers a failed insert: without this the composer would
				// stay locked for the rest of the session.
				streaming = false
				answer = ""
				reasoning = ""
			}
		}
	}

	LaunchedEffect(lines.size, streaming) {
		if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
	}

	val selectedTitle = conversations.firstOrNull { it.id == selectedId }?.title
		?: "New conversation"

	Row(modifier = Modifier.fillMaxSize().background(Graphite)) {
		Sidebar(
			conversations = conversations,
			selectedId = selectedId,
			busy = streaming,
			onSelect = ::select,
			onNew = ::newConversation,
		)
		Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
			TopBar(
				title = selectedTitle,
				provider = providerLabel,
				onSettings = { showSettings = true },
			)
			Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
				if (lines.isEmpty()) {
					EmptyState()
				} else {
					LazyColumn(
						state = listState,
						modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp),
						verticalArrangement = Arrangement.spacedBy(18.dp),
					) {
						items(lines) { line -> TranscriptLine(line) }
						if (streaming && (answer.isNotBlank() || reasoning.isNotBlank())) {
							item {
								TranscriptLine(
									Line(Role.Assistant, answer, reasoning),
									cursor = true,
								)
							}
						}
					}
				}
			}
			error?.let { message ->
				Text(
					text = message,
					color = Color(0xFFFF8A7A),
					fontSize = 12.sp,
					modifier = Modifier
						.fillMaxWidth()
						.background(GraphiteRaised)
						.padding(horizontal = 36.dp, vertical = 8.dp),
				)
			}
			Composer(
				input = input,
				onInput = { input = it },
				streaming = streaming,
				ready = loaded && selectedId != null,
				onSend = ::send,
			)
		}
	}

	if (showSettings) {
		SettingsWindow(store = store, onClose = { showSettings = false })
	}
}

@Composable
private fun Sidebar(
	conversations: List<ConversationSummary>,
	selectedId: String?,
	busy: Boolean,
	onSelect: (String) -> Unit,
	onNew: () -> Unit,
) {
	Column(
		modifier = Modifier
			.width(286.dp)
			.fillMaxHeight()
			.background(Color(0xFF191614))
			.padding(24.dp),
	) {
		Text(
			text = "inhuman",
			fontFamily = FontFamily.Monospace,
			fontWeight = FontWeight.Bold,
			fontSize = 24.sp,
			color = Paper,
		)
		Text(
			text = "私有智能体工作台",
			fontSize = 12.sp,
			color = Muted,
			modifier = Modifier.padding(top = 2.dp),
		)
		Spacer(Modifier.height(28.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = "CONVERSATIONS",
				fontFamily = FontFamily.Monospace,
				fontSize = 11.sp,
				letterSpacing = 1.5.sp,
				color = Signal,
			)
			Spacer(Modifier.weight(1f))
			Text(
				text = "+ NEW",
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.sp,
				color = if (busy) Muted else Signal,
				modifier = Modifier.clickable(enabled = !busy) { onNew() },
			)
		}
		Spacer(Modifier.height(12.dp))
		LazyColumn(modifier = Modifier.weight(1f)) {
			items(conversations, key = { it.id }) { conversation ->
				val selected = conversation.id == selectedId
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.background(
							if (selected) Color(0xFF26211D) else Color.Transparent,
							RoundedCornerShape(6.dp),
						)
						.clickable(enabled = !busy) { onSelect(conversation.id) }
						.padding(horizontal = 10.dp, vertical = 9.dp),
				) {
					Text(
						text = conversation.title,
						fontSize = 13.sp,
						color = if (selected) Paper else Paper.copy(alpha = 0.82f),
						maxLines = 1,
					)
					Text(
						text = ageLabel(conversation.updatedAt),
						fontFamily = FontFamily.Monospace,
						fontSize = 10.sp,
						color = Muted,
						modifier = Modifier.padding(top = 2.dp),
					)
				}
			}
		}
		Spacer(Modifier.height(8.dp))
		Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
		Spacer(Modifier.height(14.dp))
		Text(
			text = "DATABASE",
			fontFamily = FontFamily.Monospace,
			fontSize = 10.sp,
			color = Muted,
		)
		Text(
			text = "inhuman.db · core:db",
			fontFamily = FontFamily.Monospace,
			fontSize = 10.sp,
			color = Muted,
			modifier = Modifier.padding(top = 4.dp),
		)
	}
}

@Composable
private fun TopBar(title: String, provider: String, onSettings: () -> Unit) {
	Column {
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 18.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Paper, maxLines = 1)
				Text(
					provider,
					fontSize = 12.sp,
					color = Muted,
					modifier = Modifier.padding(top = 2.dp),
				)
			}
			Text(
				text = "SETTINGS",
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.sp,
				color = Signal,
				modifier = Modifier.clickable { onSettings() },
			)
		}
		Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
	}
}

private fun ageLabel(at: Long): String {
	val minutes = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
	return when {
		minutes < 1 -> "just now"
		minutes < 60 -> "${minutes}m ago"
		minutes < 1_440 -> "${minutes / 60}h ago"
		else -> "${minutes / 1_440}d ago"
	}
}

@Composable
private fun EmptyState() {
	Column(
		modifier = Modifier.fillMaxSize().padding(48.dp),
		verticalArrangement = Arrangement.Center,
	) {
		Text(
			"桌面端脚手架已经跑起来了。",
			fontSize = 28.sp,
			color = Paper,
			fontWeight = FontWeight.Light,
		)
		Text(
			"这段回复由 core:ai 的 DemoProvider 流式生成，没有网络、没有密钥。" +
				"消息写进桌面数据库，关掉窗口再打开还在。",
			fontSize = 15.sp,
			color = Muted,
			modifier = Modifier.padding(top = 12.dp),
		)
		Text(
			"输入一句话，验证共享核心在 JVM 上工作。",
			fontSize = 13.sp,
			color = Muted,
			modifier = Modifier.padding(top = 22.dp),
		)
	}
}

@Composable
private fun TranscriptLine(line: Line, cursor: Boolean = false) {
	Column(modifier = Modifier.fillMaxWidth()) {
		if (line.role == Role.User) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
				Surface(
					color = Color(0xFF2A241F),
					shape = RoundedCornerShape(10.dp),
					modifier = Modifier.width(540.dp),
				) {
					Text(
						text = line.text,
						modifier = Modifier.padding(14.dp),
						color = Paper,
						fontSize = 15.sp,
						lineHeight = 23.sp,
					)
				}
			}
		} else {
			Text(
				text = "ASSISTANT",
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.2.sp,
				color = Signal,
			)
			if (line.reasoning.isNotBlank()) {
				Text(
					text = line.reasoning.trim(),
					fontSize = 12.sp,
					color = Muted,
					modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
				)
			}
			val markdownState = rememberMarkdownState(line.text.trim().ifBlank { "…" })
			if (cursor) {
				// While the answer is still arriving the renderer would re-parse
				// the whole document on every chunk. The Android chat solves
				// that with a streaming state; the desktop window keeps the raw
				// text until the reply is complete, which is honest about what
				// it is showing and cheap.
				Text(
					text = line.text.trim().ifBlank { "…" } + " ▍",
					fontSize = 15.sp,
					lineHeight = 24.sp,
					color = Paper,
					modifier = Modifier.padding(top = 6.dp),
				)
			} else {
				Markdown(
					markdownState = markdownState,
					colors = markdownColor(
						text = Paper,
						codeBackground = Color(0xFF26211D),
						inlineCodeBackground = Color(0xFF26211D),
						dividerColor = Hairline,
						tableBackground = GraphiteRaised,
					),
					typography = markdownTypography(
						text = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = Paper),
						paragraph = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = Paper),
						h1 = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light, color = Paper),
						h2 = TextStyle(fontSize = 20.sp, color = Paper),
						h3 = TextStyle(fontSize = 17.sp, color = Paper),
						code = TextStyle(
							fontSize = 13.sp,
							fontFamily = FontFamily.Monospace,
							color = Paper,
						),
						inlineCode = TextStyle(
							fontSize = 14.sp,
							fontFamily = FontFamily.Monospace,
							color = Paper,
						),
						quote = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = Muted),
					),
					modifier = Modifier.padding(top = 6.dp),
				)
			}
		}
	}
}

@Composable
private fun Composer(
	input: String,
	onInput: (String) -> Unit,
	streaming: Boolean,
	ready: Boolean,
	onSend: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(GraphiteRaised)
			.padding(horizontal = 36.dp, vertical = 16.dp),
	) {
		OutlinedTextField(
			value = input,
			onValueChange = onInput,
			placeholder = { Text("给这个桌面会话发一条消息…", color = Muted) },
			modifier = Modifier.fillMaxWidth(),
			minLines = 2,
			maxLines = 6,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
			keyboardActions = KeyboardActions(onSend = { onSend() }),
			shape = RoundedCornerShape(8.dp),
		)
		Row(
			modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				if (streaming) "streaming…" else "desktop · shared core",
				fontSize = 11.sp,
				color = Muted,
				fontFamily = FontFamily.Monospace,
			)
			Spacer(Modifier.weight(1f))
			Button(
				onClick = onSend,
				enabled = input.isNotBlank() && !streaming && ready,
				colors = ButtonDefaults.buttonColors(
					containerColor = Signal,
					contentColor = Color(0xFF22140E),
				),
				shape = RoundedCornerShape(6.dp),
			) {
				Text(if (streaming) "Streaming" else "Send")
			}
		}
	}
}

@Composable
private fun SettingsWindow(store: DesktopStore, onClose: () -> Unit) {
	val scope = rememberCoroutineScope()
	var providers by remember { mutableStateOf<List<ProviderProfileEntity>>(emptyList()) }
	var selected by remember { mutableStateOf<ProviderProfileEntity?>(null) }
	var keyStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
	var keyInput by remember { mutableStateOf("") }
	var status by remember { mutableStateOf<String?>(null) }
	var saving by remember { mutableStateOf(false) }

	LaunchedEffect(Unit) {
		providers = store.providers()
		keyStatus = providers.associate { it.id to store.hasProviderKey(it.id) }
		selected = providers.firstOrNull()
	}

	fun refreshKeyStatus() {
		scope.launch {
			keyStatus = providers.associate { it.id to store.hasProviderKey(it.id) }
		}
	}

	DialogWindow(
		onCloseRequest = onClose,
		title = "inhuman · settings",
		state = rememberDialogState(width = 560.dp, height = 470.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(28.dp),
			) {
				Text(
					text = "PROVIDERS",
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					letterSpacing = 1.5.sp,
					color = Signal,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					"Keys are encrypted with Windows DPAPI. Without a key the assistant answers from the demo provider.",
					fontSize = 12.sp,
					color = Muted,
				)
				Spacer(Modifier.height(16.dp))
				LazyColumn(modifier = Modifier.weight(1f)) {
					items(providers, key = { it.id }) { profile ->
						val isSelected = profile.id == selected?.id
						val hasKey = keyStatus[profile.id] == true
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.background(
									if (isSelected) Color(0xFF26211D) else Color.Transparent,
									RoundedCornerShape(6.dp),
								)
								.clickable {
									selected = profile
									keyInput = ""
									status = null
								}
								.padding(12.dp),
						) {
							Text(profile.name, color = Paper, fontSize = 14.sp)
							Text(
								profile.baseUrl,
								fontFamily = FontFamily.Monospace,
								fontSize = 10.sp,
								color = Muted,
								modifier = Modifier.padding(top = 2.dp),
							)
							Text(
								if (hasKey) "KEY SAVED" else "NO KEY",
								fontFamily = FontFamily.Monospace,
								fontSize = 9.sp,
								color = if (hasKey) Signal else Muted,
								modifier = Modifier.padding(top = 4.dp),
							)
						}
					}
				}
				selected?.let { profile ->
					Spacer(Modifier.height(12.dp))
					OutlinedTextField(
						value = keyInput,
						onValueChange = { keyInput = it },
						label = {
							Text(
								if (keyStatus[profile.id] == true) {
									"New key (blank keeps the saved one)"
								} else {
									"API key"
								},
							)
						},
						visualTransformation = PasswordVisualTransformation(),
						singleLine = true,
						modifier = Modifier.fillMaxWidth(),
					)
					Row(
						modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Button(
							onClick = {
								scope.launch {
									saving = true
									status = null
									val value = keyInput.ifBlank { null }
									val ok = store.saveProviderKey(profile.id, value)
									status = when {
										!ok -> "That provider no longer exists."
										value == null -> "Nothing changed."
										else -> "Key saved."
									}
									keyInput = ""
									saving = false
									refreshKeyStatus()
								}
							},
							enabled = !saving,
							colors = ButtonDefaults.buttonColors(
								containerColor = Signal,
								contentColor = Color(0xFF22140E),
							),
							shape = RoundedCornerShape(6.dp),
						) {
							Text("Save key")
						}
						Spacer(Modifier.width(14.dp))
						Text(
							text = "Remove key",
							color = Muted,
							fontSize = 12.sp,
							modifier = Modifier.clickable {
								scope.launch {
									store.saveProviderKey(profile.id, "")
									status = "Key removed; replies use the demo provider."
									refreshKeyStatus()
								}
							},
						)
						Spacer(Modifier.weight(1f))
						Text(
							text = "Done",
							color = Paper,
							fontSize = 13.sp,
							modifier = Modifier.clickable { onClose() },
						)
					}
					status?.let {
						Text(
							text = it,
							color = Muted,
							fontSize = 12.sp,
							modifier = Modifier.padding(top = 8.dp),
						)
					}
				}
			}
		}
	}
}
