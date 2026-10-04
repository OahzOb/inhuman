package com.inhuman.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.design.FormFactor
import com.inhuman.core.design.InhumanTheme
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter
import com.inhuman.core.design.currentFormFactor
import com.inhuman.core.design.prefersFabForPrimaryAction
import java.text.DateFormat
import java.util.Date

/**
 * The conversation list.
 *
 * The layout differs by form factor rather than scaling: a phone gets a
 * floating action button within thumb reach and a tighter gutter, a tablet gets
 * a top-bar action and a capped text measure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
	viewModel: ConversationsViewModel,
	onOpenConversation: (String) -> Unit,
	onOpenSettings: () -> Unit,
	onOpenAgents: () -> Unit,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }

	// Ask for the file count when the question is about to be shown, not when it
	// is answered -- by then the folder is gone and there is nothing to count.
	LaunchedEffect(pendingDelete?.id) {
		pendingDelete?.let { viewModel.countFiles(it) }
	}

	/**
	 * Drop the confirmation when the conversation it names is gone.
	 *
	 * `pendingDelete` holds a *copy* of a conversation, taken when the icon was
	 * tapped. That copy does not stop being a valid object when the row is
	 * deleted, so the dialog stayed on screen naming a conversation that no
	 * longer existed -- and if nothing else closed it, it stayed for the rest of
	 * the session.
	 *
	 * Keyed on the whole list so that any change re-evaluates it. The check is
	 * by id against the live rows, which is the only question that matters:
	 * "is the thing this dialog is about still here".
	 */
	LaunchedEffect(state.conversations) {
		val id = pendingDelete?.id ?: return@LaunchedEffect
		if (state.conversations.none { it.id == id }) pendingDelete = null
	}

	val formFactor = currentFormFactor()
	val useFab = prefersFabForPrimaryAction()

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("inhuman", style = MaterialTheme.typography.titleMedium) },
				actions = {
					// On a phone the FAB carries the primary action, so the top
					// bar keeps only the title and this.
					IconButton(onClick = onOpenAgents) {
						Icon(Icons.Outlined.Person, contentDescription = "Assistants")
					}
					IconButton(onClick = onOpenSettings) {
						Icon(Icons.Outlined.Settings, contentDescription = "Settings")
					}
					if (!useFab) {
						IconButton(onClick = { viewModel.startNewConversation() }) {
							Icon(Icons.Filled.Add, contentDescription = "New conversation")
						}
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
					actionIconContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
		floatingActionButton = {
			if (useFab) {
				FloatingActionButton(
					onClick = { viewModel.startNewConversation() },
					containerColor = MaterialTheme.colorScheme.primary,
					contentColor = MaterialTheme.colorScheme.onPrimary,
					shape = RoundedCornerShape(16.dp),
				) {
					Icon(Icons.Filled.Add, contentDescription = "New conversation")
				}
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				.windowInsetsPadding(WindowInsets.safeDrawing),
			contentAlignment = Alignment.Center,
		) {
			when {
				// Nothing is shown until the first read completes, so an empty
				// database does not flash "no conversations" over a list that
				// is about to appear.
				!state.loaded -> Unit

				state.conversations.isEmpty() -> EmptyState(
					formFactor = formFactor,
					onNewConversation = { viewModel.startNewConversation() },
				)

				else -> ConversationList(
					conversations = state.conversations,
					onOpen = onOpenConversation,
					onTogglePin = viewModel::setPinned,
					onRequestDelete = { pendingDelete = it },
				)
			}
		}
	}

	// Deleting a conversation destroys its folder too, so it asks first.
	//
	// The confirmation names what goes with it rather than only the title: the
	// files in the workspace are not visible from this screen, and "delete this
	// conversation?" reads like it removes a list entry.
	pendingDelete?.let { conversation ->
		val fileCount = state.deletePreviewCounts[conversation.id]

		// One meaning of "close", used by all three ways out.
		//
		// The dialog can be closed by Cancel, by tapping outside it, or by the
		// system Back button, and the first version handled them inconsistently:
		// Cancel cleared both pieces of state, while `onDismissRequest` cleared
		// only the cached file count. So tapping outside left the dialog open --
		// and visibly changed it, because the count it had just discarded was
		// the only thing making the body read "its folder is empty" instead of
		// the fallback "and its folder".
		//
		// That is the whole bug: a dismissal that dismissed part of the state.
		// Written once here so the next path added cannot get it half right.
		val closeDialog = {
			viewModel.cancelDelete()
			pendingDelete = null
		}

		AlertDialog(
			onDismissRequest = closeDialog,
			title = { Text("Delete \"${conversation.title}\"?") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						text = when {
							fileCount == null -> "This deletes the conversation and its folder."
							fileCount == 0 -> "This deletes the conversation. Its folder is empty."
							fileCount == 1 -> "This deletes the conversation and 1 file in its folder."
							else -> "This deletes the conversation and $fileCount files in its folder."
						},
					)
					Text(
						text = "This cannot be undone.",
						color = MaterialTheme.colorScheme.error,
					)
				}
			},
			confirmButton = {
				TextButton(onClick = { viewModel.delete(conversation) }) {
					Text("Delete", color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = closeDialog) { Text("Cancel") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	// Which assistant to start with.
	//
	// Asked before the conversation exists rather than after, because the
	// assistant's instructions shape every turn that follows, and retrofitting
	// them would mean the first exchange was answered under different rules than
	// the rest.
	state.choosingAgent?.let { available ->
		AlertDialog(
			onDismissRequest = viewModel::cancelChoosing,
			title = { Text("Start with which assistant?") },
			text = {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 400.dp)
						.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					available.forEach { agent ->
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.background(
									MaterialTheme.colorScheme.surfaceVariant,
									RoundedCornerShape(10.dp),
								)
								.clickable { viewModel.chooseAgent(agent, onOpenConversation) }
								.padding(12.dp),
							verticalArrangement = Arrangement.spacedBy(3.dp),
						) {
							Text(
								text = agent.name.ifBlank { "(unnamed)" },
								style = MaterialTheme.typography.bodyLarge,
								color = MaterialTheme.colorScheme.onSurface,
							)
							// Two lines of the instructions, so the choice is
							// informed rather than a guess between two names.
							Text(
								text = agent.systemPrompt,
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { viewModel.chooseAgent(null, onOpenConversation) }) {
					Text("No assistant")
				}
			},
			dismissButton = {
				TextButton(onClick = viewModel::cancelChoosing) { Text("Cancel") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun ConversationList(
	conversations: List<ConversationEntity>,
	onOpen: (String) -> Unit,
	onTogglePin: (ConversationEntity, Boolean) -> Unit,
	onRequestDelete: (ConversationEntity) -> Unit,
) {
	LazyColumn(
		modifier = Modifier
			.fillMaxSize()
			.widthIn(max = Layout.ReadableMeasure),
		contentPadding = PaddingValues(
			horizontal = comfortableGutter(),
			vertical = 8.dp,
		),
		verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
	) {
		items(conversations, key = { it.id }) { conversation ->
			ConversationRow(
				conversation = conversation,
				onClick = { onOpen(conversation.id) },
				onTogglePin = { onTogglePin(conversation, !conversation.pinned) },
				onDelete = { onRequestDelete(conversation) },
			)
		}
	}
}

@Composable
private fun ConversationRow(
	conversation: ConversationEntity,
	onClick: () -> Unit,
	onTogglePin: () -> Unit,
	onDelete: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
			.clickable(onClick = onClick)
			// 8dp rather than 12. Two lines of text give this row ~100dp of
			// content already, so the padding was a quarter of the row for no
			// separation it did not already have from the gap below it.
			.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = conversation.title,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = formatTimestamp(conversation.updatedAt),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		IconButton(onClick = onTogglePin) {
			Icon(
				imageVector = Icons.Outlined.PushPin,
				contentDescription = if (conversation.pinned) "Unpin" else "Pin",
				tint = if (conversation.pinned) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.outline
				},
				modifier = Modifier.size(18.dp),
			)
		}

		IconButton(onClick = onDelete) {
			Icon(
				Icons.Outlined.Delete,
				contentDescription = "Delete conversation",
				tint = MaterialTheme.colorScheme.error,
				modifier = Modifier.size(18.dp),
			)
		}
	}
}

/** Relative for anything recent, absolute once that stops being useful. */
private fun formatTimestamp(millis: Long): String {
	val age = System.currentTimeMillis() - millis
	return when {
		age < 60_000 -> "just now"
		age < 3_600_000 -> "${age / 60_000} min ago"
		age < 86_400_000 -> "${age / 3_600_000} h ago"
		else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
	}
}

@Composable
private fun EmptyState(
	formFactor: FormFactor,
	onNewConversation: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = comfortableGutter())
			// The measure cap is what makes the tablet layout readable rather
			// than merely wide. On a phone this is inert.
			.widthIn(max = Layout.ReadableMeasure),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Icon(
			imageVector = Icons.Outlined.FolderOpen,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.outline,
			modifier = Modifier.size(if (formFactor == FormFactor.Compact) 40.dp else 48.dp),
		)
		Text(
			text = "No conversations yet",
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onBackground,
			textAlign = TextAlign.Center,
		)
		Text(
			text = "Each conversation gets its own folder. " +
				"The assistant can read and write files inside it, and nowhere else. " +
				"That is enforced in code, not requested in a prompt.",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
		if (formFactor != FormFactor.Compact) {
			FilledTonalButton(
				onClick = onNewConversation,
				modifier = Modifier.padding(top = 8.dp),
				shape = RoundedCornerShape(10.dp),
				contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
			) {
				Text("Start a conversation")
			}
		}
	}
}

@Preview(name = "Empty", showBackground = true, backgroundColor = 0xFF0B0D10, widthDp = 411, heightDp = 900)
@Composable
private fun ConversationsEmptyPreview() {
	InhumanTheme(darkTheme = true) {
		// A preview cannot construct a real view model; the empty state is
		// previewed directly instead.
		Box(
			Modifier
				.fillMaxSize()
				.background(MaterialTheme.colorScheme.background),
			contentAlignment = Alignment.Center,
		) {
			EmptyState(FormFactor.Compact) {}
		}
	}
}
