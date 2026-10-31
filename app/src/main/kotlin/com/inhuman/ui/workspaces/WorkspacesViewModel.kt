package com.inhuman.ui.workspaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.WorkspaceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
		val targets = current.workspaces.filter { it.workspace.id in current.selectedIds }
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
