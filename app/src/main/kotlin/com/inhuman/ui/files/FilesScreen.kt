package com.inhuman.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.ui.common.FlowRowCompat
import com.inhuman.ui.common.formatBytes
import com.inhuman.R
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.Layout
import com.inhuman.core.design.WideDialog
import com.inhuman.core.design.WorkbenchGrid
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.chat.MarkdownText
import com.inhuman.ui.common.WorkbenchBackButton
import com.inhuman.ui.common.openWorkspaceInFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Whether a preview should be parsed as Markdown rather than shown literally.
 *
 * Extension, not MIME: the system's mapping for `.md` is inconsistent across
 * providers (`text/markdown`, `text/x-markdown`, or nothing at all), while the
 * name is what the user sees in the list and what the assistant chose. The two
 * common extensions are enough; a `.md` inside a `.txt` name stays text.
 */
internal fun isMarkdownName(name: String): Boolean =
	name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

/** The sort option's own word, in the current language. */
@Composable
private fun sortLabel(sort: FileSort): String = stringResource(
	when (sort) {
		FileSort.Name -> R.string.files_sort_name
		FileSort.Modified -> R.string.files_sort_modified
		FileSort.Size -> R.string.files_sort_size
	},
)

/** The preview decode, as three states so a failure does not spin forever. */
private sealed interface ImagePreviewState {
	data object Loading : ImagePreviewState
	data object Failed : ImagePreviewState
	data class Ready(val image: ImageBitmap) : ImagePreviewState
}

/**
 * The conversation's workspace.
 *
 * Files can be browsed, previewed if they are text, imported from anywhere the
 * system can offer, exported to anywhere the system can write, and deleted. The
 * same files are reachable through the system Files app; this is the view from
 * inside the conversation, where the question is "what did it make?" rather
 * than "where did I put it?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
	viewModel: FilesViewModel,
	onBack: () -> Unit,
	/** Raised while a dialog is up, so system Back closes it rather than leaving. */
	onDialogVisibilityChange: (Boolean) -> Unit = {},
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val noFilesApp = stringResource(R.string.workspace_no_files_app)
	val pathCopied = stringResource(R.string.workspace_path_copied)
	val clipLabel = stringResource(R.string.files_clip_label)
	val openWith = stringResource(R.string.files_open_with_chooser)
	val noApp = stringResource(R.string.files_no_app)
	val notReady = stringResource(R.string.files_not_ready)

	// The view model is activity-scoped and keyed by conversation, so it
	// survives leaving this screen. A file written while the screen was gone is
	// not in its cached list; refresh on every resume, not once in `init`.
	// Without this, "the assistant saved it" followed by an empty Files screen
	// is exactly what a user sees.
	LifecycleResumeEffect(Unit) {
		viewModel.refresh()
		onPauseOrDispose { }
	}

	var pendingDelete by remember { mutableStateOf<FileEntryUi?>(null) }
	var importError by remember { mutableStateOf<String?>(null) }
	var showDownloadDialog by remember { mutableStateOf(false) }
	var showPath by remember { mutableStateOf(false) }
	var showWorkspaceImport by remember { mutableStateOf(false) }
	var searchOpen by remember { mutableStateOf(false) }
	var query by rememberSaveable { mutableStateOf("") }
	var sortName by rememberSaveable { mutableStateOf(FileSort.Name.name) }
	var sortMenu by remember { mutableStateOf(false) }
	var actionsMenu by remember { mutableStateOf(false) }
	val sort = FileSort.entries.firstOrNull { it.name == sortName } ?: FileSort.Name
	val visibleEntries = remember(state.entries, query, sort) {
		orderedFiles(state.entries, query, sort)
	}
	// A search belongs to the folder it was typed in. Keeping it across a
	// navigation would show "no matches" in a folder the user just entered.
	LaunchedEffect(state.currentPath) {
		query = ""
		searchOpen = false
	}
	// Back closes the search before it leaves Files. Registered here rather
	// than in the root handler because this screen owns the field.
	BackHandler(enabled = searchOpen) {
		searchOpen = false
		query = ""
	}

	// While the editor is open its own inline error is the right place for a
	// save failure; another AlertDialog over the editor would hide the buffer
	// the user is trying to fix.
	val dialogError = if (state.editor == null) state.error ?: importError else null

	val anyDialogOpen = pendingDelete != null || importError != null ||
		state.error != null || state.preview != null || state.editor != null ||
		showDownloadDialog || showPath || showWorkspaceImport
	LaunchedEffect(anyDialogOpen) { onDialogVisibilityChange(anyDialogOpen) }

	// Which file the export launcher was fired for.
	//
	// The launcher's result callback runs on a later composition and takes no
	// arguments, so the path cannot be passed through it. Held here rather than
	// in the view model because it is a property of this screen's in-flight
	// intent, not of the workspace.
	var exportTarget by remember { mutableStateOf<String?>(null) }

	// Import. ACTION_OPEN_DOCUMENT rather than GET_CONTENT: the former gives a
	// durable, persistable grant and a real filename, and this copies the stream
	// immediately anyway, so durability costs nothing and the name matters.
	val importer = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			// Read the display name while the grant is definitely alive; doing
			// it inside the async import can race a revoked permission.
			val name = queryDisplayName(context, uri) ?: "imported"
			viewModel.importFile(name) {
				context.contentResolver.openInputStream(uri)
					?: error("could not open the selected file")
			}
		}
	}

	// Export through ACTION_CREATE_DOCUMENT: the user chooses where it goes, no
	// permission is involved, and the result is a file they own rather than a
	// copy inside the app.
	val exporter = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("application/octet-stream"),
	) { uri ->
		// The contract's result arrives on a later composition, so the path to
		// export is read from a holder set when the launcher was fired.
		val path = exportTarget
		if (uri != null && path != null) {
			scope.launch {
				val error = viewModel.exportFile(path) {
					context.contentResolver.openOutputStream(uri)
						?: error("could not open the destination")
				}
				if (error != null) importError = error
			}
		}
		exportTarget = null
	}

	Scaffold(
		topBar = {
			Column {
				TopAppBar(
					title = {
						Column {
							Text(
								text = if (state.currentPath.isEmpty()) {
									stringResource(R.string.files_title)
								} else {
									state.currentPath.substringAfterLast('/')
								},
								style = MaterialTheme.typography.titleLarge,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
							Text(
								// The quota, shown because a user who hits it deserves
								// to know it is a limit rather than a bug.
								text = stringResource(
									R.string.files_quota,
									state.fileCount,
									state.maxFiles,
									formatBytes(state.usedBytes),
									formatBytes(state.totalBytes),
								),
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					},
					navigationIcon = {
						WorkbenchBackButton(
							onClick = {
								if (state.currentPath.isEmpty()) onBack() else viewModel.navigateUp()
							},
						)
					},
					actions = {
						IconButton(
							onClick = {
								if (searchOpen) {
									searchOpen = false
									query = ""
								} else {
									searchOpen = true
								}
							},
						) {
							Icon(
								if (searchOpen) Icons.Filled.Close else Icons.Outlined.Search,
								contentDescription = stringResource(
									if (searchOpen) R.string.files_search_close else R.string.files_search,
								),
							)
						}
						IconButton(onClick = { importer.launch(arrayOf("*/*")) }) {
							Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.files_import))
						}
						// The other three actions and the sort order live here.
						// Four separate icons on a phone left the quota line
						// wrapping to three lines, and the row action bar had
						// the same problem.
						Box {
							IconButton(onClick = { actionsMenu = true }) {
								Icon(
									Icons.Filled.MoreVert,
									contentDescription = stringResource(R.string.files_more_actions),
								)
							}
							DropdownMenu(
								expanded = actionsMenu,
								onDismissRequest = { actionsMenu = false },
							) {
								Text(
									text = stringResource(R.string.files_sort).uppercase(),
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
								)
								FileSort.entries.forEach { option ->
									DropdownMenuItem(
										text = { Text(sortLabel(option)) },
										onClick = {
											sortName = option.name
											actionsMenu = false
										},
										trailingIcon = {
											if (sort == option) {
												Icon(Icons.Filled.Check, contentDescription = null)
											}
										},
									)
								}
								HorizontalDivider()
								DropdownMenuItem(
									text = { Text(stringResource(R.string.files_workspace_location)) },
									leadingIcon = {
										Icon(Icons.Outlined.Folder, contentDescription = null)
									},
									onClick = {
										actionsMenu = false
										showPath = true
									},
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.download_title)) },
									leadingIcon = {
										Icon(Icons.Filled.Download, contentDescription = null)
									},
									onClick = {
										actionsMenu = false
										showDownloadDialog = true
									},
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.files_import_workspace)) },
									leadingIcon = {
										Icon(Icons.Outlined.ContentCopy, contentDescription = null)
									},
									onClick = {
										actionsMenu = false
										showWorkspaceImport = true
									},
								)
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
				if (searchOpen) {
					Box(
						modifier = Modifier.fillMaxWidth(),
						contentAlignment = Alignment.TopCenter,
					) {
						OutlinedTextField(
							value = query,
							onValueChange = { query = it },
							modifier = Modifier
								.widthIn(max = Layout.ReadableMeasure)
								.fillMaxWidth()
								.padding(horizontal = comfortableGutter(), vertical = 6.dp),
							singleLine = true,
							shape = MaterialTheme.shapes.small,
							placeholder = { Text(stringResource(R.string.files_search_placeholder)) },
							leadingIcon = {
								Icon(
									Icons.Outlined.Search,
									contentDescription = null,
									modifier = Modifier.size(18.dp),
								)
							},
							trailingIcon = if (query.isNotEmpty()) {
								{
									IconButton(onClick = { query = "" }) {
										Icon(
											Icons.Filled.Close,
											contentDescription = stringResource(R.string.files_search_clear),
											modifier = Modifier.size(18.dp),
										)
									}
								}
							} else {
								null
							},
						)
					}
				}
				Hairline()
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner),
			// The capped list is centred like the empty state above it; a
			// wrap-content Box in a Column would sit against the left edge.
			contentAlignment = Alignment.TopCenter,
		) {
			when {
				state.loading -> Unit

				state.entries.isEmpty() -> Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.TopCenter,
				) {
					WorkbenchGrid(Modifier.fillMaxSize())
					Column(
						modifier = Modifier
							.widthIn(max = 640.dp)
							.fillMaxWidth()
							.padding(horizontal = comfortableGutter(), vertical = 32.dp),
						verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
					) {
						Eyebrow(stringResource(R.string.files_empty_eyebrow))
						Text(
							text = when {
								// An empty subfolder used to say the whole
								// workspace was empty, which is both wrong and
								// confusing when the user has just navigated in.
								state.currentPath.isNotEmpty() ->
									stringResource(R.string.files_empty_subdir_title)

								state.rulesEnabled ->
									stringResource(R.string.files_empty_title_rules)

								else -> stringResource(R.string.files_empty_title)
							},
							style = MaterialTheme.typography.headlineMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = when {
								state.currentPath.isNotEmpty() ->
									stringResource(R.string.files_empty_subdir_body)

								state.rulesEnabled ->
									stringResource(R.string.files_empty_body_rules)

								else -> stringResource(R.string.files_empty_body)
							},
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						// The old copy pointed at four icon-only toolbar buttons.
						// Put the two real actions where the sentence is.
						FlowRowCompat {
							Button(
								onClick = { importer.launch(arrayOf("*/*")) },
								shape = MaterialTheme.shapes.small,
							) {
								Text(stringResource(R.string.files_import))
							}
							OutlinedButton(
								onClick = { showDownloadDialog = true },
								shape = MaterialTheme.shapes.small,
							) {
								Text(stringResource(R.string.download_title))
							}
						}
					}
				}

				visibleEntries.isEmpty() -> Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.TopCenter,
				) {
					WorkbenchGrid(Modifier.fillMaxSize())
					Column(
						modifier = Modifier
							.widthIn(max = 640.dp)
							.fillMaxWidth()
							.padding(horizontal = comfortableGutter(), vertical = 32.dp),
						verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
					) {
						Eyebrow(stringResource(R.string.files_search))
						Text(
							text = stringResource(R.string.files_search_no_matches, query.trim()),
							style = MaterialTheme.typography.headlineMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = stringResource(R.string.files_search_no_matches_hint),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						TextButton(onClick = { query = "" }) {
							Text(stringResource(R.string.files_search_clear))
						}
					}
				}

				else -> LazyColumn(
					modifier = Modifier
						// Constrain first, fill second: `fillMaxSize()` is greedy
						// and consumes the incoming maximum, so swapping these two
						// made the cap inert.
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxSize(),
					contentPadding = PaddingValues(
						horizontal = comfortableGutter(),
						vertical = 8.dp,
					),
					verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
				) {
					items(visibleEntries, key = { it.relativePath }) { entry ->
						FileRow(
							entry = entry,
							onOpen = { viewModel.open(entry.relativePath, entry.isDirectory) },
							onExport = if (entry.isDirectory) null else {
								{
									exportTarget = entry.relativePath
									exporter.launch(viewModel.suggestedName(entry.relativePath))
								}
							},
							onDelete = { pendingDelete = entry },
							onRuleChange = if (state.rulesEnabled) {
								{ policy -> viewModel.setPathRule(entry.relativePath, policy) }
							} else {
								null
							},
						)
					}
				}
			}
		}
	}

	state.preview?.let { preview ->
		val markdown = isMarkdownName(preview.name)
		val image = preview.mimeType.startsWith("image/")
		// Rendered is the default: the point of saving a note as .md is that it
		// is readable, not that its asterisks are visible. Source stays one tap
		// away for the cases where the raw file is what matters (a fence that
		// does not close, a table the renderer lays out badly).
		var showSource by remember(preview.relativePath) { mutableStateOf(false) }
		AlertDialog(
			onDismissRequest = viewModel::closePreview,
			title = { Text(preview.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
			text = {
				Column {
					Row(
						horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
						verticalAlignment = Alignment.CenterVertically,
					) {
						if (preview.editable) {
							TextButton(
								onClick = {
									viewModel.closePreview()
									viewModel.openForEdit(preview.relativePath)
								},
							) { Text(stringResource(R.string.files_edit)) }
						}
						TextButton(
							onClick = {
								val workspaceId = state.workspaceId
								if (workspaceId == null) {
									Toast.makeText(context, notReady, Toast.LENGTH_SHORT).show()
								} else {
									viewModel.closePreview()
									openExternally(context, workspaceId, preview, openWith, noApp)
								}
							},
						) { Text(stringResource(R.string.files_open_with_ellipsis)) }
					}
					if (image) {
						PreviewImage(
							absolutePath = state.absolutePath,
							relativePath = preview.relativePath,
							name = preview.name,
						)
					} else if (markdown && !showSource) {
						MarkdownText(
							text = preview.text,
							modifier = Modifier
								.fillMaxWidth()
								.verticalScroll(rememberScrollState()),
						)
					} else {
						Text(
							text = preview.text,
							style = MaterialTheme.typography.bodyMedium,
							fontFamily = FontFamily.Monospace,
							modifier = Modifier
								.fillMaxWidth()
								.verticalScroll(rememberScrollState()),
						)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = viewModel::closePreview) { Text(stringResource(R.string.files_close)) }
			},
			dismissButton = if (markdown) {
				{
					TextButton(onClick = { showSource = !showSource }) {
						Text(
							if (showSource) {
								stringResource(R.string.files_rendered)
							} else {
								stringResource(R.string.files_source)
							},
						)
					}
				}
			} else {
				null
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	state.editor?.let { editor ->
		FileEditorDialog(
			editor = editor,
			busy = state.editorBusy,
			error = state.error,
			onChange = viewModel::updateEditor,
			onSave = viewModel::saveEditor,
			onDismiss = viewModel::closeEditor,
		)
	}

	// Deleting from the app also asks. Not because the app distrusts the user --
	// they are the one protected -- but because it is the same irreversible
	// operation the model's gate exists for, and two different rules for one
	// action is how a user learns to click through the one that matters.
	pendingDelete?.let { entry ->
		AlertDialog(
			onDismissRequest = { pendingDelete = null },
			title = { Text(stringResource(R.string.files_delete_title, entry.name)) },
			text = {
				Text(
					if (entry.isDirectory) {
						stringResource(R.string.files_delete_folder_body)
					} else {
						stringResource(R.string.files_delete_body)
					},
				)
			},
			confirmButton = {
				TextButton(onClick = {
					viewModel.delete(entry)
					pendingDelete = null
				}) { Text(stringResource(R.string.common_delete)) }
			},
			dismissButton = {
				TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	dialogError?.let { message ->
		AlertDialog(
			onDismissRequest = { importError = null; viewModel.refresh() },
			title = { Text(stringResource(R.string.files_error_title)) },
			text = { Text(message) },
			confirmButton = {
				TextButton(onClick = { importError = null; viewModel.refresh() }) { Text(stringResource(R.string.common_ok)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
		)
	}

	if (showPath) {
		AlertDialog(
			onDismissRequest = { showPath = false },
			title = { Text(stringResource(R.string.files_workspace_location)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						stringResource(R.string.files_path_body),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					SelectionContainer {
						Text(
							text = state.absolutePath,
							style = MaterialTheme.typography.bodySmall,
							fontFamily = FontFamily.Monospace,
						)
					}
					TextButton(
						onClick = {
							if (!openWorkspaceInFiles(context)) {
								Toast.makeText(context, noFilesApp, Toast.LENGTH_SHORT).show()
							}
						},
					) { Text(stringResource(R.string.workspace_open_in_files)) }
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						val clipboard = context.getSystemService(ClipboardManager::class.java)
						clipboard?.setPrimaryClip(
							ClipData.newPlainText(clipLabel, state.absolutePath),
						)
						Toast.makeText(context, pathCopied, Toast.LENGTH_SHORT).show()
					},
				) { Text(stringResource(R.string.files_copy_path)) }
			},
			dismissButton = {
				TextButton(onClick = { showPath = false }) { Text(stringResource(R.string.files_close)) }
			},
		)
	}

	if (showDownloadDialog) {
		DownloadDialog(
			viewModel = viewModel,
			onDismiss = { showDownloadDialog = false },
		)
	}

	if (showWorkspaceImport) {
		WorkspaceImportDialog(
			viewModel = viewModel,
			onDismiss = { showWorkspaceImport = false },
		)
	}
}

/**
 * Pick a file from another workspace and copy it into the folder on screen.
 *
 * The source browser is a user view, not an agent view: workspace rules do not
 * apply, and the dialog never exposes an absolute path. The copy itself goes
 * through `WorkspaceCopy`, so it is a snapshot with the same naming and quota
 * rules as a system import; the source is left alone.
 */
@Composable
private fun WorkspaceImportDialog(
	viewModel: FilesViewModel,
	onDismiss: () -> Unit,
) {
	var workspaces by remember { mutableStateOf<List<WorkspaceChoice>?>(null) }
	var selected by remember { mutableStateOf<WorkspaceChoice?>(null) }
	var path by remember { mutableStateOf("") }
	var entries by remember { mutableStateOf<List<ImportFileChoice>?>(null) }
	var readError by remember { mutableStateOf(false) }
	var listFailed by remember { mutableStateOf(false) }

	LaunchedEffect(Unit) {
		workspaces = runCatching { viewModel.otherWorkspaces() }
			.onFailure {
				Diag.w("files", "workspace list failed", it)
				listFailed = true
			}
			.getOrElse { emptyList() }
	}
	LaunchedEffect(selected?.id, path) {
		val source = selected ?: return@LaunchedEffect
		entries = null
		readError = false
		viewModel.sourceFiles(source.id, path)
			.onSuccess { entries = it }
			.onFailure {
				Diag.w("files", "source listing failed workspace=${source.id} path=$path", it)
				readError = true
			}
	}

	WideDialog(
		onDismissRequest = onDismiss,
		title = stringResource(R.string.files_import_workspace),
		dismissLabel = stringResource(R.string.files_close),
		maxBodyHeight = 460.dp,
	) {
		Text(
			text = stringResource(R.string.files_import_note),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		val source = selected
		if (source == null) {
			val list = workspaces
			when {
				listFailed -> Text(
					text = stringResource(R.string.files_import_read_failed),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.error,
				)

				list == null -> Box(
					modifier = Modifier.fillMaxWidth().padding(16.dp),
					contentAlignment = Alignment.Center,
				) { CircularProgressIndicator(Modifier.size(20.dp)) }

				list.isEmpty() -> Text(
					text = stringResource(R.string.files_import_no_workspaces),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)

				else -> {
					Text(
						text = stringResource(R.string.files_import_choose_workspace),
						style = MaterialTheme.typography.titleSmall,
						color = MaterialTheme.colorScheme.onSurface,
					)
					list.forEachIndexed { index, choice ->
						if (index > 0) Hairline()
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									selected = choice
									path = ""
								}
								.padding(vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = choice.name,
								style = MaterialTheme.typography.bodyLarge,
								modifier = Modifier.weight(1f),
							)
							Text(
								text = stringResource(
									if (choice.isPermanent) {
										R.string.files_import_permanent
									} else {
										R.string.files_import_temporary
									},
								),
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}
			return@WideDialog
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = source.name,
				style = MaterialTheme.typography.titleSmall,
				modifier = Modifier.weight(1f),
			)
			TextButton(
				onClick = {
					selected = null
					path = ""
					entries = null
				},
			) { Text(stringResource(R.string.files_import_change)) }
		}
		if (path.isNotEmpty()) {
			Text(
				text = stringResource(R.string.files_import_up),
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier
					.fillMaxWidth()
					.clickable {
						path = if (path.contains('/')) path.substringBeforeLast('/') else ""
					}
					.padding(vertical = 8.dp),
			)
		}

		when {
			readError -> Text(
				text = stringResource(R.string.files_import_read_failed),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
			)

			entries == null -> Box(
				modifier = Modifier.fillMaxWidth().padding(16.dp),
				contentAlignment = Alignment.Center,
			) { CircularProgressIndicator(Modifier.size(20.dp)) }

			entries.orEmpty().isEmpty() -> Text(
				text = stringResource(R.string.files_import_empty_dir),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			else -> entries.orEmpty().forEachIndexed { index, entry ->
				if (index > 0) Hairline()
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clickable {
							if (entry.isDirectory) {
								path = entry.relativePath
							} else {
								viewModel.importFromWorkspace(source.id, entry.relativePath)
								onDismiss()
							}
						}
						.padding(vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = entry.name,
						style = MaterialTheme.typography.bodyLarge,
						modifier = Modifier.weight(1f),
					)
					if (!entry.isDirectory) {
						Text(
							text = formatBytes(entry.sizeBytes),
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		}
	}
}

/**
 * Download one URL, either into the conversation or into a folder the user
 * picks through SAF.
 *
 * ## Why the destination is a choice
 *
 * The workspace is the sandbox; a PDF or zip the user wants to keep should not
 * have to live inside a conversation, and moving it out later is an extra step.
 * `ACTION_OPEN_DOCUMENT_TREE` is the only way to write to shared storage
 * without asking for `MANAGE_EXTERNAL_STORAGE` or a legacy storage permission;
 * the app still holds no broad file permission.
 *
 * ## Why there is no size limit
 *
 * The user asked for the file, and the dialog shows the size the server
 * reports. The workspace quota remains the limit when saving there; a chosen
 * folder is limited by the file system. The transfer is streamed, so a large
 * file does not have to fit in memory.
 */
@Composable
private fun DownloadDialog(
	viewModel: FilesViewModel,
	onDismiss: () -> Unit,
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val enterUrl = stringResource(R.string.download_enter_url)
	val chooseFolderFirst = stringResource(R.string.download_choose_folder_first)
	val downloadFailed = stringResource(R.string.download_failed)
	val cancelledText = stringResource(R.string.download_cancelled)
	val chosenFolder = stringResource(R.string.download_chosen_folder)
	val selectedFolder = stringResource(R.string.download_selected_folder)
	val cannotOpenFolder = stringResource(R.string.download_cannot_open_folder)

	var url by remember { mutableStateOf("") }
	var toWorkspace by remember { mutableStateOf(true) }
	var folderUri by remember { mutableStateOf<Uri?>(null) }
	var folderLabel by remember { mutableStateOf<String?>(null) }
	var busy by remember { mutableStateOf(false) }
	var cancelled by remember { mutableStateOf(false) }
	var progress by remember { mutableStateOf(0L) }
	var total by remember { mutableStateOf<Long?>(null) }
	var status by remember { mutableStateOf<String?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	var job by remember { mutableStateOf<Job?>(null) }
	var active by remember { mutableStateOf<UrlDownloader.DownloadResponse?>(null) }

	val folderPicker = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocumentTree(),
	) { uri ->
		if (uri != null) {
			runCatching {
				context.contentResolver.takePersistableUriPermission(
					uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
				)
			}
			folderUri = uri
			folderLabel = uri.lastPathSegment
				?.substringAfterLast(':')
				?.ifBlank { null }
				?: selectedFolder
		}
	}

	fun cancel() {
		cancelled = true
		job?.cancel()
		job = null
		runCatching { active?.close() }
		active = null
	}

	fun start() {
		val target = url.trim()
		if (target.isEmpty()) {
			error = enterUrl
			return
		}
		if (!toWorkspace && folderUri == null) {
			error = chooseFolderFirst
			return
		}
		error = null
		status = null
		cancelled = false
		progress = 0L
		total = null
		busy = true

		var lastReported = 0L
		fun report(bytes: Long) {
			// Compose state on every 64 KiB chunk would recompose the dialog
			// thousands of times for a large file; four updates a megabyte is
			// enough to see progress and cheap enough to forget.
			if (bytes - lastReported >= 256 * 1024) {
				lastReported = bytes
				progress = bytes
			}
		}

		job = scope.launch {
			try {
				UrlDownloader.open(target).use { opened ->
					active = opened
					total = opened.contentLength
					// A URL ending in `/` has no file name; the MIME type is
					// enough to give the saved file an extension, which is what
					// makes it openable from the file browser.
					val displayName = withExtension(opened.fileName, opened.mimeType)
					if (toWorkspace) {
						val result = viewModel.saveDownload(
							fileName = displayName,
							contentLength = opened.contentLength,
							open = { opened.body },
							onProgress = ::report,
						)
						result.onSuccess { saved ->
							progress = saved.bytes
							status = context.getString(
								R.string.download_saved_workspace,
								saved.relativePath,
								formatBytes(saved.bytes),
							)
						}.onFailure { failure ->
							error = failure.message ?: downloadFailed
						}
					} else {
						val tree = folderUri ?: throw IOException(chooseFolderFirst)
						val resolver = context.contentResolver
						val parent = DocumentsContract.buildDocumentUriUsingTree(
							tree,
							DocumentsContract.getTreeDocumentId(tree),
						)
						val document = createDocumentUnique(
							resolver = resolver,
							parent = parent,
							mimeType = opened.mimeType,
							fileName = displayName,
						)
						try {
							val output = resolver.openOutputStream(document)
								?: throw IOException(cannotOpenFolder)
							output.use { out ->
								CountingInputStream(opened.body, ::report).use { input ->
									input.copyTo(out)
								}
							}
							// A small file never crosses report()'s 256 KiB
							// threshold, so `progress` is still zero; the size
							// shown has to fall back to the Content-Length.
							val savedBytes = total ?: progress
							status = context.getString(
								R.string.download_saved_folder,
								displayName,
								formatBytes(savedBytes),
								folderLabel ?: chosenFolder,
							)
						} catch (failure: Exception) {
							runCatching { DocumentsContract.deleteDocument(resolver, document) }
							throw failure
						}
					}
				}
			} catch (_: CancellationException) {
				status = cancelledText
			} catch (failure: Exception) {
				if (cancelled) {
					status = cancelledText
				} else {
					error = failure.message ?: downloadFailed
				}
			} finally {
				active = null
				busy = false
				job = null
			}
		}
	}

	// A download can outlive a dismissed dialog if the user rotates or leaves
	// the screen; closing the source is what stops the socket and the file.
	DisposableEffect(Unit) {
		onDispose { cancel() }
	}

	AlertDialog(
		onDismissRequest = {
			if (!busy) {
				cancel()
				onDismiss()
			}
		},
		title = { Text(stringResource(R.string.download_title)) },
		text = {
			Column(
				modifier = Modifier.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				OutlinedTextField(
					value = url,
					onValueChange = {
						url = it
						error = null
					},
					label = { Text("https://…") },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
					enabled = !busy,
					modifier = Modifier.fillMaxWidth(),
				)

				Text(stringResource(R.string.download_save_to), style = MaterialTheme.typography.labelLarge)
				Row(verticalAlignment = Alignment.CenterVertically) {
					RadioButton(
						selected = toWorkspace,
						onClick = { if (!busy) toWorkspace = true },
						enabled = !busy,
					)
					Text(stringResource(R.string.download_to_workspace))
				}
				Text(
					stringResource(R.string.download_to_workspace_note),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(start = 40.dp),
				)

				Row(verticalAlignment = Alignment.CenterVertically) {
					RadioButton(
						selected = !toWorkspace,
						onClick = { if (!busy) toWorkspace = false },
						enabled = !busy,
					)
					Text(stringResource(R.string.download_other_folder))
				}
				if (!toWorkspace) {
					TextButton(
						onClick = { folderPicker.launch(null) },
						enabled = !busy,
					) {
						Text(
							folderLabel?.let {
								stringResource(R.string.download_folder_label, it)
							} ?: stringResource(R.string.download_choose_folder),
						)
					}
					Text(
						stringResource(R.string.download_folder_note),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(start = 40.dp),
					)
				}

				val size = total
				if (size != null) {
					Text(
						stringResource(R.string.download_size, formatBytes(size)),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				} else if (busy) {
					Text(
						stringResource(R.string.download_size_unknown),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				if (busy) {
					val knownTotal = total
					Text(
						if (knownTotal != null) {
							stringResource(
								R.string.download_progress,
								formatBytes(progress),
								formatBytes(knownTotal),
							)
						} else {
							stringResource(R.string.download_progress_no_total, formatBytes(progress))
						},
						style = MaterialTheme.typography.bodySmall,
					)
				}

				status?.let {
					Text(
						it,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.primary,
					)
				}
				error?.let {
					Text(
						it,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
			}
		},
		confirmButton = {
			if (busy) {
				TextButton(onClick = { cancel() }) { Text(stringResource(R.string.common_cancel)) }
			} else {
				TextButton(
					onClick = { start() },
					enabled = url.isNotBlank() && (toWorkspace || folderUri != null),
				) { Text(stringResource(R.string.download_action)) }
			}
		},
		dismissButton = {
			TextButton(
				onClick = {
					cancel()
					onDismiss()
				},
				enabled = !busy,
			) { Text(stringResource(R.string.files_close)) }
		},
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

/**
 * Give a name without an extension one, from the response MIME type.
 *
 * `https://example.com/` has no last path segment, so the downloader falls back
 * to "download". The content is HTML, and a file called `download` is harder to
 * open from a file manager than `download.html`. A name that already carries an
 * extension is never rewritten.
 */
private fun withExtension(name: String, mimeType: String): String {
	if (name.contains('.')) return name
	val extension = android.webkit.MimeTypeMap.getSingleton()
		.getExtensionFromMimeType(mimeType)
		?.takeIf { it.isNotBlank() }
		?: return name
	return "$name.$extension"
}

/**
 * Create a document in a SAF tree, adding a suffix if the name is taken.
 *
 * `DocumentsContract.createDocument` does not replace an existing file; it
 * throws or returns null. Failing the whole download because the folder already
 * has `report.pdf` would be a poor outcome for a user who picked that folder.
 */
private fun createDocumentUnique(
	resolver: ContentResolver,
	parent: Uri,
	mimeType: String,
	fileName: String,
): Uri {
	var candidate = fileName
	for (attempt in 1..50) {
		val created = runCatching {
			DocumentsContract.createDocument(resolver, parent, mimeType, candidate)
		}.getOrNull()
		if (created != null) return created
		val dot = fileName.lastIndexOf('.')
		candidate = if (dot > 0) {
			fileName.substring(0, dot) + " ($attempt)" + fileName.substring(dot)
		} else {
			"$fileName ($attempt)"
		}
	}
	throw IOException("Could not create a file in the chosen folder.")
}

private fun openExternally(
	context: Context,
	workspaceId: String,
	preview: FilePreview,
	chooserTitle: String,
	noAppMessage: String,
) {
	val authority = "${context.packageName}.documents"
	val documentId = "$workspaceId/${preview.relativePath}"
	val uri = DocumentsContract.buildDocumentUri(authority, documentId)
	val mime = preview.mimeType.ifBlank { "*/*" }
	val edit = Intent(Intent.ACTION_EDIT).apply {
		setDataAndType(uri, mime)
		addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
	}
	val view = Intent(Intent.ACTION_VIEW).apply {
		setDataAndType(uri, mime)
		addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	}
	val chosen = if (edit.resolveActivity(context.packageManager) != null) edit else view
	runCatching {
		context.startActivity(Intent.createChooser(chosen, chooserTitle))
	}.onFailure {
		Toast.makeText(context, noAppMessage, Toast.LENGTH_SHORT).show()
	}
}

@Composable
private fun FileEditorDialog(
	editor: FileEditor,
	busy: Boolean,
	error: String?,
	onChange: (String) -> Unit,
	onSave: () -> Unit,
	onDismiss: () -> Unit,
) {
	var confirmDiscard by remember(editor.relativePath) { mutableStateOf(false) }

	fun requestClose() {
		if (editor.dirty) {
			confirmDiscard = true
		} else {
			onDismiss()
		}
	}

	Dialog(
		onDismissRequest = ::requestClose,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier
				.fillMaxSize()
				.imePadding(),
			color = MaterialTheme.colorScheme.background,
		) {
			Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							editor.name,
							style = MaterialTheme.typography.titleMedium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						val chars = stringResource(R.string.files_editor_chars, editor.text.length)
						Text(
							text = if (editor.dirty) {
								stringResource(R.string.files_editor_unsaved, chars)
							} else {
								chars
							},
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					TextButton(onClick = ::requestClose, enabled = !busy) { Text(stringResource(R.string.files_close)) }
					TextButton(onClick = onSave, enabled = !busy) { Text(stringResource(R.string.common_save)) }
				}
				Hairline()
				error?.let {
					Text(
						it,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.padding(vertical = 6.dp),
					)
				}
				OutlinedTextField(
					value = editor.text,
					onValueChange = onChange,
					modifier = Modifier
						.weight(1f)
						.fillMaxWidth()
						.padding(top = 8.dp),
					textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
					singleLine = false,
					keyboardOptions = KeyboardOptions(
						capitalization = KeyboardCapitalization.None,
						autoCorrectEnabled = false,
					),
					label = { Text(stringResource(R.string.files_editor_label, editor.relativePath)) },
				)
			}
		}
	}

	if (confirmDiscard) {
		AlertDialog(
			onDismissRequest = { confirmDiscard = false },
			title = { Text(stringResource(R.string.files_discard_title)) },
			text = { Text(stringResource(R.string.files_discard_body)) },
			confirmButton = {
				TextButton(
					onClick = {
						confirmDiscard = false
						onDismiss()
					},
				) { Text(stringResource(R.string.files_discard)) }
			},
			dismissButton = {
				TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.files_keep_editing)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * A downsampled image preview.
 *
 * The list already knows the absolute workspace path for the user; decoding is
 * off the main thread and bounded to roughly 1600px on the long edge, because a
 * phone photo is not a bitmap anyone needs at full resolution in a dialog.
 */
@Composable
private fun PreviewImage(absolutePath: String, relativePath: String, name: String) {
	val preview by produceState<ImagePreviewState>(
		initialValue = ImagePreviewState.Loading,
		absolutePath,
		relativePath,
	) {
		value = withContext(Dispatchers.IO) {
			runCatching {
				if (absolutePath.isBlank()) return@runCatching ImagePreviewState.Failed
				val file = File(absolutePath, relativePath)
				if (!file.isFile) return@runCatching ImagePreviewState.Failed
				val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
				BitmapFactory.decodeFile(file.absolutePath, bounds)
				if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
					return@runCatching ImagePreviewState.Failed
				}
				var sample = 1
				while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
					sample *= 2
				}
				val decoded = BitmapFactory.decodeFile(
					file.absolutePath,
					BitmapFactory.Options().apply { inSampleSize = sample },
				) ?: return@runCatching ImagePreviewState.Failed
				ImagePreviewState.Ready(decoded.asImageBitmap())
			}.getOrDefault(ImagePreviewState.Failed)
		}
	}
	when (val current = preview) {
		ImagePreviewState.Loading -> Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
		) {
			CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
			Text(
				text = stringResource(R.string.files_preview_image_loading),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		ImagePreviewState.Failed -> Text(
			text = stringResource(R.string.files_preview_image_failed),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		is ImagePreviewState.Ready -> Image(
			bitmap = current.image,
			contentDescription = name,
			contentScale = ContentScale.Fit,
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(max = 420.dp),
		)
	}
}

@Composable
private fun FileRow(
	entry: FileEntryUi,
	onOpen: () -> Unit,
	onExport: (() -> Unit)?,
	onDelete: () -> Unit,
	onRuleChange: ((ToolPolicy) -> Unit)?,
) {
	var ruleMenuOpen by remember { mutableStateOf(false) }
	var actionsMenuOpen by remember { mutableStateOf(false) }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
			.clickable(onClick = onOpen)
			.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(
			imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
			contentDescription = null,
			tint = if (entry.isDirectory) {
				MaterialTheme.colorScheme.primary
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
			modifier = Modifier.size(20.dp),
		)

		Column(
			modifier = Modifier
				.weight(1f)
				.padding(start = 12.dp),
			verticalArrangement = Arrangement.spacedBy(1.dp),
		) {
			Text(
				text = entry.name,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			// The rule, when there is one, replaces the date rather than sitting
			// beside it. A restricted file is the thing a user needs to notice,
			// and a second line of small grey text would be read as metadata.
			if (entry.rule != ToolPolicy.Free) {
				Text(
					text = entry.rule.label(),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.primary,
				)
			} else {
				Text(
					text = if (entry.isDirectory) entry.readableDate
					else "${entry.readableSize} · ${entry.readableDate}",
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}

		// What the agent may do with this file. A workspace opened without a
		// conversation has no conversation-scoped rules, so the control is absent
		// rather than showing a policy that would not be consulted.
		if (onRuleChange != null) {
			Box {
				IconButton(onClick = { ruleMenuOpen = true }) {
					Icon(
						if (entry.rule == ToolPolicy.Free) Icons.Outlined.LockOpen
						else Icons.Filled.Lock,
						contentDescription = stringResource(R.string.files_rule_button),
						tint = if (entry.rule == ToolPolicy.Free) {
							MaterialTheme.colorScheme.onSurfaceVariant
						} else {
							MaterialTheme.colorScheme.primary
						},
						modifier = Modifier.size(18.dp),
					)
				}
				DropdownMenu(expanded = ruleMenuOpen, onDismissRequest = { ruleMenuOpen = false }) {
					for (option in ToolPolicy.entries) {
						DropdownMenuItem(
							text = { Text(option.label()) },
							onClick = {
								ruleMenuOpen = false
								onRuleChange(option)
							},
						)
					}
				}
			}
		}

		// Export and delete live behind one menu instead of two always-visible
		// icons. The delete icon was the brightest thing on every row, and a
		// destructive action one tap away from the row that opens a file is a
		// mis-tap waiting to happen.
		Box {
			IconButton(onClick = { actionsMenuOpen = true }) {
				Icon(
					Icons.Filled.MoreVert,
					contentDescription = stringResource(R.string.files_more_actions),
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(18.dp),
				)
			}
			DropdownMenu(
				expanded = actionsMenuOpen,
				onDismissRequest = { actionsMenuOpen = false },
			) {
				if (onExport != null) {
					DropdownMenuItem(
						text = { Text(stringResource(R.string.files_save_copy)) },
						leadingIcon = {
							Icon(Icons.Outlined.SaveAlt, contentDescription = null)
						},
						onClick = {
							actionsMenuOpen = false
							onExport()
						},
					)
				}
				DropdownMenuItem(
					text = { Text(stringResource(R.string.common_delete)) },
					leadingIcon = {
						Icon(
							Icons.Outlined.Delete,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.error,
						)
					},
					onClick = {
						actionsMenuOpen = false
						onDelete()
					},
				)
			}
		}
	}
}

/**
 * What a rule means, in the words a user would use.
 *
 * Duplicated from the settings screen deliberately, and the duplication is worth
 * naming: the two screens show the same four states and a shared helper would
 * have to live in `:core:design`, which is about layout rather than about
 * policy. Two four-line `when`s are cheaper than the module edge -- but if a
 * fifth policy is ever added, this is the second place that has to learn it.
 */
@Composable
private fun ToolPolicy.label(): String = when (this) {
	ToolPolicy.Free -> stringResource(R.string.files_policy_free)
	ToolPolicy.Ask -> stringResource(R.string.files_policy_ask)
	ToolPolicy.Deny -> stringResource(R.string.files_policy_deny)
	ToolPolicy.Blind -> stringResource(R.string.files_policy_blind)
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
	runCatching {
		context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
			val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
			if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
		}
	}.getOrNull()
