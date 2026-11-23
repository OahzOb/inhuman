package com.inhuman.ui.workspaces

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.R
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.SessionMode
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceZip
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
	private val context: Context,
	private val repository: ConversationRepository,
	private val agents: AgentRepository,
	private val workspaceId: String,
	/** The saved default for new conversations; this path has no Create form. */
	private val secure: SecureStore,
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

	/**
	 * Write this workspace to a user-chosen zip.
	 *
	 * Available here as well as in the chat menu because a permanent workspace
	 * can exist with no conversation at all; without this entry point such a
	 * workspace would have no way to leave the device. The zip is the files
	 * only, not the database or an encrypted backup.
	 */
	fun exportZip(uri: Uri, onResult: (WorkspaceZip.Result?, String?) -> Unit) {
		viewModelScope.launch {
			val result = withContext(Dispatchers.IO) {
				runCatching {
					val out = context.contentResolver.openOutputStream(uri)
						?: error(context.getString(R.string.export_error_destination))
					out.use { WorkspaceZip.write(repository.workspace(workspaceId), it) }
				}
			}
			val failure = result.exceptionOrNull()
			onResult(
				result.getOrNull(),
				failure?.let {
					it.message?.takeIf { message -> message.isNotBlank() }
						?: context.getString(R.string.export_error_unknown)
				},
			)
		}
	}

	fun startNewConversation(onCreated: (String) -> Unit = {}) {
		viewModelScope.launch {
			val available = agents.all()
			when {
				available.isEmpty() -> create(null, onCreated)
				available.size == 1 -> create(available.first(), onCreated)
				else -> _state.update { it.copy(choosingAgent = available) }
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
			runCatching {
				repository.create(
					title = context.getString(R.string.chat_title_fallback),
					agent = agent,
					workspaceId = workspaceId,
					sessionMode = if (secure.newConversationContinuous()) {
						SessionMode.CONTINUOUS
					} else {
						SessionMode.CLASSIC
					},
				)
			}
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
					it.copy(error = context.getString(R.string.workspace_error_delete_conversations_first))
				}
			}
		}
	}

	fun clearError() = _state.update { it.copy(error = null) }
}
