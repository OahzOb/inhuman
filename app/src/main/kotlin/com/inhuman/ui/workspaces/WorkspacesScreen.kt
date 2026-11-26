package com.inhuman.ui.workspaces

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.R
import com.inhuman.core.design.Layout
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.comfortableGutter
import com.inhuman.core.design.prefersFabForPrimaryAction
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
	val defaultName = stringResource(R.string.workspaces_new)
	var showCreate by remember { mutableStateOf(false) }
	var newName by remember { mutableStateOf("") }
	var showBulkDelete by remember { mutableStateOf(false) }
	// A workspace zip carries its own name, but the document display name is the
	// only name the picker gives us; the fallback is a plain noun rather than an
	// error, because the import can still be named afterwards.
	val importContext = LocalContext.current
	var importError by remember { mutableStateOf<String?>(null) }
	val workspaceImporter = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			val resolver = importContext.contentResolver
			val rawName = runCatching {
				resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
					?.use { cursor ->
						if (cursor.moveToFirst()) cursor.getString(0) else null
					}
			}.getOrNull()?.takeIf { it.isNotBlank() }
			// The document picker's display name carries the archive extension.
			// A workspace called "notes.zip" would be a file name pretending to
			// be a name the user chose, so strip only the one extension the
			// import actually consumes.
			val displayName = rawName
				?.let { name ->
					if (name.endsWith(".zip", ignoreCase = true)) name.dropLast(4).trim() else name
				}
				?.takeIf { it.isNotBlank() }
				?: importContext.getString(R.string.workspaces_import_zip_default_name)
			viewModel.importWorkspaceZip(
				open = {
					resolver.openInputStream(uri)
						?: error(importContext.getString(R.string.workspaces_import_zip_unreadable))
				},
				displayName = displayName,
			) { id, error ->
				if (id != null) {
					onOpenWorkspace(id)
				} else {
					importError = error
						?: importContext.getString(R.string.workspaces_import_zip_unknown)
				}
			}
		}
	}

	BackHandler(enabled = state.selecting) { viewModel.cancelSelection() }

	LaunchedEffect(startCreating) {
		if (startCreating) {
			newName = defaultName
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
								pluralStringResource(
									R.plurals.workspaces_selected_count,
									state.selectedIds.size,
									state.selectedIds.size,
								)
							} else {
								stringResource(R.string.workspaces_title)
							},
							style = MaterialTheme.typography.titleLarge,
						)
					},
					navigationIcon = {
						if (state.selecting) {
							IconButton(onClick = viewModel::cancelSelection) {
								Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.workspaces_cancel_selection))
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
								Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.workspaces_delete_selected))
							}
						} else {
							// Same rule as the home list and the assistants screen: a FAB
							// only on a genuinely narrow window, a header action elsewhere.
							if (!prefersFabForPrimaryAction()) {
								TextButton(
									onClick = {
										newName = defaultName
										showCreate = true
									},
								) { Text(stringResource(R.string.workspaces_new)) }
							}
							if (state.workspaces.isNotEmpty()) {
								TextButton(onClick = { viewModel.beginSelection() }) { Text(stringResource(R.string.workspaces_select)) }
							}
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
			if (!state.selecting && prefersFabForPrimaryAction()) {
				FloatingActionButton(
					onClick = {
						newName = defaultName
						showCreate = true
					},
					containerColor = MaterialTheme.colorScheme.primary,
					contentColor = MaterialTheme.colorScheme.onPrimary,
					shape = MaterialTheme.shapes.medium,
				) {
					Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.workspaces_new))
				}
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner),
			// Capped columns centre on a tablet; without this they sat against
			// the left edge while the empty state above was centred.
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
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
					TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.common_dismiss)) }
				}
			}
			if (!state.selecting) {
				Row(
					modifier = Modifier
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxWidth()
						.padding(horizontal = comfortableGutter()),
					horizontalArrangement = Arrangement.End,
				) {
					TextButton(
						onClick = {
							workspaceImporter.launch(
								arrayOf("application/zip", "application/x-zip-compressed"),
							)
						},
					) {
						Text(stringResource(R.string.workspaces_import_zip))
					}
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
						Eyebrow(stringResource(R.string.workspaces_empty_eyebrow))
						Text(
							text = stringResource(R.string.workspaces_empty_title),
							style = MaterialTheme.typography.headlineMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = stringResource(R.string.workspaces_empty_body),
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
					verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
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
				Text(
					pluralStringResource(
						R.plurals.workspaces_delete_title,
						selectedRows.size,
						selectedRows.size,
					),
				)
			},
			text = {
				Text(
					if (conversations == 0) {
						stringResource(R.string.workspaces_delete_body_empty)
					} else {
						pluralStringResource(
							R.plurals.workspaces_delete_body_with_conversations,
							conversations,
							conversations,
						)
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
				) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
			},
			dismissButton = {
				TextButton(onClick = { showBulkDelete = false }) { Text(stringResource(R.string.common_cancel)) }
			},
		)
	}

	if (showCreate) {
		AlertDialog(
			onDismissRequest = { showCreate = false },
			title = { Text(stringResource(R.string.workspaces_new)) },
			text = {
				OutlinedTextField(
					value = newName,
					onValueChange = { newName = it },
					label = { Text(stringResource(R.string.common_name)) },
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
				) { Text(stringResource(R.string.workspaces_create)) }
			},
			dismissButton = {
				TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.common_cancel)) }
			},
		)
	}

	importError?.let { message ->
		AlertDialog(
			onDismissRequest = { importError = null },
			title = { Text(stringResource(R.string.workspaces_import_zip)) },
			text = { Text(stringResource(R.string.workspaces_import_zip_failed, message)) },
			confirmButton = {
				TextButton(onClick = { importError = null }) {
					Text(stringResource(R.string.common_ok))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

// Built once, not per row per composition.
private val workspaceDateFormat: DateFormat =
	DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

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
				text = pluralStringResource(
					R.plurals.workspaces_conversation_count,
					row.conversationCount,
					row.conversationCount,
				) + " · " + workspaceDateFormat.format(Date(row.workspace.updatedAt)),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
