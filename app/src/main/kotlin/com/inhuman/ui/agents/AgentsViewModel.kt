package com.inhuman.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
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
	/**
	 * What this assistant may do without asking, by tool name.
	 *
	 * Carried in the draft like [tools], and for the same reason: it belongs to
	 * the assistant, so it is edited where the assistant is edited rather than in
	 * a separate screen that would need its own picker to say *which* assistant.
	 *
	 * Absent means [ToolPolicy.Free], so this is empty for an assistant with no
	 * rules. A rule can only ever tighten what the global setting allows --
	 * `PolicySet` composes the two by taking the strictest -- so this screen
	 * cannot be used to grant something settings has refused.
	 */
	val rules: Map<String, ToolPolicy> = emptyMap(),
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
	/**
	 * Only for the approval rules, which are keyed by assistant.
	 *
	 * A second repository rather than a method on `AgentRepository` because the
	 * rules and the assistant are different tables that happen to share a key --
	 * and because `AgentRepository` is about the provider's model list as much as
	 * about the assistant row.
	 */
	private val conversations: ConversationRepository,
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
			result.exceptionOrNull()?.let {
				Diag.w("models", "agents fetch failed: ${it.javaClass.simpleName}: ${it.message}")
			}
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
		// Loaded after the draft is shown rather than before it, so the editor
		// opens immediately and the rules fill in. A blank rules map renders as
		// "no rules", which is the default and therefore not a wrong answer in
		// the meantime -- unlike an empty tools list, which would read as "none".
		viewModelScope.launch {
			val rules = runCatching {
				conversations.policies().forConversation(conversationId = "", agentId = agent.id).assistant
			}.getOrElse {
				Diag.w("policy", "assistant rules read failed agent=${Diag.shortId(agent.id)}", it)
				emptyMap()
			}
			updateDraft { it.copy(rules = rules) }
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
		Diag.i(
			"agent",
			"save id=${Diag.shortId(draft.id)} new=${draft.isNew} " +
				"tools=${draft.tools?.size ?: "all"} rules=${draft.rules.size}",
		)
		viewModelScope.launch {
			repository.save(draft.toEntity(System.currentTimeMillis()))
			// Written after the assistant row exists, because `agent_approval`
			// has no foreign key but a rule for an assistant that was never
			// created would be invisible and impossible to remove.
			//
			// `Free` is written as a *deletion* inside `setAssistantRule`, so a
			// rule the user cleared does not linger as a row saying "no
			// restriction" -- see the note on `PolicyDao.clearAgentRule`.
			for (entry in ToolCatalog.all) {
				val policy = draft.rules[entry.name] ?: ToolPolicy.Free
				runCatching { conversations.policies().setAssistantRule(draft.id, entry.name, policy) }
					.onFailure {
						Diag.w(
							"policy",
							"assistant rule write failed agent=${Diag.shortId(draft.id)} " +
								"tool=${entry.name} policy=$policy",
							it,
						)
					}
			}
			_state.update { it.copy(editing = null) }
			Diag.i("agent", "saved id=${Diag.shortId(draft.id)}")
		}
	}

	/** Set one rule in the draft; nothing is written until [save]. */
	fun setDraftRule(toolName: String, policy: ToolPolicy) {
		updateDraft { draft ->
			draft.copy(
				rules = if (policy == ToolPolicy.Free) {
					draft.rules - toolName
				} else {
					draft.rules + (toolName to policy)
				},
			)
		}
	}

	fun delete(agent: AgentEntity) {
		Diag.i("agent", "delete id=${Diag.shortId(agent.id)}")
		viewModelScope.launch {
			repository.delete(agent)
			Diag.i("agent", "deleted id=${Diag.shortId(agent.id)}")
		}
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
