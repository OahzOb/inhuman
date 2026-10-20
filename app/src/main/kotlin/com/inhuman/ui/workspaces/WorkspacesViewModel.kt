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
					_state.update { it.copy(workspaces = rows, loading = false) }
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
}
