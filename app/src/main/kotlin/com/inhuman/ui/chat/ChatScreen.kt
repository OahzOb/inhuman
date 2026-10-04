package com.inhuman.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import com.inhuman.core.data.Branches
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
	/** Raised while a dialog is up, so system Back closes it rather than leaving. */
	onDialogVisibilityChange: (Boolean) -> Unit = {},
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val listState = rememberLazyListState()
	var showModelPicker by remember { mutableStateOf(false) }

	// Named `ctx`, not `context`: the bare name collides with the Kotlin
	// stdlib's `context(...)` at every call site that has a lambda in scope, and
	// the compiler then reports "function invocation expected" rather than
	// anything about naming. The same trap caught the documents provider.
	val ctx = LocalContext.current

	// The image picker. ACTION_OPEN_DOCUMENT like the file browser's import,
	// and for the same reasons: a real filename and a grant that survives long
	// enough to copy the bytes through it.
	val importer = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			val name = queryImageName(ctx, uri)
			val mime = ctx.contentResolver.getType(uri) ?: "image/*"
			viewModel.attachImage(name, mime) {
				ctx.contentResolver.openInputStream(uri)
					?: error("could not open the selected image")
			}
		}
	}

	// Reported upward so the app's single Back handler knows a dialog is up.
	// Both this and the approval gate are transient questions that Back should
	// answer by dismissing, not by navigating away from.
	val anyDialogOpen = showModelPicker || state.pendingApproval != null
	LaunchedEffect(anyDialogOpen) { onDialogVisibilityChange(anyDialogOpen) }

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

	// Which message the regenerate control belongs to. Computed here rather than
	// compared per row, so the list does not scan itself once per message.
	val lastAssistantId = remember(state.messages) {
		state.messages.lastOrNull { !it.fromUser && !it.isToolResult }?.id
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
				verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
			) {
				items(state.messages, key = { it.id }) { message ->
					MessageBubble(
						message = message,
						// The newest assistant reply, which is the only one that
						// can be regenerated without discarding what follows it.
						isLastAssistantMessage = !message.fromUser && !message.isToolResult &&
							message.id == lastAssistantId,
						isStreaming = state.isStreaming,
						currentTurnMutated = state.currentTurnMutated,
						onRegenerate = viewModel::regenerate,
						// Both ids go up: the alternative being switched to is
						// not on the active path, so the view model cannot find
						// its parent by searching the transcript.
						onSelectBranch = { parentId, childId ->
							if (parentId != null) viewModel.selectBranch(parentId, childId)
						},
					)
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
				attachments = state.pendingAttachments,
				canAttachImages = state.modelSupportsVision,
				onAttach = { importer.launch(arrayOf("image/*")) },
				onRemoveAttachment = viewModel::removeAttachment,
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
				Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage)) {
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

/**
 * Step between the alternatives at one point in the conversation.
 *
 * ## Why arrows and a count rather than a list
 *
 * The alternatives are answers to the same question, so they are ordered only by
 * when they were produced -- there is nothing to choose between them by name.
 * A count with arrows says "this is one of three" and lets someone flip through,
 * which is what comparing two answers actually looks like.
 *
 * The arrows stop at the ends instead of wrapping. A control that cycles leaves
 * no way to tell whether the list has been around once, and with three answers
 * that matters: the reader is trying to be sure they have seen them all.
 */
@Composable
private fun BranchSwitcher(
	branches: Branches,
	onSelect: (String) -> Unit,
	enabled: Boolean,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(2.dp),
	) {
		IconButton(
			onClick = { branches.previousId?.let(onSelect) },
			enabled = enabled && branches.previousId != null,
			modifier = Modifier.size(28.dp),
		) {
			Text(
				text = "‹",
				style = MaterialTheme.typography.titleMedium,
				color = if (enabled && branches.previousId != null) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.outlineVariant
				},
			)
		}

		Text(
			text = branches.label,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		IconButton(
			onClick = { branches.nextId?.let(onSelect) },
			enabled = enabled && branches.nextId != null,
			modifier = Modifier.size(28.dp),
		) {
			Text(
				text = "›",
				style = MaterialTheme.typography.titleMedium,
				color = if (enabled && branches.nextId != null) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.outlineVariant
				},
			)
		}
	}
}

/**
 * One tool call, inside the collapsed section.
 *
 * Shows the name, the argument that identifies it, and what happened -- which is
 * what someone opening this section is trying to find out. The raw JSON is not
 * shown: `{"path":"notes.txt","content":"..."}` spends a screenful saying what
 * `write_file notes.txt` says in a line, and the second is what a person reads.
 */
@Composable
private fun ToolCallLine(call: ToolCallUi) {
	val status = when (call.state) {
		ToolCallUi.State.Streaming -> "…"
		ToolCallUi.State.Ready -> "ready"
		ToolCallUi.State.Running -> "running"
		ToolCallUi.State.Done -> "done"
		ToolCallUi.State.Declined -> "declined"
		ToolCallUi.State.Failed -> "failed"
	}
	val statusColor = when (call.state) {
		ToolCallUi.State.Failed -> MaterialTheme.colorScheme.error
		ToolCallUi.State.Declined -> MaterialTheme.colorScheme.outline
		else -> MaterialTheme.colorScheme.onSurfaceVariant
	}

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Top,
	) {
		Text(
			text = call.displayName,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.tertiary,
			fontFamily = FontFamily.Monospace,
		)
		Text(
			// The single most identifying argument, which for these tools is
			// almost always the path or the query.
			text = summariseArguments(call),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = status,
			style = MaterialTheme.typography.labelLarge,
			color = statusColor,
		)
	}
}

/**
 * The argument worth showing, or a fallback.
 *
 * Deliberately shallow: it reads the first of a few known keys rather than
 * parsing properly, because the value here is a label in a collapsed list and
 * a wrong-but-present argument name is better than a blank line. The real
 * arguments are in the transcript and in the tool result.
 */
private fun summariseArguments(call: ToolCallUi): String {
	val args = call.argumentsJson
	if (args.isBlank()) return ""
	for (key in listOf("path", "query", "pattern", "name")) {
		val match = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(args) ?: continue
		return match.groupValues[1].replace("\\\"", "\"").take(60)
	}
	return args.take(60)
}

/**
 * The one line that stands for a tool result.
 *
 * ## The duplicate this removes
 *
 * A search result opens with its own header — `latest Python version release — 5
 * results` — and the collapsed row was prefixing that with the tool name, so the
 * row read `web_search — latest Python version release — 5 results` while the
 * expanded body underneath began with the same words again. On a phone that
 * wrapped to two lines, which is most of what made a one-line index entry stand
 * 53dp tall.
 *
 * The count moves out of the text and into the row's trailing slot, where the
 * collapsed header already has a place for it -- so the same fact is stated once,
 * in the position a reader looks for it.
 *
 * ## Why the parsing is this shallow
 *
 * It handles the shapes the tools here actually produce. Anything else falls
 * through to the raw first line, which is never wrong, only less tidy -- and a
 * general parser for prose would be a larger thing to get wrong than the two
 * lines it would save.
 */
private fun toolResultSummary(
	toolName: String?,
	firstLine: String,
): Pair<String, String?> {
	if (firstLine.isBlank()) return (toolName ?: "Tool result") to null

	// "… — 5 results", "… — 3 files", "… — 1 item".
	val counted = Regex("^(.*?)\\s+—\\s+(\\d+)\\s+(results?|files?|items?|matches?)$")
		.find(firstLine)
	val body = counted?.groupValues?.get(1)?.trim() ?: firstLine
	val count = counted?.groupValues?.get(2)?.let { "$it ${counted.groupValues[3]}" }

	// The count is stated once, in the slot made for it, and the tool name is
	// dropped when there is one: "5 results" already says a search happened.
	val text = if (count != null) body else toolName?.let { "$it — $body" } ?: body
	return text to count
}

@Composable
private fun MessageBubble(
	message: ChatMessageUi,
	isLastAssistantMessage: Boolean,
	isStreaming: Boolean,
	currentTurnMutated: Boolean,
	onRegenerate: () -> Unit,
	onSelectBranch: (String?, String) -> Unit,
) {
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

	// A tool result is machinery, not speech.
	//
	// It is rendered as one collapsed line rather than as a bubble, because it
	// is not something anyone said -- it is what came back. An eight-search turn
	// produces pages of it, and left expanded the transcript is mostly search
	// results with the answer somewhere underneath.
	//
	// The row names the tool and summarises what came back, because those are
	// the two things that decide whether it is worth opening.
	if (message.isToolResult) {
		val firstLine = message.plainText.lineSequence()
			.firstOrNull { it.isNotBlank() }
			?.trim()
			.orEmpty()
		val (summaryText, summaryCount) = toolResultSummary(message.toolName, firstLine)
		CollapsibleSection(
			summary = summaryText,
			// The count goes in the header's right-hand slot rather than into the
			// sentence. It is the same fact either way, but a fixed-width slot
			// does not push the description along with it, so a column of these
			// lines up.
			trailing = summaryCount,
			modifier = Modifier
				.widthIn(max = Layout.ReadableMeasure)
				// No vertical padding of its own. The collapsed header already
				// carries its own inset, and the 2dp that used to be here was
				// part of a measured 47px gap between two one-line rows.
				.padding(vertical = 0.dp),
		) {
			Text(
				text = message.plainText.ifBlank { "(empty)" },
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				fontFamily = FontFamily.Monospace,
			)
		}
		return
	}

	Column(
		modifier = Modifier.fillMaxWidth(),
		horizontalAlignment = alignment,
	) {
		Box(
			modifier = Modifier
				.widthIn(max = Layout.ReadableMeasure)
				.background(bubbleColor, RoundedCornerShape(14.dp))
				.padding(horizontal = 12.dp, vertical = 8.dp),
		) {
			Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage)) {
				// Reasoning first, collapsed, and never in the way of the answer.
				//
				// It is shown when a model has thought and hidden otherwise, so
				// a reply that used no thinking has no empty header above it. The
				// character count is in the header because "Thought for a moment"
				// and "Thought for four thousand characters" are different
				// propositions and the second is worth opening.
				val reasoning = message.parts
					.filter { it.kind == AssistantPart.Kind.Reasoning }
					.joinToString("\n\n") { it.text }
				if (reasoning.isNotBlank() && !message.fromUser) {
					CollapsibleSection(
						summary = "Thinking",
						trailing = "${reasoning.length} chars",
					) {
						ReasoningBody(reasoning)
					}
				}

				// Tool calls, collapsed together.
				//
				// One section rather than one per call: a turn that searched
				// eight times would otherwise produce eight headers, and the
				// question a reader has is "what did it do", not "what did the
				// fourth call do".
				val calls = message.toolCalls
				if (calls.isNotEmpty()) {
					CollapsibleSection(
						summary = if (calls.size == 1) "Used one tool" else "Used ${calls.size} tools",
						trailing = calls.count { it.state == ToolCallUi.State.Failed }
							.takeIf { it > 0 }?.let { "$it failed" },
					) {
						Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed)) {
							calls.forEach { ToolCallLine(it) }
						}
					}
				}

				// The answer. Everything above is working-out; this is the part
				// that was asked for, and it is the only part that is always
				// visible.
				message.parts
					.filter { it.kind == AssistantPart.Kind.Text }
					.forEach { part ->
						if (message.fromUser) {
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
					}

				// Attachment names, so a message that was mostly a picture does
				// not look like it had no content.
				if (message.attachments.isNotEmpty()) {
					message.attachments.forEach { attachment ->
						Text(
							text = "📎 ${attachment.displayName}",
							style = MaterialTheme.typography.labelLarge,
							color = onColor,
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

		// Alternatives, when this reply has any.
		//
		// Placed under the answer rather than beside it, because it belongs to
		// the whole message: it says "this is one of three answers to the
		// question above", which is a fact about the message and not about any
		// part of its text.
		message.branches?.let { branches ->
			BranchSwitcher(
				branches = branches,
				onSelect = { childId -> onSelectBranch(message.branchParentId, childId) },
				enabled = !message.isStreaming,
				modifier = Modifier.padding(top = 6.dp),
			)
		}

		// Regenerate, on the newest reply only.
		//
		// Offered here rather than on every reply: regenerating mid-conversation
		// would discard everything after it, and a control that silently deletes
		// the rest of a transcript is not one to put within reach of a mis-tap.
		if (isLastAssistantMessage && !message.isStreaming && !isStreaming) {
			if (currentTurnMutated) {
				// Not offered at all, with the reason given.
				//
				// Regenerating re-runs the turn, tools included. For a reply that
				// wrote or deleted files that means doing it a second time, and no
				// amount of confirmation makes that the same request -- the second
				// write lands on a workspace the first one changed.
				//
				// A disabled button would invite pressing it; a line of text
				// explains why the option is gone, and the file it changed is in
				// the workspace for anyone who wants to look.
				Text(
					text = "This reply changed files, so it cannot be regenerated.",
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.outline,
					modifier = Modifier.padding(top = 4.dp),
				)
			} else {
				IconButton(
					onClick = onRegenerate,
					modifier = Modifier
						.padding(top = 2.dp)
						.size(32.dp),
				) {
					Icon(
						// Refresh rather than a rerun arrow: the action produces
						// another answer, not a replay of this one, and the
						// existing answers stay reachable through the branch
						// switcher beside it.
						imageVector = Icons.Outlined.Refresh,
						contentDescription = "Answer again",
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(18.dp),
					)
				}
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
	attachments: List<PendingAttachment>,
	canAttachImages: Boolean,
	onAttach: () -> Unit,
	onRemoveAttachment: (PendingAttachment) -> Unit,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		// Staged images sit above the field, so they read as part of the message
		// being written rather than as a toolbar beside it.
		if (attachments.isNotEmpty()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = comfortableGutter(), vertical = 4.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				attachments.forEach { attachment ->
					Row(
						modifier = Modifier
							.background(
								MaterialTheme.colorScheme.surfaceVariant,
								RoundedCornerShape(8.dp),
							)
							.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = "${attachment.displayName}  ${humanSize(attachment.sizeBytes)}",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.widthIn(max = 200.dp),
						)
						IconButton(
							onClick = { onRemoveAttachment(attachment) },
							modifier = Modifier.size(28.dp),
						) {
							Text(
								text = "✕",
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.error,
							)
						}
					}
				}
			}
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = comfortableGutter(), vertical = 8.dp),
			verticalAlignment = Alignment.Bottom,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			// Present but disabled when the model cannot see, rather than hidden.
			//
			// A control that vanishes leaves someone wondering where it went; one
			// that is there and greyed out can be tapped and answered. That answer
			// matters here because sending an image to a text-only model does not
			// fail -- it returns 200 and the model describes a placeholder -- so
			// there is no other place the reason could surface.
			IconButton(
				onClick = onAttach,
				enabled = enabled && canAttachImages,
				modifier = Modifier.padding(bottom = 4.dp),
			) {
				Icon(
					Icons.Outlined.Image,
					contentDescription = if (canAttachImages) {
						"Attach an image"
					} else {
						"This model cannot read images"
					},
					tint = if (enabled && canAttachImages) {
						MaterialTheme.colorScheme.onSurfaceVariant
					} else {
						MaterialTheme.colorScheme.outlineVariant
					},
				)
			}

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
				// An image alone is sendable: "what is this?" is implied, and
				// requiring text would make attaching one a two-step job.
				enabled = enabled && (value.isNotBlank() || attachments.isNotEmpty()),
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
}

/** Sizes as a person reads them, not as a byte count. */
private fun humanSize(bytes: Long): String = when {
	bytes < 1024 -> "$bytes B"
	bytes < 1024 * 1024 -> "${bytes / 1024} KB"
	else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * The picked file's display name.
 *
 * Read while the grant is definitely alive rather than inside the async attach,
 * where a revoked permission would race it -- and with a fallback, because a
 * provider that does not expose DISPLAY_NAME is common enough that failing on it
 * would make attachments look broken for one file source.
 */
private fun queryImageName(context: android.content.Context, uri: android.net.Uri): String =
	runCatching {
		context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
			val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
			if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
		}
	}.getOrNull()?.takeIf { it.isNotBlank() } ?: "image"
