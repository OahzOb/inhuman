package com.inhuman.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.ConversationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class ConversationsUiState(
	val conversations: List<ConversationEntity> = emptyList(),
	val loaded: Boolean = false,
)

class ConversationsViewModel(
	private val repository: ConversationRepository,
) : ViewModel() {

	private val _state = MutableStateFlow(ConversationsUiState())
	val state: StateFlow<ConversationsUiState> = _state.asStateFlow()

	init {
		viewModelScope.launch {
			repository.observeAll().collect { list ->
				_state.value = ConversationsUiState(conversations = list, loaded = true)
			}
		}
	}

	/**
	 * Create a conversation and return its id.
	 *
	 * Returns the id rather than navigating itself: a view model that navigates
	 * cannot be tested without a navigation host, and the caller already knows
	 * where it wants to go.
	 */
	fun create(onCreated: (String) -> Unit) {
		viewModelScope.launch {
			val conversation = repository.create()
			onCreated(conversation.id)
		}
	}

	fun delete(conversation: ConversationEntity) {
		viewModelScope.launch { repository.delete(conversation) }
	}

	fun setPinned(conversation: ConversationEntity, pinned: Boolean) {
		viewModelScope.launch { repository.setPinned(conversation.id, pinned) }
	}
}
