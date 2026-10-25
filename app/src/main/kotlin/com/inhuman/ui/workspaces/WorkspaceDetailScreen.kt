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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.ui.common.formatBytes
import com.inhuman.R
import com.inhuman.ui.common.formatBytes
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.ui.common.formatBytes
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.ui.common.formatBytes
import com.inhuman.core.design.Layout
import com.inhuman.ui.common.formatBytes
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.common.formatBytes
import com.inhuman.ui.common.WorkbenchBackButton
import com.inhuman.ui.common.formatBytes
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
	val fallbackTitle = stringResource(R.string.workspace_fallback_title)
	val copyLabel = stringResource(R.string.workspace_copy_path)
	val pathCopied = stringResource(R.string.workspace_path_copied)
	val noFilesApp = stringResource(R.string.workspace_no_files_app)
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
						text = workspace?.name ?: fallbackTitle,
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
							Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.workspace_menu))
						}
						DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.workspace_rename)) },
								onClick = {
									menuOpen = false
									renameInput = workspace?.name.orEmpty()
									showRename = true
								},
							)
							DropdownMenuItem(
								text = { Text(stringResource(R.string.workspace_delete)) },
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
					TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.common_dismiss)) }
				}
			}

			Row(
				modifier = Modifier.widthIn(max = Layout.ReadableMeasure).fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = pluralStringResource(
						R.plurals.workspace_usage,
						state.usage.files,
						state.usage.files,
						formatBytes(state.usage.bytes),
					),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.weight(1f),
				)
				OutlinedButton(onClick = onOpenFiles) { Text(stringResource(R.string.workspace_files_button)) }
			}

			Surface(
				modifier = Modifier.widthIn(max = Layout.ReadableMeasure).fillMaxWidth(),
				color = MaterialTheme.colorScheme.surfaceVariant,
				shape = RoundedCornerShape(10.dp),
			) {
				Column(
					modifier = Modifier.padding(12.dp),
					verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
				) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							text = stringResource(R.string.workspace_absolute_path),
							style = MaterialTheme.typography.labelLarge,
							modifier = Modifier.weight(1f),
						)
						IconButton(
							onClick = {
								val clipboard = context.getSystemService(ClipboardManager::class.java)
								clipboard?.setPrimaryClip(
									ClipData.newPlainText(copyLabel, state.absolutePath),
								)
								Toast.makeText(context, pathCopied, Toast.LENGTH_SHORT).show()
							},
						) {
							Icon(
								Icons.Outlined.ContentCopy,
								contentDescription = copyLabel,
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
						text = stringResource(R.string.workspace_path_note),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					TextButton(
						onClick = {
							if (!openWorkspaceInFiles(context)) {
								Toast.makeText(context, noFilesApp, Toast.LENGTH_SHORT).show()
							}
						},
					) { Text(stringResource(R.string.workspace_open_in_files)) }
				}
			}

			Row(
				modifier = Modifier.widthIn(max = Layout.ReadableMeasure).fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(stringResource(R.string.workspace_conversations), style = MaterialTheme.typography.titleSmall)
				TextButton(onClick = viewModel::startNewConversation) {
					Text(stringResource(R.string.workspace_new_conversation))
				}
			}

			if (state.conversations.isEmpty() && !state.loading) {
				Text(
					text = stringResource(R.string.workspace_no_conversations),
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
			title = { Text(stringResource(R.string.workspace_rename)) },
			text = {
				OutlinedTextField(
					value = renameInput,
					onValueChange = { renameInput = it },
					label = { Text(stringResource(R.string.common_name)) },
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
				) { Text(stringResource(R.string.common_save)) }
			},
			dismissButton = {
				TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.common_cancel)) }
			},
		)
	}

	state.choosingAgent?.let { available ->
		AlertDialog(
			onDismissRequest = viewModel::cancelChoosingAgent,
			title = { Text(stringResource(R.string.workspace_choose_assistant)) },
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
				TextButton(onClick = viewModel::cancelChoosingAgent) { Text(stringResource(R.string.common_cancel)) }
			},
		)
	}

	if (showDelete) {
		val hasConversations = state.conversations.isNotEmpty()
		AlertDialog(
			onDismissRequest = { showDelete = false },
			title = { Text(stringResource(R.string.workspace_delete_title)) },
			text = {
				Text(
					if (hasConversations) {
						pluralStringResource(
							R.plurals.workspace_delete_body_with_conversations,
							state.conversations.size,
							state.conversations.size,
						)
					} else {
						pluralStringResource(
							R.plurals.workspace_delete_body_files,
							state.usage.files,
							state.usage.files,
						)
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
				) { Text(stringResource(R.string.common_delete)) }
			},
			dismissButton = {
				TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.common_cancel)) }
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

