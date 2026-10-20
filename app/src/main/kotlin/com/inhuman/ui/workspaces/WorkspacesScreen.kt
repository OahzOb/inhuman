package com.inhuman.ui.workspaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
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
import com.inhuman.core.design.comfortableGutter
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

	LaunchedEffect(startCreating) {
		if (startCreating) {
			newName = "New workspace"
			showCreate = true
			onCreateStarted()
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Workspaces", style = MaterialTheme.typography.titleMedium) },
				navigationIcon = {
					IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.titleLarge) }
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
					navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
		floatingActionButton = {
			FloatingActionButton(
				onClick = {
					newName = "New workspace"
					showCreate = true
				},
			) {
				Icon(Icons.Filled.Add, contentDescription = "New workspace")
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				.windowInsetsPadding(WindowInsets.safeDrawing),
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
					contentAlignment = Alignment.Center,
				) {
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.spacedBy(6.dp),
					) {
						Text("No workspaces yet", style = MaterialTheme.typography.titleMedium)
						Text(
							"Create one to keep files across conversations.",
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
						WorkspaceListRow(row = row, onClick = { onOpenWorkspace(row.workspace.id) })
					}
				}
			}
		}
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
private fun WorkspaceListRow(row: WorkspaceRowUi, onClick: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 12.dp, horizontal = 4.dp),
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
