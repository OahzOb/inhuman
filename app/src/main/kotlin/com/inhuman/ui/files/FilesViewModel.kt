package com.inhuman.ui.files

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
) {
	val readableSize: String
		get() = when {
			isDirectory -> ""
			sizeBytes < 1024 -> "$sizeBytes B"
			sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
			else -> String.format("%.1f MB", sizeBytes / 1024.0 / 1024.0)
		}

	val readableDate: String
		get() = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
			.format(Date(modifiedAt))
}

data class FilesUiState(
	/** Path being shown, relative to the workspace root. Empty is the root. */
	val currentPath: String = "",
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
)

data class FilePreview(
	val name: String,
	val relativePath: String,
	val text: String,
	val truncated: Boolean = false,
)

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
	private val repository: ConversationRepository,
	conversationId: String,
) : ViewModel() {

	private val conversation = conversationId

	private val _state = MutableStateFlow(FilesUiState())
	val state: StateFlow<FilesUiState> = _state.asStateFlow()

	init { refresh() }

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
		val entity = withContext(Dispatchers.IO) { repository.conversation(conversation)?.entity }
			?: return null
		return repository.workspace(entity)
	}

	fun refresh() {
		viewModelScope.launch {
			_state.value = _state.value.copy(loading = true, error = null)
			val ws = workspace()
			if (ws == null) {
				_state.value = _state.value.copy(loading = false, error = "conversation not found")
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
				val entries = if (dir == null || !dir.isDirectory) {
					null
				} else {
					dir.listFiles().orEmpty()
						.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
						.take(ws.limits.maxListEntries)
						.map { f ->
							FileEntryUi(
								name = f.name,
								relativePath = ws.relativize(f),
								isDirectory = f.isDirectory,
								sizeBytes = if (f.isDirectory) 0 else f.length(),
								modifiedAt = f.lastModified(),
								mimeType = mimeTypeOf(f),
							)
						}
				}
				entries to ws.usage()
			}
			val (entries, usage) = result
			_state.value = _state.value.copy(
				loading = false,
				entries = entries.orEmpty(),
				error = if (entries == null) "cannot list that directory" else null,
				usedBytes = usage.bytes,
				totalBytes = ws.limits.maxTotalBytes,
				fileCount = usage.files,
				maxFiles = ws.limits.maxFileCount,
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
				val text = withContext(Dispatchers.IO) {
					val f = (ws.resolve(path) as? SessionWorkspace.Resolution.Ok)?.file ?: return@withContext null
					if (f.length() > ws.limits.maxReadBytes) return@withContext null
					runCatching { f.readText() }.getOrNull()
				}
				_state.value = _state.value.copy(
					preview = if (text != null) {
						FilePreview(
							name = path.substringAfterLast('/'),
							relativePath = path,
							text = text,
						)
					} else {
						// Not text, or too big. Reported rather than opened as
						// mojibake, which looks like a corrupt file.
						FilePreview(
							name = path.substringAfterLast('/'),
							relativePath = path,
							text = "(binary or larger than the read limit — export it to open it)",
						)
					},
				)
			}
		}
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
		viewModelScope.launch {
			val ws = workspace() ?: return@launch
			withContext(Dispatchers.IO) {
				(ws.resolve(entry.relativePath) as? SessionWorkspace.Resolution.Ok)?.file
					?.deleteRecursively()
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
			_state.value = _state.value.copy(
				error = (outcome as? WorkspaceTransfer.Outcome.Failed)?.reason,
			)
			refresh()
		}
	}

	/**
	 * Write a workspace file out through a destination the caller opens.
	 *
	 * @return null on success, or a message to show.
	 */
	suspend fun exportFile(relativePath: String, open: () -> java.io.OutputStream): String? {
		val ws = workspace() ?: return "conversation not found"
		val outcome = withContext(Dispatchers.IO) {
			WorkspaceTransfer(ws).export(relativePath) { open() }
		}
		return (outcome as? WorkspaceTransfer.Outcome.Failed)?.reason
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
