package com.inhuman.ui.workspaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.core.files.SessionWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WorkspaceDetailUiState(
	val workspace: WorkspaceEntity? = null,
	val conversations: List<ConversationEntity> = emptyList(),
	val usage: SessionWorkspace.Usage = SessionWorkspace.Usage(0, 0),
	/** Absolute app-private directory; shown to the user, never sent to the model. */
	val absolutePath: String = "",
	val choosingAgent: List<AgentEntity>? = null,
	val loading: Boolean = true,
	val error: String? = null,
	val deleting: Boolean = false,
)

/** One permanent workspace: its files, its sessions, and its lifetime. */
class WorkspaceDetailViewModel(
	private val repository: ConversationRepository,
	private val agents: AgentRepository,
	private val workspaceId: String,
) : ViewModel() {

	private val _state = MutableStateFlow(WorkspaceDetailUiState())
	val state: StateFlow<WorkspaceDetailUiState> = _state.asStateFlow()

	init {
		_state.update {
			it.copy(absolutePath = repository.workspacePath(workspaceId))
		}
		viewModelScope.launch {
			combine(
				repository.observeWorkspace(workspaceId),
				repository.observeWorkspaceConversations(workspaceId),
			) { workspace, conversations -> workspace to conversations }
				.collect { (workspace, conversations) ->
					val usage = withContext(Dispatchers.IO) {
						runCatching { repository.workspace(workspaceId).usage() }
							.getOrDefault(SessionWorkspace.Usage(0, 0))
					}
					_state.update {
						it.copy(
							workspace = workspace,
							conversations = conversations,
							usage = usage,
							absolutePath = repository.workspacePath(workspaceId),
							loading = false,
						)
					}
				}
		}
	}

	fun refreshUsage() {
		viewModelScope.launch {
			val usage = withContext(Dispatchers.IO) {
				runCatching { repository.workspace(workspaceId).usage() }
					.getOrDefault(SessionWorkspace.Usage(0, 0))
			}
			_state.update { it.copy(usage = usage) }
		}
	}

	fun startNewConversation() {
		viewModelScope.launch {
			val available = agents.all()
			if (available.isEmpty()) {
				create(null)
			} else {
				_state.update { it.copy(choosingAgent = available) }
			}
		}
	}

	fun cancelChoosingAgent() = _state.update { it.copy(choosingAgent = null) }

	fun chooseAgent(agent: AgentEntity?, onCreated: (String) -> Unit) {
		_state.update { it.copy(choosingAgent = null) }
		create(agent, onCreated)
	}

	private fun create(agent: AgentEntity?, onCreated: ((String) -> Unit)? = null) {
		viewModelScope.launch {
			runCatching { repository.create(agent = agent, workspaceId = workspaceId) }
				.onSuccess { onCreated?.invoke(it.id) }
				.onFailure { failure ->
					_state.update {
						it.copy(error = failure.message ?: "Could not create conversation.")
					}
				}
		}
	}

	fun rename(name: String) {
		val workspace = _state.value.workspace ?: return
		viewModelScope.launch {
			runCatching { repository.renameWorkspace(workspace, name) }
				.onFailure { failure ->
					_state.update { it.copy(error = failure.message ?: "Could not rename workspace.") }
				}
		}
	}

	fun delete(onDeleted: () -> Unit) {
		val workspace = _state.value.workspace ?: return
		viewModelScope.launch {
			_state.update { it.copy(deleting = true) }
			val deleted = runCatching { repository.deleteWorkspace(workspace) }
				.getOrDefault(false)
			_state.update { it.copy(deleting = false) }
			if (deleted) {
				onDeleted()
			} else {
				_state.update {
					it.copy(error = "This workspace still has conversations. Delete them first.")
				}
			}
		}
	}

	fun clearError() = _state.update { it.copy(error = null) }
}
