package com.inhuman.desktop

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.provider.demo.DemoProvider
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

fun main() = application {
	Window(
		onCloseRequest = ::exitApplication,
		title = "inhuman · desktop spike",
		state = rememberWindowState(width = 900.dp, height = 620.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			DesktopSpike()
		}
	}
}

@Composable
private fun DesktopSpike() {
	val lines = remember { mutableStateListOf<Line>() }
	val provider = remember { DemoProvider(chunkDelayMillis = 8) }
	val scope = rememberCoroutineScope()
	val listState = rememberLazyListState()
	var input by remember { mutableStateOf("") }
	var streaming by remember { mutableStateOf(false) }
	var reasoning by remember { mutableStateOf("") }
	var answer by remember { mutableStateOf("") }

	fun send() {
		val text = input.trim()
		if (text.isEmpty() || streaming) return
		input = ""
		lines += Line(Role.User, text)
		reasoning = ""
		answer = ""
		streaming = true
		scope.launch {
			val request = ChatRequest(
				model = "demo-model",
				messages = lines
					.filter { it.role == Role.User || it.role == Role.Assistant }
					.map { ChatMessage(role = it.role, content = it.text) },
				thinking = false,
			)
			try {
				provider.stream(request).collect { chunk ->
					when (chunk) {
						is StreamChunk.ReasoningDelta -> reasoning += chunk.text
						is StreamChunk.TextDelta -> answer += chunk.text
						is StreamChunk.Failure -> {
							answer += "\n\n[stream failure] ${chunk.message}"
						}
						else -> Unit
					}
				}
			} finally {
				if (answer.isNotBlank() || reasoning.isNotBlank()) {
					lines += Line(Role.Assistant, answer, reasoning)
				}
				streaming = false
				answer = ""
				reasoning = ""
			}
		}
	}

	LaunchedEffect(lines.size, streaming) {
		if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
	}

	Row(modifier = Modifier.fillMaxSize().background(Graphite)) {
		Sidebar()
		Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
			TopBar()
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
			Composer(
				input = input,
				onInput = { input = it },
				streaming = streaming,
				onSend = ::send,
			)
		}
	}
}

@Composable
private fun Sidebar() {
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
		Text(
			text = "DESKTOP SPIKE",
			fontFamily = FontFamily.Monospace,
			fontSize = 11.sp,
			letterSpacing = 1.5.sp,
			color = Signal,
		)
		Spacer(Modifier.height(12.dp))
		Surface(
			color = Color(0xFF211D19),
			shape = RoundedCornerShape(8.dp),
			modifier = Modifier.fillMaxWidth(),
		) {
			Column(Modifier.padding(12.dp)) {
				Text("新对话", color = Paper, fontWeight = FontWeight.Medium)
				Text("Demo provider · no key", color = Muted, fontSize = 12.sp)
			}
		}
		Spacer(Modifier.weight(1f))
		Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
		Spacer(Modifier.height(14.dp))
		Text(
			text = "SHARED CORE",
			fontFamily = FontFamily.Monospace,
			fontSize = 10.sp,
			color = Muted,
		)
		Text(
			text = "core:ai · core:files · core:common",
			fontFamily = FontFamily.Monospace,
			fontSize = 10.sp,
			color = Muted,
			modifier = Modifier.padding(top = 4.dp),
		)
	}
}

@Composable
private fun TopBar() {
	Column {
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 18.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text("新对话", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Paper)
				Text(
					"Demo / demo-model · 桌面端 spike",
					fontSize = 12.sp,
					color = Muted,
					modifier = Modifier.padding(top = 2.dp),
				)
			}
			Text(
				"DEMO",
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				color = Signal,
			)
		}
		Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
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
			"这段回复由 core:ai 的 DemoProvider 流式生成，没有网络、没有密钥。",
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
			Text(
				text = line.text.trim().ifBlank { "…" } + if (cursor) " ▍" else "",
				fontSize = 15.sp,
				lineHeight = 24.sp,
				color = Paper,
				modifier = Modifier.padding(top = 6.dp),
			)
		}
	}
}

@Composable
private fun Composer(
	input: String,
	onInput: (String) -> Unit,
	streaming: Boolean,
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
				enabled = input.isNotBlank() && !streaming,
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
