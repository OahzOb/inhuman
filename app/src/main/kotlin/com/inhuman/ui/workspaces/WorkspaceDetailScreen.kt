package com.inhuman.ui.workspaces

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.common.WorkbenchBackButton
import com.inhuman.ui.common.openWorkspaceInFiles
import java.text.DateFormat
import java.util.Date

/**
 * One permanent workspace.
 *
 * The absolute path is user-facing only. It is deliberately not added to any
 * tool, prompt, or diagnostics line: the agent continues to see relative paths
 * through [com.inhuman.core.files.SessionWorkspace].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceDetailScreen(
	viewModel: WorkspaceDetailViewModel,
	onBack: () -> Unit,
	onOpenConversation: (String) -> Unit,
	onOpenFiles: () -> Unit,
	onDeleted: () -> Unit,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	var menuOpen by remember { mutableStateOf(false) }
	var showRename by remember { mutableStateOf(false) }
	var renameInput by remember { mutableStateOf("") }
	var showDelete by remember { mutableStateOf(false) }

	val workspace = state.workspace

	// Files edited in the browser are not a database change, so the flows above
	// cannot see them. Refresh the usage when this screen is resumed.
	LifecycleResumeEffect(Unit) {
		viewModel.refreshUsage()
		onPauseOrDispose { }
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Text(
						text = workspace?.name ?: "Workspace",
						style = MaterialTheme.typography.titleLarge,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				},
				navigationIcon = {
					WorkbenchBackButton(onClick = onBack)
				},
				actions = {
					Box {
						IconButton(onClick = { menuOpen = true }) {
							Icon(Icons.Filled.MoreVert, contentDescription = "Workspace menu")
						}
						DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
							DropdownMenuItem(
								text = { Text("Rename workspace") },
								onClick = {
									menuOpen = false
									renameInput = workspace?.name.orEmpty()
									showRename = true
								},
							)
							DropdownMenuItem(
								text = { Text("Delete workspace") },
								onClick = {
									menuOpen = false
									showDelete = true
								},
							)
						}
					}
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
				.verticalScroll(rememberScrollState())
				.padding(horizontal = comfortableGutter()),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			state.error?.let { message ->
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = message,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.weight(1f),
					)
					TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
				}
			}

			Row(
				modifier = Modifier.widthIn(max = Layout.ReadableMeasure).fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = "${state.usage.files} files · ${humanBytes(state.usage.bytes)}",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.weight(1f),
				)
				OutlinedButton(onClick = onOpenFiles) { Text("Files") }
			}

			Surface(
				modifier = Modifier.widthIn(max = Layout.ReadableMeasure).fillMaxWidth(),
				color = MaterialTheme.colorScheme.surfaceVariant,
				shape = RoundedCornerShape(10.dp),
			) {
				Column(
					modifier = Modifier.padding(12.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							text = "Absolute path",
							style = MaterialTheme.typography.labelLarge,
							modifier = Modifier.weight(1f),
						)
						IconButton(
							onClick = {
								val clipboard = context.getSystemService(ClipboardManager::class.java)
								clipboard?.setPrimaryClip(
									ClipData.newPlainText("Workspace path", state.absolutePath),
								)
								Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
							},
						) {
							Icon(
								Icons.Outlined.ContentCopy,
								contentDescription = "Copy workspace path",
							)
						}
					}
					SelectionContainer {
						Text(
							text = state.absolutePath,
							style = MaterialTheme.typography.bodySmall,
							fontFamily = FontFamily.Monospace,
						)
					}
					Text(
						text = "App-private. The assistant only sees relative paths and never this location.",
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					TextButton(
						onClick = {
							if (!openWorkspaceInFiles(context)) {
								Toast.makeText(context, "No Files app available", Toast.LENGTH_SHORT).show()
							}
						},
					) { Text("Open in Files app") }
				}
			}

			Row(
				modifier = Modifier.widthIn(max = Layout.ReadableMeasure).fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text("Conversations", style = MaterialTheme.typography.titleSmall)
				TextButton(onClick = viewModel::startNewConversation) {
					Text("New conversation")
				}
			}

			if (state.conversations.isEmpty() && !state.loading) {
				Text(
					text = "No conversations use this workspace yet.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(vertical = 8.dp),
				)
			} else {
				Column(
					modifier = Modifier.widthIn(max = Layout.ReadableMeasure).fillMaxWidth(),
				) {
					state.conversations.forEachIndexed { index, conversation ->
						if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
						ConversationRow(
							conversation = conversation,
							onClick = { onOpenConversation(conversation.id) },
						)
					}
				}
			}
		}
	}

	if (showRename) {
		AlertDialog(
			onDismissRequest = { showRename = false },
			title = { Text("Rename workspace") },
			text = {
				OutlinedTextField(
					value = renameInput,
					onValueChange = { renameInput = it },
					label = { Text("Name") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
			},
			confirmButton = {
				TextButton(
					enabled = renameInput.isNotBlank(),
					onClick = {
						showRename = false
						viewModel.rename(renameInput)
					},
				) { Text("Save") }
			},
			dismissButton = {
				TextButton(onClick = { showRename = false }) { Text("Cancel") }
			},
		)
	}

	state.choosingAgent?.let { available ->
		AlertDialog(
			onDismissRequest = viewModel::cancelChoosingAgent,
			title = { Text("Choose an assistant") },
			text = {
				Column(
					modifier = Modifier
						.heightIn(max = 360.dp)
						.verticalScroll(rememberScrollState()),
				) {
					available.forEach { agent ->
						AgentChoiceRow(
							agent = agent,
							onClick = {
								viewModel.chooseAgent(agent, onOpenConversation)
							},
						)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = viewModel::cancelChoosingAgent) { Text("Cancel") }
			},
		)
	}

	if (showDelete) {
		val hasConversations = state.conversations.isNotEmpty()
		AlertDialog(
			onDismissRequest = { showDelete = false },
			title = { Text("Delete workspace?") },
			text = {
				Text(
					if (hasConversations) {
						"This workspace still has ${state.conversations.size} " +
							"conversation${if (state.conversations.size == 1) "" else "s"}. " +
							"Delete them first; deleting a workspace never deletes its files " +
							"while conversations remain."
					} else {
						"This deletes the workspace and its ${state.usage.files} files. " +
							"This cannot be undone."
					},
				)
			},
			confirmButton = {
				TextButton(
					enabled = !hasConversations && !state.deleting,
					onClick = {
						showDelete = false
						viewModel.delete(onDeleted)
					},
				) { Text("Delete") }
			},
			dismissButton = {
				TextButton(onClick = { showDelete = false }) { Text("Cancel") }
			},
		)
	}
}

@Composable
private fun ConversationRow(conversation: ConversationEntity, onClick: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 10.dp, horizontal = 4.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		Text(
			text = conversation.title,
			style = MaterialTheme.typography.bodyLarge,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
				.format(Date(conversation.updatedAt)),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun AgentChoiceRow(agent: AgentEntity, onClick: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 10.dp, horizontal = 4.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		Text(agent.name, style = MaterialTheme.typography.bodyLarge)
		Text(
			agent.systemPrompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

private fun humanBytes(bytes: Long): String = when {
	bytes < 1024 -> "$bytes B"
	bytes < 1024 * 1024 -> "${bytes / 1024} KB"
	else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
