package com.inhuman.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationsUiState(
	val conversations: List<ConversationEntity> = emptyList(),
	/** Names by agent id, so a row can say which assistant it belongs to. */
	val agentNames: Map<String, String> = emptyMap(),
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
		viewModelScope.launch {
			val conversation = repository.create(agent = agent)
			onCreated?.invoke(conversation.id)
		}
	}

	fun delete(conversation: ConversationEntity) {
		viewModelScope.launch { repository.delete(conversation) }
	}

	fun setPinned(conversation: ConversationEntity, pinned: Boolean) {
		viewModelScope.launch { repository.setPinned(conversation.id, pinned) }
	}
}
