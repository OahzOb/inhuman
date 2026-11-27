package com.inhuman.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inhuman.R

/**
 * Where a prompt import should come from.
 *
 * The device picker already existed; the workspace browser is the new source.
 * Asking once keeps the prompt field's single "Import" action honest instead of
 * adding a second button to an already dense editor.
 */
@Composable
internal fun PromptImportChoiceDialog(
	onDeviceFile: () -> Unit,
	onWorkspaceFile: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.agents_import_title)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					text = stringResource(R.string.agents_import_choose_source),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				TextButton(onClick = onDeviceFile, modifier = Modifier.fillMaxWidth()) {
					Text(
						text = stringResource(R.string.agents_import_from_device),
						modifier = Modifier.fillMaxWidth(),
						textAlign = TextAlign.Start,
					)
				}
				TextButton(onClick = onWorkspaceFile, modifier = Modifier.fillMaxWidth()) {
					Text(
						text = stringResource(R.string.agents_import_from_workspace),
						modifier = Modifier.fillMaxWidth(),
						textAlign = TextAlign.Start,
					)
				}
			}
		},
		confirmButton = {},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
		},
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

/**
 * Browse one workspace for a text file to import as a system prompt.
 *
 * Two steps in one dialog: pick a workspace, then walk its folders. Every path
 * goes through [com.inhuman.core.files.SessionWorkspace], and the same read
 * guard and text-type rule as the device picker apply; the dialog is a UI for
 * the import, not a second way to read arbitrary files.
 */
@Composable
internal fun WorkspacePromptImportDialog(
	draft: PromptImportDraft?,
	onChooseWorkspace: (String) -> Unit,
	onEnterDirectory: (String) -> Unit,
	onUp: () -> Unit,
	onChangeWorkspace: () -> Unit,
	onPickFile: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.prompt_import_title)) },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 440.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				val current = draft
				when {
					current == null || (current.workspaces.isEmpty() && current.loading) -> {
						Text(
							text = stringResource(R.string.prompt_import_loading),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}

					current.selectedWorkspaceId == null -> {
						if (current.loading) {
							Text(
								text = stringResource(R.string.prompt_import_loading),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						current.error?.let { message ->
							Text(
								text = message,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.error,
							)
						}
						LazyColumn(modifier = Modifier.fillMaxWidth()) {
							items(current.workspaces, key = { it.id }) { workspace ->
								TextButton(
									onClick = { onChooseWorkspace(workspace.id) },
									modifier = Modifier.fillMaxWidth(),
								) {
									Text(
										text = if (workspace.permanent) {
											workspace.name
										} else {
											stringResource(
												R.string.prompt_import_temporary,
												workspace.name,
											)
										},
										modifier = Modifier.fillMaxWidth(),
										textAlign = TextAlign.Start,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
									)
								}
							}
						}
					}

					else -> {
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = current.selectedWorkspaceName,
									style = MaterialTheme.typography.titleSmall,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = if (current.directory.isEmpty()) {
										"/"
									} else {
										"/${current.directory}"
									},
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
							Spacer(Modifier.width(8.dp))
							TextButton(onClick = onChangeWorkspace) {
								Text(stringResource(R.string.prompt_import_change_workspace))
							}
						}
						if (current.directory.isNotEmpty()) {
							TextButton(onClick = onUp) {
								Text(stringResource(R.string.prompt_import_up))
							}
						}
						current.error?.let { message ->
							Text(
								text = message,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.error,
							)
						}
						if (current.loading) {
							Text(
								text = stringResource(R.string.prompt_import_loading),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						} else {
							LazyColumn(modifier = Modifier.fillMaxWidth()) {
								items(current.entries, key = { it.name }) { entry ->
									TextButton(
										onClick = {
											if (entry.isDirectory) {
												onEnterDirectory(entry.name)
											} else {
												onPickFile(entry.name)
											}
										},
										modifier = Modifier.fillMaxWidth(),
									) {
										Text(
											text = if (entry.isDirectory) {
												"\u25B8 ${entry.name}"
											} else {
												"    ${entry.name}"
											},
											modifier = Modifier.fillMaxWidth(),
											textAlign = TextAlign.Start,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
									}
								}
							}
							if (current.entries.isEmpty() && current.error == null) {
								Text(
									text = stringResource(R.string.prompt_import_empty),
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}
					}
				}
			}
		},
		confirmButton = {},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
		},
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurface,
	)
}
