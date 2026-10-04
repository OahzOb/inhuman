package com.inhuman.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
	onOpenFiles: () -> Unit = {},
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val listState = rememberLazyListState()
	var showModelPicker by remember { mutableStateOf(false) }

	// Re-read the key and demo setting whenever this screen appears. The view
	// model outlives the navigation trip to settings, so without this a key
	// added there is not noticed until the app restarts.
	LaunchedEffect(Unit) { viewModel.refreshConfiguration() }

	// Follow the newest content while a reply arrives, but never fight the user.
	//
	// Two things matter here. The effect keys on the *number* of messages, not
	// on any content, so a token arriving does not restart it -- keying on
	// something that changes per token would fire an animated scroll many times
	// a second, which is its own kind of flicker. And it only scrolls when the
	// user is already near the bottom: yanking the view down while someone is
	// reading earlier in the conversation is the single most annoying thing a
	// chat app can do.
	val atBottom by remember {
		derivedStateOf {
			val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
			last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
		}
	}
	LaunchedEffect(state.messages.size) {
		if (state.messages.isNotEmpty() && atBottom) {
			// scrollToItem, not animateScrollToItem: the list is already growing
			// under the user's eyes, and an animation racing the arrival of the
			// next chunk is what makes streaming look unsteady.
			listState.scrollToItem(state.messages.lastIndex)
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					// Tapping the title opens the model controls. The title is
					// the only thing in the bar that is not already a button, and
					// putting the current model under it means the settings are
					// visible without being in the way -- which matters because
					// they change what an answer costs.
					Column(
						modifier = Modifier.clickable { showModelPicker = true },
					) {
						Text(
							text = state.title,
							style = MaterialTheme.typography.titleMedium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							text = buildString {
								append(state.modelLabel)
								if (state.thinking) {
									append(" · thinking")
									state.reasoningEffort?.let { append(" ($it)") }
								}
							},
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				},
				navigationIcon = {
					IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.titleLarge) }
				},
				actions = {
					TextButton(onClick = onOpenFiles) { Text("Files") }
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

	// The model and reasoning controls, for this conversation only.
	//
	// Opened from the title rather than given a button: it is a setting that is
	// looked at occasionally and matters constantly, and a bar with Files, Key
	// and Model in it is a bar with no room for the conversation's name.
	if (showModelPicker) {
		ModelPickerDialog(
			chat = viewModel,
			currentModel = state.model,
			effectiveModel = state.effectiveModel,
			currentEffort = state.reasoningEffort,
			currentThinking = state.thinking,
			onDismiss = { showModelPicker = false },
		)
	}

	// The approval gate.
	// Rendered as a dialog over the transcript rather than inline, because it
	// blocks the turn: nothing else happens until it is answered, and an inline
	// card could be scrolled past without noticing that the run has stopped.
	//
	// The text is written for a person, not for the model. Showing the raw tool
	// name and JSON arguments would be technically complete and practically
	// useless -- the point of the gate is that someone reads it before saying
	// yes, and nobody reads `{"path":"a.txt"}` carefully.
	state.pendingApproval?.let { pending ->
		AlertDialog(
			onDismissRequest = viewModel::declinePendingCall,
			title = { Text("Allow this action?") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(pending.summary, style = MaterialTheme.typography.bodyLarge)
					if (pending.call.argumentsJson.isNotBlank()) {
						Text(
							text = pending.call.argumentsJson,
							style = MaterialTheme.typography.bodyMedium,
							fontFamily = FontFamily.Monospace,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier
								.fillMaxWidth()
								.background(
									MaterialTheme.colorScheme.surfaceVariant,
									RoundedCornerShape(8.dp),
								)
								.padding(10.dp),
						)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = viewModel::approvePendingCall) { Text("Allow") }
			},
			dismissButton = {
				TextButton(onClick = viewModel::declinePendingCall) { Text("Decline") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
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

						AssistantPart.Kind.Text -> if (message.fromUser) {
							// The user's own text is rendered literally. They
							// typed it; interpreting their asterisks as
							// formatting would silently change what they said.
							Text(
								text = part.text,
								style = MaterialTheme.typography.bodyLarge,
								color = onColor,
							)
						} else {
							MarkdownText(text = part.text)
						}

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
		// What the turn cost. Collapsed to a single line until tapped: speed and
		// the cache share answer the questions people actually ask, and the
		// full breakdown is there for when something looks wrong.
		message.usage?.let { u ->
			UsageLine(usage = u, modifier = Modifier.padding(top = 4.dp))
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
