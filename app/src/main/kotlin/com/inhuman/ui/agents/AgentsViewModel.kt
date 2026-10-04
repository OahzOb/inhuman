package com.inhuman.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ToolsColumn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentsUiState(
	val agents: List<AgentEntity> = emptyList(),
	val models: List<RemoteModel> = emptyList(),
	val loadingModels: Boolean = false,
	/** Non-null while the model list failed, so the picker can say why. */
	val modelsError: String? = null,
	val editing: AgentDraft? = null,
)

/**
 * An agent being edited.
 *
 * A separate type from [AgentEntity] because the two have different rules: a
 * draft may have a blank name while someone is typing, and saving it in that
 * state is what the validation is for. Editing the entity directly would mean
 * every keystroke writing a temporarily-invalid row.
 */
data class AgentDraft(
	val id: String,
	val isNew: Boolean,
	val isDefault: Boolean,
	val name: String,
	val systemPrompt: String,
	val model: String?,
	val reasoningEffort: String?,
	val thinking: Boolean,
	val createdAt: Long,
	/**
	 * Tools this assistant may use. Null means every tool.
	 *
	 * Null and empty are both reachable and mean different things: "everything,
	 * including whatever is added later" against "nothing". The picker offers
	 * both, labelled, because a chat-only assistant is a reasonable thing to
	 * want and so is one that keeps up with new capabilities automatically.
	 */
	val tools: List<String>? = null,
) {
	val canSave: Boolean get() = name.isNotBlank() && systemPrompt.isNotBlank()

	fun toEntity(now: Long) = AgentEntity(
		id = id,
		name = name.trim(),
		systemPrompt = systemPrompt.trim(),
		model = model,
		reasoningEffort = reasoningEffort,
		thinking = thinking,
		toolsJson = ToolsColumn.encode(tools),
		createdAt = createdAt,
		updatedAt = now,
		isDefault = isDefault,
	)
}

class AgentsViewModel(
	private val repository: AgentRepository,
) : ViewModel() {

	private val _state = MutableStateFlow(AgentsUiState())
	val state: StateFlow<AgentsUiState> = _state.asStateFlow()

	init {
		viewModelScope.launch {
			repository.observeAll().collect { list ->
				_state.update { it.copy(agents = list) }
			}
		}
		loadModels()
	}

	/**
	 * Fetch the model list.
	 *
	 * Failure is shown rather than hidden: with no key the picker cannot be
	 * populated at all, and a silently empty list looks like the app is broken
	 * rather than like it needs a key.
	 */
	fun loadModels(force: Boolean = false) {
		viewModelScope.launch {
			_state.update { it.copy(loadingModels = true, modelsError = null) }
			val result = repository.models(force)
			_state.update {
				it.copy(
					loadingModels = false,
					models = result.getOrNull().orEmpty(),
					modelsError = result.exceptionOrNull()?.message,
				)
			}
		}
	}

	fun edit(agent: AgentEntity) {
		_state.update {
			it.copy(
				editing = AgentDraft(
					id = agent.id,
					isNew = false,
					isDefault = agent.isDefault,
					name = agent.name,
					systemPrompt = agent.systemPrompt,
					model = agent.model,
					reasoningEffort = agent.reasoningEffort,
					thinking = agent.thinking,
					tools = agent.tools,
					createdAt = agent.createdAt,
				),
			)
		}
	}

	fun create() {
		val agent = repository.newAgent()
		_state.update {
			it.copy(
				editing = AgentDraft(
					id = agent.id,
					isNew = true,
					isDefault = false,
					name = "",
					systemPrompt = "",
					model = null,
					reasoningEffort = null,
					thinking = false,
					tools = AgentRepository.DEFAULT_NEW_AGENT_TOOLS,
					createdAt = agent.createdAt,
				),
			)
		}
	}

	fun closeEditor() = _state.update { it.copy(editing = null) }

	fun updateDraft(transform: (AgentDraft) -> AgentDraft) {
		_state.update { s -> s.editing?.let { s.copy(editing = transform(it)) } ?: s }
	}

	fun save() {
		val draft = _state.value.editing ?: return
		if (!draft.canSave) return
		viewModelScope.launch {
			repository.save(draft.toEntity(System.currentTimeMillis()))
			_state.update { it.copy(editing = null) }
		}
	}

	fun delete(agent: AgentEntity) {
		viewModelScope.launch { repository.delete(agent) }
	}

	/** Effort levels for a model, as the provider reports them. */
	fun effortLevelsFor(modelId: String?): List<String> =
		_state.value.models.firstOrNull { it.id == modelId }?.supportedEffortLevels.orEmpty()

	fun modelDisplayName(id: String?): String {
		if (id == null) return "provider default"
		val m = _state.value.models.firstOrNull { it.id == id }
		return m?.displayName?.takeIf { it.isNotBlank() } ?: id
	}
}
