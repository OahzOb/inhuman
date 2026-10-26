package com.inhuman.ui.files

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.ui.common.formatBytes
import com.inhuman.R
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.DateFormat
import java.util.Date

/** One entry in the browser. */
data class FileEntryUi(
	val name: String,
	val relativePath: String,
	val isDirectory: Boolean,
	val sizeBytes: Long,
	val modifiedAt: Long,
	val mimeType: String,
	/**
	 * What the agent may do with this, from a rule on this exact path.
	 *
	 * The rule **on this path**, not the effective one: the effective policy for
	 * a nested file also accounts for its ancestors, and showing that here would
	 * make a folder's rule look like it had been copied onto everything inside
	 * it. What the row offers to change is this path's own rule.
	 */
	val rule: ToolPolicy = ToolPolicy.Free,
) {
	val readableSize: String
		get() = when {
			isDirectory -> ""
			sizeBytes < 1024 -> "$sizeBytes B"
			sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
			else -> formatBytes(sizeBytes)
		}

	val readableDate: String
		get() = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
			.format(Date(modifiedAt))
}

data class FilesUiState(
	/** Path being shown, relative to the workspace root. Empty is the root. */
	val currentPath: String = "",
	/** The durable workspace id, used to build a writable document URI. */
	val workspaceId: String? = null,
	val entries: List<FileEntryUi> = emptyList(),
	val loading: Boolean = true,
	/** Bytes used and the ceiling, for the header. */
	val usedBytes: Long = 0,
	val totalBytes: Long = 0,
	val fileCount: Int = 0,
	val maxFiles: Int = 0,
	val error: String? = null,
	/** Non-null while a preview is open. */
	val preview: FilePreview? = null,
	/** Non-null while the in-app text editor is open. */
	val editor: FileEditor? = null,
	val editorBusy: Boolean = false,
	/**
	 * Absolute app-private directory, shown to the user and never sent to the
	 * model. A workspace opened without a conversation has no rules to edit.
	 */
	val absolutePath: String = "",
	val rulesEnabled: Boolean = false,
)

data class FilePreview(
	val name: String,
	val relativePath: String,
	val text: String,
	val mimeType: String = "text/plain",
	val editable: Boolean = false,
	val truncated: Boolean = false,
)

/**
 * An open in-app text edit.
 *
 * [originalText] is kept only so the dialog can ask before discarding a dirty
 * buffer; the workspace file remains the source of truth until Save.
 */
data class FileEditor(
	val name: String,
	val relativePath: String,
	val originalText: String,
	val text: String,
) {
	val dirty: Boolean get() = text != originalText
}

/** Where a download landed inside the workspace. */
data class DownloadSave(val relativePath: String, val bytes: Long)

/**
 * The workspace, as a browser.
 *
 * ## Why this exists next to a DocumentsProvider
 *
 * The provider makes the files reachable from the system Files app, which is
 * what a user wants when they are *elsewhere*. It does not help inside the app,
 * where the context is a conversation and the question is "what did it make?".
 * Having both is not duplication: they are the same files seen from the two
 * places a user looks.
 *
 * ## Attachment
 *
 * Attaching means quoting the file into the message rather than uploading it to
 * the provider, and that is a deliberate limit for now. Sending bytes to the
 * model needs per-provider multimodal formats -- base64 image blocks for one,
 * inline parts for another, upload-then-reference for a third -- and doing it
 * badly would silently send nothing. Quoting always works, is visible in the
 * transcript, and costs only tokens.
 */
class FilesViewModel(
	private val context: Context,
	private val repository: ConversationRepository,
	private val conversationId: String? = null,
	private val requestedWorkspaceId: String? = null,
) : ViewModel() {

	init {
		require(conversationId != null || requestedWorkspaceId != null) {
			"FilesViewModel needs a conversation or a workspace."
		}
	}

	/** Resolved once, because a workspace id is the durable part of this screen. */
	private var workspaceId: String? = requestedWorkspaceId

	private val _state = MutableStateFlow(FilesUiState())
	val state: StateFlow<FilesUiState> = _state.asStateFlow()

	// No refresh in `init`.
	//
	// FilesScreen refreshes on every resume instead. Doing both walked the
	// workspace twice on first open; doing only `init` meant that reopening the
	// screen showed the list as it was when the view model was first built. The
	// symptom was exactly the reported one: the assistant wrote a file, the
	// transcript said so, and Files was still empty because the activity-scoped
	// view model had cached the pre-write state.

	/**
	 * The workspace for this conversation, or null if the row is gone.
	 *
	 * `suspend` and off the main thread. It used to be a plain function wrapping
	 * `runBlocking` around a database read, and every caller -- refresh, open,
	 * delete, import -- reached it from `viewModelScope`, whose dispatcher is
	 * `Dispatchers.Main.immediate`. So each of those parked the thread that draws
	 * the UI while Room answered.
	 */
	private suspend fun workspace(): SessionWorkspace? {
		val id = workspaceId ?: conversationId?.let { id ->
			withContext(Dispatchers.IO) { repository.conversation(id)?.entity?.workspaceId }
		} ?: return null
		workspaceId = id
		return repository.workspace(id)
	}

	/**
	 * Set the agent's rule for one path.
	 *
	 * Written through and then re-read, like the settings screen does and for the
	 * same reason: what the row shows has to be what a tool call would resolve,
	 * and a locally-patched list would be a second source of truth.
	 */
	fun setPathRule(relativePath: String, policy: ToolPolicy) {
		val conversation = conversationId ?: return
		viewModelScope.launch {
			withContext(Dispatchers.IO) {
				runCatching { repository.policies().setPathRule(conversation, relativePath, policy) }
					.onFailure {
						Diag.w(
							"policy",
							"path rule write failed path=$relativePath policy=$policy",
							it,
						)
					}
			}
			refresh()
		}
	}

	fun refresh() {
		viewModelScope.launch {
			_state.value = _state.value.copy(loading = true, error = null)
			val ws = workspace()
			val id = workspaceId
			if (ws == null || id == null) {
				Diag.w("files", "refresh: workspace not found")
				_state.value = _state.value.copy(
			loading = false,
			error = context.getString(R.string.files_error_workspace_missing),
		)
				return@launch
			}
			val path = _state.value.currentPath
			// One block, one walk. `usedBytes()` and `fileCount()` each call
			// `usage()`, which traverses the whole tree, so asking for them
			// separately walked the workspace twice per refresh -- the cost A6
			// exists to remove, reintroduced here.
			val result = withContext(Dispatchers.IO) {
				val dir = when (val r = ws.resolve(path)) {
					is SessionWorkspace.Resolution.Ok -> r.file
					is SessionWorkspace.Resolution.Rejected -> null
				}
				// The rules for this conversation, read once. A per-entry query
				// would be one round trip per row. A workspace opened without a
				// conversation has no conversation-scoped rules to show.
				val rules = conversationId?.let { conversation ->
					runCatching {
						repository.policies().forConversation(conversation, agentId = null).paths
					}.getOrElse {
						Diag.w("policy", "path rules read failed; showing no rules", it)
						emptyMap()
					}
				}.orEmpty()

				val entries = if (dir == null || !dir.isDirectory) {
					Diag.w(
						"files",
						"list failed path=${path.ifBlank { "/" }} dir=${dir != null}",
					)
					null
				} else {
					dir.listFiles().orEmpty()
						.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
						.take(ws.limits.maxListEntries)
						.map { f ->
							val relative = ws.relativize(f)
							FileEntryUi(
								name = f.name,
								relativePath = relative,
								isDirectory = f.isDirectory,
								sizeBytes = if (f.isDirectory) 0 else f.length(),
								modifiedAt = f.lastModified(),
								mimeType = mimeTypeOf(f),
								rule = rules[relative] ?: ToolPolicy.Free,
							)
						}
				}
				entries to ws.usage()
			}
			val (entries, usage) = result
			_state.value = _state.value.copy(
				loading = false,
				workspaceId = id,
				entries = entries.orEmpty(),
				error = if (entries == null) "cannot list that directory" else null,
				usedBytes = usage.bytes,
				totalBytes = ws.limits.maxTotalBytes,
				fileCount = usage.files,
				maxFiles = ws.limits.maxFileCount,
				absolutePath = repository.workspacePath(id),
				rulesEnabled = conversationId != null,
			)
			Diag.d(
				"files",
				"refresh path=${path.ifBlank { "/" }} entries=${entries?.size ?: -1} " +
					"files=${usage.files} bytes=${usage.bytes}",
			)
		}
	}

	fun open(path: String, isDirectory: Boolean) {
		if (isDirectory) {
			_state.value = _state.value.copy(currentPath = path, preview = null)
			refresh()
		} else {
			viewModelScope.launch {
				val ws = workspace() ?: return@launch
				val entry = _state.value.entries.firstOrNull { it.relativePath == path }
				val mime = entry?.mimeType ?: "text/plain"
				val text = withContext(Dispatchers.IO) {
					val f = (ws.resolve(path) as? SessionWorkspace.Resolution.Ok)?.file ?: return@withContext null
					if (f.length() > ws.limits.maxReadBytes) return@withContext null
					runCatching { f.readText() }
						.onFailure { Diag.w("files", "preview read failed path=$path", it) }
						.getOrNull()
				}
				val editable = text != null &&
					!text.contains('\u0000') &&
					(mime.startsWith("text/") || isTextExtension(path))
				_state.value = _state.value.copy(
					preview = if (text != null) {
						FilePreview(
							name = path.substringAfterLast('/'),
							relativePath = path,
							text = text,
							mimeType = mime,
							editable = editable,
						)
					} else {
						// Not text, or too big. Reported rather than opened as
						// mojibake, which looks like a corrupt file.
						FilePreview(
							name = path.substringAfterLast('/'),
							relativePath = path,
							text = "(binary or larger than the read limit — open it with another app)",
							mimeType = mime,
							editable = false,
						)
					},
				)
			}
		}
	}

	/** Open the text of one workspace file in the in-app editor. */
	fun openForEdit(path: String) {
		if (_state.value.editorBusy) return
		viewModelScope.launch {
			val ws = workspace() ?: return@launch
			val file = (ws.resolve(path) as? SessionWorkspace.Resolution.Ok)?.file
			if (file == null || file.isDirectory) {
				_state.value = _state.value.copy(
				error = context.getString(R.string.files_error_not_editable),
			)
				return@launch
			}
			if (file.length() > ws.limits.maxReadBytes) {
				_state.value = _state.value.copy(
					error = context.getString(R.string.files_error_too_large),
				)
				return@launch
			}
			val text = withContext(Dispatchers.IO) {
				runCatching { file.readText() }
					.onFailure { Diag.w("files", "editor read failed path=$path", it) }
					.getOrNull()
			}
			if (text == null || text.contains('\u0000')) {
				_state.value = _state.value.copy(
					error = context.getString(R.string.files_error_binary),
				)
				return@launch
			}
			_state.value = _state.value.copy(
				preview = null,
				editor = FileEditor(
					name = path.substringAfterLast('/'),
					relativePath = path,
					originalText = text,
					text = text,
				),
				error = null,
			)
		}
	}

	fun updateEditor(text: String) {
		val editor = _state.value.editor ?: return
		_state.value = _state.value.copy(editor = editor.copy(text = text))
	}

	/**
	 * Save the editor through the same atomic transfer used by imports.
	 *
	 * Writing the file directly would bypass the total/free-space checks and
	 * leave a half-written file if the process died. The maximum writable size is
	 * the workspace's per-file limit; a larger file is refused before Save.
	 */
	fun saveEditor() {
		val editor = _state.value.editor ?: return
		if (_state.value.editorBusy) return
		_state.value = _state.value.copy(editorBusy = true, error = null)
		viewModelScope.launch {
			val ws = workspace()
			if (ws == null) {
				_state.value = _state.value.copy(
			editorBusy = false,
			error = context.getString(R.string.files_error_workspace_missing),
		)
				return@launch
			}
			val bytes = editor.text.toByteArray(Charsets.UTF_8)
			val outcome = withContext(Dispatchers.IO) {
				WorkspaceTransfer(ws).import(
					destinationPath = editor.relativePath,
					maxBytes = ws.limits.maxWriteBytes,
				) { ByteArrayInputStream(bytes) }
			}
			when (outcome) {
				is WorkspaceTransfer.Outcome.Ok -> {
					Diag.i("files", "editor saved path=${editor.relativePath} bytes=${outcome.bytes}")
					_state.value = _state.value.copy(editor = null, editorBusy = false, error = null)
					refresh()
				}

				is WorkspaceTransfer.Outcome.Failed -> {
					Diag.w("files", "editor save failed path=${editor.relativePath} reason=${outcome.reason}")
					_state.value = _state.value.copy(editorBusy = false, error = outcome.reason)
				}
			}
		}
	}

	fun closeEditor() {
		if (_state.value.editorBusy) return
		_state.value = _state.value.copy(editor = null, error = null)
	}

	private fun isTextExtension(path: String): Boolean {
		val ext = path.substringAfterLast('.', "").lowercase()
		return ext in setOf(
			"txt", "md", "markdown", "json", "jsonl", "kt", "kts", "java", "xml",
			"yaml", "yml", "toml", "csv", "tsv", "log", "sh", "js", "ts", "py",
			"html", "css", "sql", "ini", "properties", "gradle", "gitignore",
		)
	}

	fun closePreview() = _state.update { it.copy(preview = null) }

	fun navigateUp() {
		val current = _state.value.currentPath
		val parent = if (current.contains('/')) current.substringBeforeLast('/') else ""
		_state.value = _state.value.copy(currentPath = parent, preview = null)
		refresh()
	}

	/** Remove a file, from the app rather than through the model. */
	fun delete(entry: FileEntryUi) {
		Diag.i(
			"files",
			"delete path=${entry.relativePath} dir=${entry.isDirectory}",
		)
		viewModelScope.launch {
			val ws = workspace() ?: return@launch
			withContext(Dispatchers.IO) {
				val file = (ws.resolve(entry.relativePath) as? SessionWorkspace.Resolution.Ok)?.file
				if (file == null) {
					Diag.w("files", "delete refused by sandbox path=${entry.relativePath}")
				} else {
					Diag.i("files", "delete done path=${entry.relativePath} ok=${file.deleteRecursively()}")
				}
			}
			refresh()
		}
	}

	/**
	 * Copy an incoming stream into the workspace.
	 *
	 * The caller supplies the opener because only it knows which URI contract
	 * produced it; this class stays free of Android storage APIs.
	 */
	fun importFile(displayName: String, open: () -> java.io.InputStream) {
		viewModelScope.launch {
			val ws = workspace() ?: return@launch
			val transfer = WorkspaceTransfer(ws)
			val name = transfer.uniqueName(displayName)
			val target = if (_state.value.currentPath.isEmpty()) {
				name
			} else {
				"${_state.value.currentPath}/$name"
			}
			val outcome = withContext(Dispatchers.IO) { transfer.import(target) { open() } }
			when (outcome) {
				is WorkspaceTransfer.Outcome.Failed -> {
					Diag.w("files", "import failed target=$target reason=${outcome.reason}")
					_state.value = _state.value.copy(error = outcome.reason)
				}

				is WorkspaceTransfer.Outcome.Ok -> {
					Diag.i(
						"files",
						"imported path=${outcome.relativePath} bytes=${outcome.bytes}",
					)
					_state.value = _state.value.copy(error = null)
				}
			}
			refresh()
		}
	}

	/**
	 * Save a user-initiated download into this workspace.
	 *
	 * The workspace's own quota still applies: "no download limit" means no
	 * arbitrary cap on top of the storage the user actually has, not permission
	 * to walk past the sandbox's 256 MiB ceiling. The per-file `maxWriteBytes`
	 * limit is deliberately not used here -- a downloaded PDF or zip is not a
	 * tool write -- so the ceiling is the space left in the workspace.
	 *
	 * @return the workspace-relative path and size, or a message to show.
	 */
	suspend fun saveDownload(
		fileName: String,
		contentLength: Long?,
		open: () -> InputStream,
		onProgress: (Long) -> Unit,
	): Result<DownloadSave> = withContext(Dispatchers.IO) {
		val ws = workspace()
			?: return@withContext Result.failure(IOException("This workspace no longer exists."))
		val transfer = WorkspaceTransfer(ws)
		val free = (ws.limits.maxTotalBytes - ws.usage().bytes).coerceAtLeast(0)
		if (free <= 0) {
			return@withContext Result.failure(IOException("The workspace is full."))
		}
		if (contentLength != null && contentLength > free) {
			return@withContext Result.failure(
				IOException(
					"The download is ${formatBytes(contentLength)}, but the workspace has only " +
						"${formatBytes(free)} free. Choose a folder instead, or make room.",
				),
			)
		}
		val target = "downloads/" + transfer.uniqueName(fileName)
		val counted = CountingInputStream(open(), onProgress)
		when (val outcome = transfer.import(target, maxBytes = free) { counted }) {
			is WorkspaceTransfer.Outcome.Ok -> {
				Diag.i("download", "saved path=${outcome.relativePath} bytes=${outcome.bytes}")
				refresh()
				Result.success(DownloadSave(outcome.relativePath, outcome.bytes))
			}

			is WorkspaceTransfer.Outcome.Failed ->
				Result.failure(IOException(outcome.reason))
		}
	}

	/**
	 * Write a workspace file out through a destination the caller opens.
	 *
	 * @return null on success, or a message to show.
	 */
	suspend fun exportFile(relativePath: String, open: () -> java.io.OutputStream): String? {
		val ws = workspace() ?: return "workspace not found"
		val outcome = withContext(Dispatchers.IO) {
			WorkspaceTransfer(ws).export(relativePath) { open() }
		}
		return when (outcome) {
			is WorkspaceTransfer.Outcome.Failed -> {
				Diag.w("files", "export failed path=$relativePath reason=${outcome.reason}")
				outcome.reason
			}

			is WorkspaceTransfer.Outcome.Ok -> {
				Diag.i("files", "exported path=${outcome.relativePath} bytes=${outcome.bytes}")
				null
			}
		}
	}

	fun suggestedName(relativePath: String): String =
		relativePath.substringAfterLast('/').ifBlank { "file" }

	private fun mimeTypeOf(file: File): String {
		if (file.isDirectory) return "vnd.android.document/directory"
		val ext = file.extension.lowercase()
		return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
			?: "application/octet-stream"
	}
}

private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
	value = transform(value)
}
