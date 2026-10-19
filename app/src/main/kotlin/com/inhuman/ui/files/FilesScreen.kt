package com.inhuman.ui.files

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.chat.MarkdownText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

	val anyDialogOpen = pendingDelete != null || importError != null ||
		state.error != null || state.preview != null || showDownloadDialog
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
			TopAppBar(
				title = {
					Column {
						Text(
							text = if (state.currentPath.isEmpty()) "Files" else state.currentPath.substringAfterLast('/'),
							style = MaterialTheme.typography.titleMedium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							// The quota, shown because a user who hits it deserves
							// to know it is a limit rather than a bug.
							text = "${state.fileCount}/${state.maxFiles} files · ${human(state.usedBytes)} of ${human(state.totalBytes)}",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				},
				navigationIcon = {
					IconButton(onClick = { if (state.currentPath.isEmpty()) onBack() else viewModel.navigateUp() }) {
						Text("‹", style = MaterialTheme.typography.titleLarge)
					}
				},
				actions = {
					IconButton(onClick = { showDownloadDialog = true }) {
						Icon(Icons.Filled.Download, contentDescription = "Download from a URL")
					}
					IconButton(onClick = { importer.launch(arrayOf("*/*")) }) {
						Icon(Icons.Filled.Upload, contentDescription = "Import a file")
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
					navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
					actionIconContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				.windowInsetsPadding(WindowInsets.safeDrawing),
		) {
			when {
				state.loading -> Unit

				state.entries.isEmpty() -> Column(
					modifier = Modifier
						.fillMaxSize()
						.padding(horizontal = comfortableGutter()),
					verticalArrangement = Arrangement.Center,
					horizontalAlignment = Alignment.CenterHorizontally,
				) {
					Text(
						text = "This conversation's folder is empty",
						style = MaterialTheme.typography.bodyLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = "Ask the assistant to create something, or import a file.",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.outline,
						modifier = Modifier.padding(top = 6.dp),
					)
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
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					items(state.entries, key = { it.relativePath }) { entry ->
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
							onRuleChange = { policy ->
								viewModel.setPathRule(entry.relativePath, policy)
							},
						)
					}
				}
			}
		}
	}

	state.preview?.let { preview ->
		val markdown = isMarkdownName(preview.name)
		// Rendered is the default: the point of saving a note as .md is that it
		// is readable, not that its asterisks are visible. Source stays one tap
		// away for the cases where the raw file is what matters (a fence that
		// does not close, a table the renderer lays out badly).
		var showSource by remember(preview.relativePath) { mutableStateOf(false) }
		AlertDialog(
			onDismissRequest = viewModel::closePreview,
			title = { Text(preview.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
			text = {
				if (markdown && !showSource) {
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
			},
			confirmButton = {
				TextButton(onClick = viewModel::closePreview) { Text("Close") }
			},
			dismissButton = if (markdown) {
				{
					TextButton(onClick = { showSource = !showSource }) {
						Text(if (showSource) "Rendered" else "Source")
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

	// Deleting from the app also asks. Not because the app distrusts the user --
	// they are the one protected -- but because it is the same irreversible
	// operation the model's gate exists for, and two different rules for one
	// action is how a user learns to click through the one that matters.
	pendingDelete?.let { entry ->
		AlertDialog(
			onDismissRequest = { pendingDelete = null },
			title = { Text("Delete ${entry.name}?") },
			text = {
				Text(
					if (entry.isDirectory) "The folder and everything in it will be removed. This cannot be undone."
					else "This cannot be undone.",
				)
			},
			confirmButton = {
				TextButton(onClick = {
					viewModel.delete(entry)
					pendingDelete = null
				}) { Text("Delete") }
			},
			dismissButton = {
				TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	(state.error ?: importError)?.let { message ->
		AlertDialog(
			onDismissRequest = { importError = null; viewModel.refresh() },
			title = { Text("Could not do that") },
			text = { Text(message) },
			confirmButton = {
				TextButton(onClick = { importError = null; viewModel.refresh() }) { Text("OK") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
		)
	}

	if (showDownloadDialog) {
		DownloadDialog(
			viewModel = viewModel,
			onDismiss = { showDownloadDialog = false },
		)
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
				?: "Selected folder"
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
			error = "Enter a URL first."
			return
		}
		if (!toWorkspace && folderUri == null) {
			error = "Choose a folder first."
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
							status = "Saved to workspace: ${saved.relativePath} (${human(saved.bytes)})"
						}.onFailure { failure ->
							error = failure.message ?: "Download failed."
						}
					} else {
						val tree = folderUri ?: throw IOException("Choose a folder first.")
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
								?: throw IOException("Could not open the chosen folder.")
							output.use { out ->
								CountingInputStream(opened.body, ::report).use { input ->
									input.copyTo(out)
								}
							}
							// A small file never crosses report()'s 256 KiB
							// threshold, so `progress` is still zero; the size
							// shown has to fall back to the Content-Length.
							val savedBytes = total ?: progress
							status = "Saved $displayName (${human(savedBytes)}) to " +
								(folderLabel ?: "the chosen folder")
						} catch (failure: Exception) {
							runCatching { DocumentsContract.deleteDocument(resolver, document) }
							throw failure
						}
					}
				}
			} catch (_: CancellationException) {
				status = "Download cancelled."
			} catch (failure: Exception) {
				if (cancelled) {
					status = "Download cancelled."
				} else {
					error = failure.message ?: "Download failed."
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
		title = { Text("Download from a URL") },
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

				Text("Save to", style = MaterialTheme.typography.labelLarge)
				Row(verticalAlignment = Alignment.CenterVertically) {
					RadioButton(
						selected = toWorkspace,
						onClick = { if (!busy) toWorkspace = true },
						enabled = !busy,
					)
					Text("Conversation workspace")
				}
				Text(
					"downloads/ inside this conversation. It counts against the " +
						"workspace quota and is visible in Files.",
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
					Text("Another folder")
				}
				if (!toWorkspace) {
					TextButton(
						onClick = { folderPicker.launch(null) },
						enabled = !busy,
					) {
						Text(folderLabel?.let { "Folder: $it" } ?: "Choose folder…")
					}
					Text(
						"Uses the system folder picker. The app does not gain " +
							"general storage permission.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(start = 40.dp),
					)
				}

				val size = total
				if (size != null) {
					Text(
						"Size: ${human(size)}",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				} else if (busy) {
					Text(
						"Size not reported by the server.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				if (busy) {
					val knownTotal = total
					Text(
						if (knownTotal != null) {
							"Downloading ${human(progress)} of ${human(knownTotal)}"
						} else {
							"Downloading ${human(progress)}"
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
				TextButton(onClick = { cancel() }) { Text("Cancel") }
			} else {
				TextButton(
					onClick = { start() },
					enabled = url.isNotBlank() && (toWorkspace || folderUri != null),
				) { Text("Download") }
			}
		},
		dismissButton = {
			TextButton(
				onClick = {
					cancel()
					onDismiss()
				},
				enabled = !busy,
			) { Text("Close") }
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

@Composable
private fun FileRow(
	entry: FileEntryUi,
	onOpen: () -> Unit,
	onExport: (() -> Unit)?,
	onDelete: () -> Unit,
	onRuleChange: (ToolPolicy) -> Unit,
) {
	var ruleMenuOpen by remember { mutableStateOf(false) }

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

		if (onExport != null) {
			IconButton(onClick = onExport) {
				Icon(
					Icons.Outlined.SaveAlt,
					contentDescription = "Save a copy",
					modifier = Modifier.size(18.dp),
				)
			}
		}

		// What the agent may do with this file.
		Box {
			IconButton(onClick = { ruleMenuOpen = true }) {
				Icon(
					if (entry.rule == ToolPolicy.Free) Icons.Outlined.LockOpen
					else Icons.Filled.Lock,
					contentDescription = "What the agent may do with this",
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

		IconButton(onClick = onDelete) {
			Icon(
				Icons.Outlined.Delete,
				contentDescription = "Delete",
				// `outline` was used here first and the button was effectively
				// invisible: it is a near-background grey by design, meant for
				// hairlines rather than glyphs, and at roughly 2:1 contrast the
				// icon simply did not appear. A destructive control that cannot
				// be seen is worse than one that is too prominent.
				tint = MaterialTheme.colorScheme.error,
				modifier = Modifier.size(18.dp),
			)
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
private fun ToolPolicy.label(): String = when (this) {
	ToolPolicy.Free -> "Agent may change this"
	ToolPolicy.Ask -> "Agent asks first"
	ToolPolicy.Deny -> "Agent may look, not change"
	ToolPolicy.Blind -> "Hidden from the agent"
}

private fun human(bytes: Long): String = when {
	bytes < 1024 -> "$bytes B"
	bytes < 1024 * 1024 -> "${bytes / 1024} KB"
	else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
	runCatching {
		context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
			val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
			if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
		}
	}.getOrNull()
