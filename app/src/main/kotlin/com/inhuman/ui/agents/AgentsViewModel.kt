package com.inhuman.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.R
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.SavedPromptEntity
import com.inhuman.core.data.db.ToolsColumn
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.engine.roleplay.RoleplayCard
import com.inhuman.core.engine.roleplay.RoleplayCodec
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AgentsUiState(
	val agents: List<AgentEntity> = emptyList(),
	val providers: List<com.inhuman.core.data.db.ProviderProfileEntity> = emptyList(),
	val models: List<RemoteModel> = emptyList(),
	val loadingModels: Boolean = false,
	/** Non-null while the model list failed, so the picker can say why. */
	val modelsError: String? = null,
	/** Whether ssh_exec is currently part of the user-visible tool set. */
	val sshToolEnabled: Boolean = false,
	/** Whether the follow-up tool is available to pick at all. */
	val suggestionsEnabled: Boolean = false,
	/** Whether the anonymous Bilibili read tools are offered to pick at all. */
	val bilibiliEnabled: Boolean = false,
	/** Whether the URL-download tool is offered to pick at all. */
	val downloadEnabled: Boolean = false,
	/** Whether the optional Bilibili login is linked (`bili_transcript` needs it). */
	val bilibiliLoggedIn: Boolean = false,
	val editing: AgentDraft? = null,
	/** Non-null while the "export card to workspace" dialog is open. */
	val cardExport: CardExportDraft? = null,
	/** The user's saved system prompts, newest first. */
	val savedPrompts: List<SavedPromptEntity> = emptyList(),
)

/** A permanent workspace offered as an export destination. */
data class CardExportWorkspace(val id: String, val name: String)

/**
 * The "export this assistant's card to a workspace file" dialog.
 *
 * The card JSON is captured when the dialog opens, so the dialog exports
 * exactly the card the user was looking at -- including edits made in the card
 * editor and not yet saved, because the export action is offered from inside
 * that editor.
 */
data class CardExportDraft(
	val assistantName: String,
	val cardJson: String,
	val workspaces: List<CardExportWorkspace> = emptyList(),
	val selectedWorkspaceId: String? = null,
	val folderName: String = "",
	val busy: Boolean = false,
	val error: String? = null,
	/** Workspace-relative path once the write succeeded. */
	val savedPath: String? = null,
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
	/**
	 * The role-play character card, or a blank one for a plain assistant.
	 *
	 * Carried in the draft rather than read from the entity when saving, because
	 * the editor changes it in its own dialog and Cancel has to discard it. It
	 * is content, not capability, and [RoleplayCodec] encodes it only in
	 * [toEntity] -- an empty card becomes `""`, which is what turns role-play
	 * off for this assistant.
	 */
	val card: RoleplayCard = RoleplayCard(),
	/** Which provider profile the model id belongs to. */
	val providerId: String = com.inhuman.core.data.db.ProviderIds.DEEPSEEK,
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
	 * The suggestion model's profile, or null to inherit the assistant's.
	 *
	 * These four fields configure `suggest_replies` only. They are on the
	 * assistant, not on the workspace: the assistant already owns the model and
	 * the prompt, and a workspace can be shared by several conversations with
	 * different assistants, so "the workspace's model" has no single owner.
	 */
	val suggestionProviderId: String? = null,
	val suggestionModel: String? = null,
	val suggestionPrompt: String? = null,
	val suggestionFormat: String = "tool",
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
	/**
	 * A card-only character is legal.
	 *
	 * The card defines who the model is, and the assistant prompt is what to do
	 * in every request; a role-play assistant may legitimately carry no prompt
	 * at all. Requiring both made Save disabled with no way to explain that the
	 * card would have been enough.
	 */
	val canSave: Boolean
		get() = name.isNotBlank() && (systemPrompt.isNotBlank() || !card.isBlank)

	fun toEntity(now: Long) = AgentEntity(
		id = id,
		name = name.trim(),
		systemPrompt = systemPrompt.trim(),
		rpJson = RoleplayCodec.encodeCard(card),
		providerId = providerId,
		model = model,
		reasoningEffort = reasoningEffort,
		thinking = thinking,
		toolsJson = ToolsColumn.encode(tools),
		suggestionProviderId = suggestionProviderId,
		suggestionModel = suggestionModel,
		suggestionPrompt = suggestionPrompt?.takeIf { it.isNotBlank() },
		suggestionFormat = suggestionFormat,
		createdAt = createdAt,
		updatedAt = now,
		isDefault = isDefault,
	)
}

class AgentsViewModel(
	private val context: android.content.Context,
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
	private val secure: SecureStore,
) : ViewModel() {

	private val _state = MutableStateFlow(AgentsUiState())
	val state: StateFlow<AgentsUiState> = _state.asStateFlow()

	/**
	 * Re-read the global capability switches.
	 *
	 * Called when the screen is shown: Settings is a different screen, and the
	 * editor can outlive a change made there. All three switches decide which
	 * rows the editor offers, so they are read together rather than one at a
	 * time by three different callers.
	 */
	fun refreshGlobalSwitches() {
		_state.update {
			it.copy(
				sshToolEnabled = secure.get(SecureStore.KEY_SSH_TOOL_ENABLED) == "1",
				suggestionsEnabled = secure.get(SecureStore.KEY_SUGGESTIONS_ENABLED) == "1",
				bilibiliEnabled = secure.get(SecureStore.KEY_BILIBILI_TOOL_ENABLED) == "1",
				downloadEnabled = secure.get(SecureStore.KEY_DOWNLOAD_TOOL_ENABLED) == "1",
				bilibiliLoggedIn = com.inhuman.core.ai.bilibili.BilibiliCredentials.fromStoredJson(
					secure.get(SecureStore.KEY_BILIBILI_SESSION),
				) != null,
			)
		}
	}

	/**
	 * Save the editor's current prompt under a title.
	 *
	 * The same title updates its row rather than creating a near-duplicate; the
	 * repository owns that decision. Neither the title nor the text is logged:
	 * diagnostics deliberately do not record prompt content, and a title can
	 * reveal as much as the body.
	 */
	fun savePrompt(title: String, text: String) {
		viewModelScope.launch {
			runCatching { conversations.savedPrompts().save(title, text) }
				.onSuccess { id -> if (id != null) Diag.i("prompt", "saved chars=${text.length}") }
				.onFailure { Diag.w("prompt", "save failed", it) }
		}
	}

	fun deletePrompt(id: String) {
		viewModelScope.launch {
			runCatching { conversations.savedPrompts().delete(id) }
				.onSuccess { Diag.i("prompt", "deleted") }
				.onFailure { Diag.w("prompt", "delete failed", it) }
		}
	}

	init {
		refreshGlobalSwitches()
		viewModelScope.launch {
			repository.observeAll().collect { list ->
				_state.update { it.copy(agents = list) }
			}
		}
		viewModelScope.launch {
			repository.observeProviders().collect { list ->
				_state.update { it.copy(providers = list) }
			}
		}
		viewModelScope.launch {
			conversations.savedPrompts().observeAll().collect { list ->
				_state.update { it.copy(savedPrompts = list) }
			}
		}
		loadModels(
			providerId = com.inhuman.core.data.db.ProviderIds.DEEPSEEK,
		)
	}

	/**
	 * Fetch the model list.
	 *
	 * Failure is shown rather than hidden: with no key the picker cannot be
	 * populated at all, and a silently empty list looks like the app is broken
	 * rather than like it needs a key.
	 */
	fun loadModels(
		providerId: String = com.inhuman.core.data.db.ProviderIds.DEEPSEEK,
		force: Boolean = false,
	) {
		viewModelScope.launch {
			_state.update {
				it.copy(loadingModels = true, models = emptyList(), modelsError = null)
			}
			val result = repository.models(providerId = providerId, force = force)
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

	/** Changing provider invalidates the model: ids are provider-specific. */
	fun selectProvider(providerId: String) {
		updateDraft { draft ->
			draft.copy(providerId = providerId, model = null, reasoningEffort = null)
		}
		loadModels(providerId)
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
					card = RoleplayCodec.decodeCard(agent.rpJson) ?: RoleplayCard(),
					providerId = agent.providerId,
					model = agent.model,
					reasoningEffort = agent.reasoningEffort,
					thinking = agent.thinking,
					tools = agent.tools,
					suggestionProviderId = agent.suggestionProviderId,
					suggestionModel = agent.suggestionModel,
					suggestionPrompt = agent.suggestionPrompt,
					suggestionFormat = agent.suggestionFormat,
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
		loadModels(agent.providerId)
	}

	fun create() {
		val agent = repository.newAgent()
		// A name is required to save, and a blank Name field left Save looking
		// broken even after a prompt had been imported. Start with a usable
		// name; it stays editable like any other field.
		val existingNames = _state.value.agents.map { it.name }.toSet()
		var name = "New assistant"
		var suffix = 2
		while (name in existingNames) {
			name = "New assistant $suffix"
			suffix += 1
		}
		_state.update {
			it.copy(
				editing = AgentDraft(
					id = agent.id,
					isNew = true,
					isDefault = false,
					name = name,
					// Start from the same instructions as the default assistant.
					// A blank prompt left Save disabled with no explanation, which
					// read as a broken button; it also gave a new assistant no
					// description of the workspace until the user wrote one.
					systemPrompt = Database.DEFAULT_SYSTEM_PROMPT,
					providerId = com.inhuman.core.data.db.ProviderIds.DEEPSEEK,
					model = null,
					reasoningEffort = null,
					thinking = false,
					tools = AgentRepository.DEFAULT_NEW_AGENT_TOOLS,
					suggestionFormat = "tool",
					createdAt = agent.createdAt,
				),
			)
		}
	}

	fun closeEditor() = _state.update { it.copy(editing = null, cardExport = null) }

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

	/**
	 * Open the export dialog for the card currently shown in the editor.
	 *
	 * [card] is passed in rather than read from the draft because the card
	 * editor edits a local copy until Save; exporting must write the words the
	 * user is looking at, not the last saved version.
	 */
	fun openCardExport(card: RoleplayCard) {
		val draft = _state.value.editing ?: return
		// An assistant-sourced card has no `name` field -- the assistant's name
		// plays that role. The exported file is not owned by an assistant any
		// more, so it gets the name written into it; otherwise a scene started
		// from the file would fall back to whichever assistant happened to be
		// chosen and its title could change with an unrelated setting.
		val exported = if (card.name.isBlank()) {
			card.copy(name = draft.name.trim())
		} else {
			card
		}
		val json = RoleplayCodec.encodeCard(exported)
		if (json.isBlank()) return
		viewModelScope.launch {
			val workspaces = runCatching { conversations.workspaces() }
				.onFailure { Diag.w("agent", "export workspace list read failed", it) }
				.getOrDefault(emptyList())
				.filter { it.isPermanent }
				.map { CardExportWorkspace(it.id, it.name) }
			_state.update {
				it.copy(
					cardExport = CardExportDraft(
						assistantName = draft.name.trim().ifBlank { "character" },
						cardJson = json,
						workspaces = workspaces,
						selectedWorkspaceId = workspaces.firstOrNull()?.id,
						folderName = exportFolderName(draft.name),
					),
				)
			}
		}
	}

	fun updateCardExport(transform: (CardExportDraft) -> CardExportDraft) {
		_state.update { current ->
			current.cardExport?.let { current.copy(cardExport = transform(it)) } ?: current
		}
	}

	fun closeCardExport() {
		if (_state.value.cardExport?.busy == true) return
		_state.update { it.copy(cardExport = null) }
	}

	/**
	 * Write the captured card to `rp/<folder>/card.json` in a permanent
	 * workspace.
	 *
	 * The write goes through [WorkspaceTransfer] rather than a bare
	 * `File.writeText`: quota, per-file ceiling and the path fence are the same
	 * rules the user's own imports obey, and the workspace must already exist.
	 * An existing `card.json` is refused instead of overwritten -- an export
	 * from a different assistant must not silently replace a character file.
	 */
	fun exportCard() {
		val export = _state.value.cardExport ?: return
		if (export.busy || export.savedPath != null) return
		val workspaceId = export.selectedWorkspaceId
		if (workspaceId == null) {
			updateCardExport {
				it.copy(error = context.getString(R.string.roleplay_export_no_workspaces))
			}
			return
		}
		val folder = exportFolderName(export.folderName)
		if (!isValidExportFolder(folder)) {
			updateCardExport {
				it.copy(error = context.getString(R.string.roleplay_export_name_invalid))
			}
			return
		}
		updateCardExport { it.copy(busy = true, error = null) }
		viewModelScope.launch {
			val result = withContext(Dispatchers.IO) {
				runCatching {
					val workspace = conversations.workspace(workspaceId)
					val path = "${EXPORT_ROOT.trim('/')}/$folder/card.json"
					when (val resolved = workspace.resolve(path)) {
						is SessionWorkspace.Resolution.Rejected -> error(resolved.reason)
						is SessionWorkspace.Resolution.Ok ->
							if (resolved.file.exists()) {
								error(context.getString(R.string.roleplay_export_exists))
							}
					}
					val outcome = WorkspaceTransfer(workspace).import(
						destinationPath = path,
						maxBytes = null,
					) { export.cardJson.byteInputStream() }
					when (outcome) {
						is WorkspaceTransfer.Outcome.Ok -> outcome.relativePath
						is WorkspaceTransfer.Outcome.Failed -> error(outcome.reason)
					}
				}
			}
			_state.update { current ->
				val dialog = current.cardExport ?: return@update current
				result.fold(
					onSuccess = {
						current.copy(
							cardExport = dialog.copy(busy = false, savedPath = it, error = null),
						)
					},
					onFailure = { failure ->
						current.copy(
							cardExport = dialog.copy(
								busy = false,
								error = context.getString(
									R.string.roleplay_export_failed,
									failure.message ?: "unknown error",
								),
							),
						)
					},
				)
			}
			result.onSuccess { path ->
				Diag.i("agent", "card exported to=$path chars=${export.cardJson.length}")
			}
		}
	}

	/**
	 * A folder name that cannot escape the export root.
	 *
	 * Characters that are path separators or awkward in a filename become `-`;
	 * leading dots are stripped so the result cannot be `.` or `..` (and a
	 * hidden file is not what "export my character" means).
	 */
	private fun exportFolderName(raw: String): String = raw.trim()
		.replace(EXPORT_UNSAFE, "-")
		.trim()
		.trim('.', ' ', '-')
		.take(80)

	private fun isValidExportFolder(folder: String): Boolean =
		folder.isNotBlank() && folder != "." && folder != ".." && '/' !in folder && '\\' !in folder

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
		if (id == null) return context.getString(com.inhuman.R.string.agents_provider_default)
		val m = _state.value.models.firstOrNull { it.id == id }
		return m?.displayName?.takeIf { it.isNotBlank() } ?: id
	}

	private companion object {
		/** Story folders live under this root, the same convention `rp_format` publishes. */
		const val EXPORT_ROOT = "rp"

		/** Path separators and characters that make a file name awkward. */
		val EXPORT_UNSAFE = Regex("[\\\\/:*?\"<>|]")
	}
}
