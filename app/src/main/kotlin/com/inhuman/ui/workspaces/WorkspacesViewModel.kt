package com.inhuman.ui.workspaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.core.files.WorkspaceZip
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WorkspaceRowUi(
	val workspace: WorkspaceEntity,
	val conversationCount: Int,
)

data class WorkspacesUiState(
	val workspaces: List<WorkspaceRowUi> = emptyList(),
	val loading: Boolean = true,
	val error: String? = null,
	val selecting: Boolean = false,
	val selectedIds: Set<String> = emptySet(),
	val deletingSelection: Boolean = false,
)

/** The Workspaces list: permanent workspaces only. Temporary ones belong to a conversation. */
class WorkspacesViewModel(
	private val repository: ConversationRepository,
) : ViewModel() {

	private val _state = MutableStateFlow(WorkspacesUiState())
	val state: StateFlow<WorkspacesUiState> = _state.asStateFlow()

	init {
		viewModelScope.launch {
			combine(
				repository.observePermanentWorkspaces(),
				repository.observeAll(),
			) { workspaces, conversations -> workspaces to conversations }
				.collect { (workspaces, conversations) ->
					val counts = conversations.groupingBy { it.workspaceId }.eachCount()
					val rows = workspaces.map { workspace ->
						WorkspaceRowUi(
							workspace = workspace,
							conversationCount = counts[workspace.id] ?: 0,
						)
					}
					val ids = rows.mapTo(mutableSetOf()) { it.workspace.id }
					_state.update { current ->
						current.copy(
							workspaces = rows,
							loading = false,
							selectedIds = current.selectedIds.intersect(ids),
							// Same rule as the conversation list: an emission with
							// no selection yet must not dismiss the checkboxes.
							selecting = current.selecting &&
								(current.selectedIds.isEmpty() || current.selectedIds.any { it in ids }),
						)
					}
				}
		}
	}

	fun create(name: String, onCreated: (String) -> Unit) {
		viewModelScope.launch {
			runCatching { repository.createWorkspace(name) }
				.onSuccess { onCreated(it.id) }
				.onFailure { failure ->
					_state.update { it.copy(error = failure.message ?: "Could not create workspace.") }
				}
		}
	}

	/**
	 * Import a plain workspace zip into a new permanent workspace.
	 *
	 * A new row rather than a merge: a permanent workspace may already be open
	 * and shared, and a zip import that renames or overwrites its files would be
	 * a destructive act hiding behind a "restore" label. The workspace row is
	 * created first so the sweep cannot treat the directory as an orphan; if the
	 * archive yields nothing, the row is removed again rather than leaving an
	 * empty workspace the user did not ask for.
	 */
	fun importWorkspaceZip(
		open: () -> InputStream,
		displayName: String,
		onResult: (String?, String?) -> Unit,
	) {
		viewModelScope.launch {
			val outcome = withContext(Dispatchers.IO) {
				runCatching {
					val created = repository.createWorkspace(
						displayName.trim().ifBlank { "Imported workspace" },
					)
					val workspace = repository.workspace(created.id)
					val imported = open().use { WorkspaceZip.importInto(workspace, it) }
					if (imported.failed) {
						// A partially imported workspace is kept and disclosed by the
						// error: deleting it would throw away the files that did
						// arrive, while reporting success would make a truncated
						// import look complete. An import that wrote nothing has no
						// reason to leave an empty row behind.
						if (imported.files == 0) {
							runCatching { repository.deleteWorkspaces(listOf(created)) }
						}
						error(imported.failure ?: "Could not import the workspace.")
					}
					created.id
				}
			}
			outcome.onSuccess { onResult(it, null) }
				.onFailure { failure ->
					_state.update {
						it.copy(error = failure.message ?: "Could not import the workspace.")
					}
					onResult(null, failure.message ?: "Could not import the workspace.")
				}
		}
	}

	fun clearError() = _state.update { it.copy(error = null) }

	fun beginSelection(id: String? = null) {
		_state.update { current ->
			current.copy(
				selecting = true,
				selectedIds = if (id == null) current.selectedIds else current.selectedIds + id,
			)
		}
	}

	fun toggleSelection(id: String) {
		_state.update { current ->
			val next = if (id in current.selectedIds) {
				current.selectedIds - id
			} else {
				current.selectedIds + id
			}
			current.copy(selectedIds = next, selecting = true)
		}
	}

	fun cancelSelection() {
		_state.update { it.copy(selecting = false, selectedIds = emptySet()) }
	}

	fun deleteSelected() {
		val current = _state.value
		if (current.deletingSelection || current.selectedIds.isEmpty()) return
		val targets = current.workspaces
			.filter { it.workspace.id in current.selectedIds }
			// The built-in workshop is app-owned and never deletable, even if a
			// stale selection names it: the list UI has no checkbox for it.
			.filter { it.workspace.kind == com.inhuman.core.data.db.WorkspaceKinds.NONE }
		if (targets.isEmpty()) {
			cancelSelection()
			return
		}
		_state.update { it.copy(deletingSelection = true) }
		viewModelScope.launch {
			val deleted = runCatching {
				repository.deleteWorkspaces(targets.map { it.workspace })
			}.onFailure { failure ->
				_state.update { it.copy(error = failure.message ?: "Could not delete workspaces.") }
			}.getOrNull()
			_state.update {
				it.copy(
					deletingSelection = false,
					selecting = false,
					selectedIds = emptySet(),
					error = if (deleted == null) {
						it.error
					} else if (deleted.workspaces != targets.size) {
						"Deleted ${deleted.workspaces} of ${targets.size} workspaces. " +
							"The rest could not be removed."
					} else {
						null
					},
				)
			}
		}
	}
}
