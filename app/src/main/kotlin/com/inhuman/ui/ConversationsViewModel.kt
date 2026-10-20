package com.inhuman.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class ConversationsUiState(
	val conversations: List<ConversationEntity> = emptyList(),
	/** Names by agent id, so a row can say which assistant it belongs to. */
	val agentNames: Map<String, String> = emptyMap(),
	/** Workspace display names and lifetime, for delete confirmations. */
	val workspaceNames: Map<String, String> = emptyMap(),
	val permanentWorkspaceIds: Set<String> = emptySet(),
	val loaded: Boolean = false,
	/**
	 * Assistants to choose from, non-null only while the picker is open.
	 *
	 * Held as state rather than as a separate screen because choosing is a
	 * single decision before anything exists to show -- a conversation has to
	 * have an assistant before it has a transcript, so the choice comes first
	 * and there is nothing to navigate away from yet.
	 */
	val choosingAgent: List<AgentEntity>? = null,
	/**
	 * File counts for conversations whose deletion is being confirmed.
	 *
	 * Fetched before the question is asked, because the number is part of the
	 * question: "delete this conversation?" and "delete this conversation and
	 * the 14 files in it?" are different decisions, and only one of them is
	 * informed. Counting after the answer would be counting a folder that no
	 * longer exists.
	 */
	val deletePreviewCounts: Map<String, Int> = emptyMap(),
)

class ConversationsViewModel(
	private val repository: ConversationRepository,
	private val agents: AgentRepository,
) : ViewModel() {

	private val _state = MutableStateFlow(ConversationsUiState())
	val state: StateFlow<ConversationsUiState> = _state.asStateFlow()

	init {
		viewModelScope.launch {
			repository.observeAll().collect { list ->
				_state.update { it.copy(conversations = list, loaded = true) }
			}
		}
		viewModelScope.launch {
			agents.observeAll().collect { list ->
				_state.update { it.copy(agentNames = list.associate { a -> a.id to a.name }) }
			}
		}
		viewModelScope.launch {
			repository.observeWorkspaces().collect { list ->
				_state.update {
					it.copy(
						workspaceNames = list.associate { w -> w.id to w.name },
						permanentWorkspaceIds = list.filter { w -> w.isPermanent }
							.mapTo(mutableSetOf()) { w -> w.id },
					)
				}
			}
		}
	}

	/**
	 * Begin creating a conversation.
	 *
	 * Opens the assistant picker rather than creating immediately. Which
	 * assistant is the first real decision about a conversation and the only one
	 * that cannot be changed afterwards without losing the instructions the
	 * transcript was produced under.
	 */
	fun startNewConversation() {
		viewModelScope.launch {
			val available = agents.all()
			if (available.isEmpty()) {
				// Nothing to choose from: create with no assistant rather than
				// blocking. The default is seeded on database creation, so this
				// is only reachable if something went wrong there.
				create(null)
			} else {
				_state.update { it.copy(choosingAgent = available) }
			}
		}
	}

	fun cancelChoosing() = _state.update { it.copy(choosingAgent = null) }

	/** Create with the chosen assistant and hand back the new id. */
	fun chooseAgent(agent: AgentEntity?, onCreated: (String) -> Unit) {
		_state.update { it.copy(choosingAgent = null) }
		create(agent, onCreated)
	}

	private fun create(agent: AgentEntity?, onCreated: ((String) -> Unit)? = null) {
		Diag.i("conv", "create agent=${Diag.shortId(agent?.id)}")
		viewModelScope.launch {
			val conversation = repository.create(agent = agent)
			Diag.i(
				"conv",
				"created id=${Diag.shortId(conversation.id)} " +
					"agent=${Diag.shortId(conversation.agentId)}",
			)
			onCreated?.invoke(conversation.id)
		}
	}

	/**
	 * Count the files in a conversation's workspace, for the confirmation.
	 *
	 * Runs off the main thread: the count walks the directory, and a workspace
	 * that the model has been filling can hold thousands of entries.
	 */
	fun countFiles(conversation: ConversationEntity) {
		viewModelScope.launch {
			val count = withContext(Dispatchers.IO) {
				val workspace = runCatching { repository.workspace(conversation) }
					.onFailure {
						Diag.w(
							"workspace",
							"delete preview: workspace did not open " +
								"conv=${Diag.shortId(conversation.id)}",
							it,
						)
					}
					.getOrNull() ?: return@withContext null
				runCatching { workspace.fileCount() }
					.onFailure {
						Diag.w(
							"workspace",
							"delete preview: count failed conv=${Diag.shortId(conversation.id)}",
							it,
						)
					}
					.getOrNull()
			}
			// A null count means the workspace could not be read. Left absent so
			// the dialog says less rather than claiming zero files in a folder
			// that might be full.
			if (count != null) {
				_state.update { it.copy(deletePreviewCounts = it.deletePreviewCounts + (conversation.id to count)) }
			}
		}
	}

	fun cancelDelete() {
		// The count is dropped on cancel so a later confirmation cannot show a
		// stale number for a folder that has changed in between.
		_state.update { it.copy(deletePreviewCounts = emptyMap()) }
	}

	/**
	 * Delete a conversation, its messages, and—when its workspace is
	 * temporary—the workspace and files. A permanent workspace is left intact.
	 *
	 * The count is cleared first: if the deletion fails, the next confirmation
	 * should recount rather than reuse a number that was true a minute ago.
	 */
	fun delete(conversation: ConversationEntity) {
		Diag.i("conv", "delete id=${Diag.shortId(conversation.id)}")
		_state.update { it.copy(deletePreviewCounts = emptyMap()) }
		viewModelScope.launch {
			repository.delete(conversation)
			Diag.i("conv", "deleted id=${Diag.shortId(conversation.id)}")
		}
	}

	fun setPinned(conversation: ConversationEntity, pinned: Boolean) {
		Diag.d("conv", "pin id=${Diag.shortId(conversation.id)} pinned=$pinned")
		viewModelScope.launch { repository.setPinned(conversation.id, pinned) }
	}
}
