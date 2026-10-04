package com.inhuman.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter

/**
 * One conversation.
 *
 * The reply is rendered as its parts rather than as one string: reasoning is
 * dimmed and separated from the answer, because a model that thinks out loud
 * should not push its answer off the top of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
	viewModel: ChatViewModel,
	onBack: () -> Unit,
	onOpenSettings: () -> Unit,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val listState = rememberLazyListState()

	// Re-read the key and demo setting whenever this screen appears. The view
	// model outlives the navigation trip to settings, so without this a key
	// added there is not noticed until the app restarts.
	LaunchedEffect(Unit) { viewModel.refreshConfiguration() }

	// Follow the newest content, but only while a reply is arriving. Scrolling
	// on every state change would yank the view back down while the user is
	// reading something earlier.
	LaunchedEffect(state.isStreaming, state.messages.size) {
		if (state.messages.isNotEmpty()) {
			listState.animateScrollToItem(state.messages.lastIndex)
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("New conversation", style = MaterialTheme.typography.titleMedium) },
				navigationIcon = {
					IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.titleLarge) }
				},
				actions = {
					TextButton(onClick = onOpenSettings) { Text("Key") }
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
					navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				.windowInsetsPadding(WindowInsets.safeDrawing)
				// imePadding, so the composer rides above the keyboard instead
				// of being covered by it.
				.imePadding(),
		) {
			LazyColumn(
				state = listState,
				modifier = Modifier.weight(1f),
				contentPadding = PaddingValues(
					horizontal = comfortableGutter(),
					vertical = 12.dp,
				),
				verticalArrangement = Arrangement.spacedBy(14.dp),
			) {
				items(state.messages, key = { it.id }) { message ->
					MessageBubble(message)
				}
			}

			state.error?.let { error ->
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = comfortableGutter(), vertical = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = error,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.weight(1f),
					)
					TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
				}
			}

			Composer(
				value = state.input,
				onValueChange = viewModel::onInputChange,
				onSend = viewModel::send,
				onStop = viewModel::stop,
				isStreaming = state.isStreaming,
				enabled = state.configured,
			)
		}
	}
}

@Composable
private fun MessageBubble(message: ChatMessageUi) {
	val alignment = if (message.fromUser) Alignment.End else Alignment.Start
	val bubbleColor = if (message.fromUser) {
		MaterialTheme.colorScheme.primaryContainer
	} else {
		MaterialTheme.colorScheme.surface
	}
	val onColor = if (message.fromUser) {
		MaterialTheme.colorScheme.onPrimaryContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}

	Column(
		modifier = Modifier.fillMaxWidth(),
		horizontalAlignment = alignment,
	) {
		Box(
			modifier = Modifier
				.widthIn(max = Layout.ReadableMeasure)
				.background(bubbleColor, RoundedCornerShape(14.dp))
				.padding(horizontal = 14.dp, vertical = 10.dp),
		) {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				message.parts.forEach { part ->
					when (part.kind) {
						AssistantPart.Kind.Reasoning -> Text(
							text = part.text,
							style = MaterialTheme.typography.bodyMedium,
							// Dimmed and in a monospace face: it reads as an
							// aside rather than as the answer.
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							fontFamily = FontFamily.Monospace,
						)

						AssistantPart.Kind.Text -> Text(
							text = part.text,
							style = MaterialTheme.typography.bodyLarge,
							color = onColor,
						)

						AssistantPart.Kind.ToolCall -> Text(
							text = part.text,
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.tertiary,
						)
					}
				}

				if (message.isStreaming && message.parts.isEmpty()) {
					CircularProgressIndicator(
						modifier = Modifier.size(16.dp),
						strokeWidth = 2.dp,
						color = MaterialTheme.colorScheme.primary,
					)
				}
			}
		}

		message.error?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
				modifier = Modifier.padding(top = 4.dp),
			)
		}

		// Token accounting, shown only when the provider reported it. Cache hits
		// are called out because DeepSeek bills them about 50x cheaper, so
		// seeing them is how a user knows prefix caching is working.
		message.usage?.let { u ->
			val bits = buildList {
				u.promptTokens?.let { add("in $it") }
				u.completionTokens?.let { add("out $it") }
				u.cachedPromptTokens?.takeIf { it > 0 }?.let { add("cached $it") }
				u.reasoningTokens?.takeIf { it > 0 }?.let { add("think $it") }
			}
			if (bits.isNotEmpty()) {
				Text(
					text = bits.joinToString(" · "),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 4.dp),
				)
			}
		}
	}
}

@Composable
private fun Composer(
	value: String,
	onValueChange: (String) -> Unit,
	onSend: () -> Unit,
	onStop: () -> Unit,
	isStreaming: Boolean,
	enabled: Boolean,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = comfortableGutter(), vertical = 8.dp),
		verticalAlignment = Alignment.Bottom,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		OutlinedTextField(
			value = value,
			onValueChange = onValueChange,
			modifier = Modifier.weight(1f),
			placeholder = {
				Text(if (enabled) "Message" else "Add an API key to start")
			},
			enabled = enabled && !isStreaming,
			maxLines = 6,
			shape = RoundedCornerShape(20.dp),
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
			keyboardActions = KeyboardActions(),
		)

		// One button, two meanings: send while idle, stop while streaming.
		// Reaching for a separate stop control mid-generation is worse than
		// having the same target do the obvious thing.
		if (isStreaming) {
			FilledIconButton(onClick = onStop) {
				Icon(Icons.Filled.Stop, contentDescription = "Stop")
			}
		} else {
			FilledIconButton(
				onClick = onSend,
				enabled = enabled && value.isNotBlank(),
				colors = IconButtonDefaults.filledIconButtonColors(
					containerColor = MaterialTheme.colorScheme.primary,
					contentColor = MaterialTheme.colorScheme.onPrimary,
				),
			) {
				Icon(Icons.Filled.ArrowUpward, contentDescription = "Send")
			}
		}
	}
}
