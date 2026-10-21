package com.inhuman.ui.workspaces

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.design.Layout
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.common.WorkbenchBackButton
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(
	viewModel: WorkspacesViewModel,
	startCreating: Boolean = false,
	onCreateStarted: () -> Unit = {},
	onBack: () -> Unit,
	onOpenWorkspace: (String) -> Unit,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	var showCreate by remember { mutableStateOf(false) }
	var newName by remember { mutableStateOf("") }
	var showBulkDelete by remember { mutableStateOf(false) }

	BackHandler(enabled = state.selecting) { viewModel.cancelSelection() }

	LaunchedEffect(startCreating) {
		if (startCreating) {
			newName = "New workspace"
			showCreate = true
			onCreateStarted()
		}
	}

	Scaffold(
		topBar = {
			Column {
				TopAppBar(
					title = {
						Text(
							if (state.selecting) {
								"${state.selectedIds.size} selected"
							} else {
								"Workspaces"
							},
							style = MaterialTheme.typography.titleLarge,
						)
					},
					navigationIcon = {
						if (state.selecting) {
							IconButton(onClick = viewModel::cancelSelection) {
								Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
							}
						} else {
							WorkbenchBackButton(onClick = onBack)
						}
					},
					actions = {
						if (state.selecting) {
							IconButton(
								onClick = { showBulkDelete = true },
								enabled = state.selectedIds.isNotEmpty() && !state.deletingSelection,
							) {
								Icon(Icons.Outlined.Delete, contentDescription = "Delete selected workspaces")
							}
						} else if (state.workspaces.isNotEmpty()) {
							TextButton(onClick = { viewModel.beginSelection() }) { Text("Select") }
						}
					},
					colors = TopAppBarDefaults.topAppBarColors(
						containerColor = MaterialTheme.colorScheme.background,
						titleContentColor = MaterialTheme.colorScheme.onBackground,
						navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
						actionIconContentColor = MaterialTheme.colorScheme.onBackground,
					),
				)
				Hairline()
			}
		},
		floatingActionButton = {
			if (!state.selecting) {
				FloatingActionButton(
					onClick = {
						newName = "New workspace"
						showCreate = true
					},
					containerColor = MaterialTheme.colorScheme.primary,
					contentColor = MaterialTheme.colorScheme.onPrimary,
					shape = MaterialTheme.shapes.medium,
				) {
					Icon(Icons.Filled.Add, contentDescription = "New workspace")
				}
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			state.error?.let { message ->
				Row(
					modifier = Modifier
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxWidth()
						.padding(horizontal = comfortableGutter()),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = message,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.weight(1f),
					)
					TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
				}
			}
			if (!state.loading && state.workspaces.isEmpty()) {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.TopCenter,
				) {
					Column(
						modifier = Modifier
							.widthIn(max = 640.dp)
							.fillMaxWidth()
							.padding(horizontal = comfortableGutter(), vertical = 32.dp),
						verticalArrangement = Arrangement.spacedBy(12.dp),
					) {
						Eyebrow("Workspaces / shared")
						Text(
							text = "Shared folders,\nnot shared chats.",
							style = MaterialTheme.typography.headlineMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = "A permanent workspace keeps its files across conversations. " +
								"Use one when several threads need the same documents.",
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			} else {
				LazyColumn(
					modifier = Modifier
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxWidth()
						.padding(horizontal = comfortableGutter()),
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					items(state.workspaces, key = { it.workspace.id }) { row ->
						WorkspaceListRow(
							row = row,
							selecting = state.selecting,
							selected = row.workspace.id in state.selectedIds,
							onClick = {
								if (state.selecting) {
									viewModel.toggleSelection(row.workspace.id)
								} else {
									onOpenWorkspace(row.workspace.id)
								}
							},
							onLongClick = {
								if (state.selecting) {
									viewModel.toggleSelection(row.workspace.id)
								} else {
									viewModel.beginSelection(row.workspace.id)
								}
							},
						)
						Hairline()
					}
				}
			}
		}
	}

	if (showBulkDelete) {
		val selectedRows = state.workspaces.filter { it.workspace.id in state.selectedIds }
		val conversations = selectedRows.sumOf { it.conversationCount }
		AlertDialog(
			onDismissRequest = { showBulkDelete = false },
			title = {
				Text("Delete ${selectedRows.size} workspace${if (selectedRows.size == 1) "" else "s"}?")
			},
			text = {
				Text(
					if (conversations == 0) {
						"This deletes the selected workspaces and all their files. " +
							"This cannot be undone."
					} else {
						"This also deletes $conversations conversation" +
							(if (conversations == 1) "" else "s") +
							(if (conversations == 1) " inside it" else " inside them") +
							(if (conversations == 1) ", its rollback snapshots" else ", their rollback snapshots") +
							", and all files in these workspaces. This cannot be undone."
					},
				)
			},
			confirmButton = {
				TextButton(
					enabled = !state.deletingSelection,
					onClick = {
						showBulkDelete = false
						viewModel.deleteSelected()
					},
				) { Text("Delete", color = MaterialTheme.colorScheme.error) }
			},
			dismissButton = {
				TextButton(onClick = { showBulkDelete = false }) { Text("Cancel") }
			},
		)
	}

	if (showCreate) {
		AlertDialog(
			onDismissRequest = { showCreate = false },
			title = { Text("New workspace") },
			text = {
				OutlinedTextField(
					value = newName,
					onValueChange = { newName = it },
					label = { Text("Name") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
			},
			confirmButton = {
				TextButton(
					enabled = newName.isNotBlank(),
					onClick = {
						showCreate = false
						viewModel.create(newName) { id -> onOpenWorkspace(id) }
					},
				) { Text("Create") }
			},
			dismissButton = {
				TextButton(onClick = { showCreate = false }) { Text("Cancel") }
			},
		)
	}
}

@Composable
private fun WorkspaceListRow(
	row: WorkspaceRowUi,
	selecting: Boolean,
	selected: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
				else androidx.compose.ui.graphics.Color.Transparent,
			)
			.combinedClickable(onClick = onClick, onLongClick = onLongClick)
			.padding(vertical = 12.dp, horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (selecting) {
			Checkbox(
				checked = selected,
				onCheckedChange = { onClick() },
				modifier = Modifier.size(42.dp),
			)
		}
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = row.workspace.name,
				style = MaterialTheme.typography.titleSmall,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = buildString {
					append(row.conversationCount)
					append(if (row.conversationCount == 1) " conversation" else " conversations")
					append(" · ")
					append(
						DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
							.format(Date(row.workspace.updatedAt)),
					)
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
