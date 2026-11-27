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
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.AppCapabilities
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.ui.chat.AssistantEditRequest
import com.inhuman.ui.chat.MediaProviderFacts
import com.inhuman.ui.chat.MediaToolStore
import com.inhuman.ui.chat.mediaConfiguredToolNames
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
import java.io.File
import java.nio.file.Files

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
	/** Whether the app's own (keyless) web tools are switched on globally. */
	val localSearchEnabled: Boolean = false,
	val localFetchEnabled: Boolean = false,
	/** Whether the URL-download tool is offered to pick at all. */
	val downloadEnabled: Boolean = false,
	/** Whether the optional Bilibili login is linked (`bili_transcript` needs it). */
	val bilibiliLoggedIn: Boolean = false,
	/**
	 * Media tool names whose backend resolves for the assistant being edited.
	 *
	 * Null before the first resolution, which the tool list treats as "do not
	 * filter". Absent rather than greyed is the rule (`docs/07` B46): a
	 * capability whose backend cannot resolve is a row that does nothing.
	 */
	val mediaConfiguredTools: Set<String>? = null,
	val editing: AgentDraft? = null,
	/**
	 * A save or delete that failed, for the dismissible row in the list and
	 * the same message above the editor's first field. `null` while nothing
	 * failed; a successful save clears it.
	 */
	val error: String? = null,
	/** Non-null while the "export card to workspace" dialog is open. */
	val cardExport: CardExportDraft? = null,
	/** Non-null while the "import prompt from a workspace file" dialog is open. */
	val promptImport: PromptImportDraft? = null,
	/** A file the user picked in the workspace importer, consumed once by the screen. */
	val promptImportPicked: PromptImportPicked? = null,
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

/** A workspace offered by the prompt importer. */
data class PromptImportWorkspace(val id: String, val name: String, val permanent: Boolean)

/** One row in the prompt importer's file browser. */
data class PromptImportEntry(val name: String, val isDirectory: Boolean)

/**
 * The "import a system prompt from a workspace file" dialog.
 *
 * A workspace is a normal place for the user to keep a prompt they want to
 * reuse, so this is an import source next to the device file picker -- not a new
 * read path for an assistant. The browser resolves every path through the
 * workspace's own [SessionWorkspace], exactly as Files does; it never touches a
 * path outside the selected workspace.
 */
data class PromptImportDraft(
	val workspaces: List<PromptImportWorkspace> = emptyList(),
	val selectedWorkspaceId: String? = null,
	val selectedWorkspaceName: String = "",
	val directory: String = "",
	val entries: List<PromptImportEntry> = emptyList(),
	val loading: Boolean = false,
	val error: String? = null,
)

/** A workspace file the user chose, handed to the screen once. */
data class PromptImportPicked(val name: String, val text: String)

/** The file browser's row cap; the same order Files shows, but a prompt picker
 * does not need thousands of rows to be useful. */
private const val PROMPT_IMPORT_LIST_LIMIT = 500

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
				localSearchEnabled = secure.get(SecureStore.KEY_WEB_LOCAL_SEARCH_ENABLED) == "1",
				localFetchEnabled = secure.get(SecureStore.KEY_WEB_LOCAL_FETCH_ENABLED) == "1",
				downloadEnabled = secure.get(SecureStore.KEY_DOWNLOAD_TOOL_ENABLED) == "1",
				bilibiliLoggedIn = com.inhuman.core.ai.bilibili.BilibiliCredentials.fromStoredJson(
					secure.get(SecureStore.KEY_BILIBILI_SESSION),
				) != null,
			)
		}
		// A key or media backend added in Settings is picked up the next
		// time the editor opens.
		_state.value.editing?.providerId?.let { refreshMediaConfiguredTools(it) }
	}

	/**
	 * Recompute which media tools the editor may offer for [providerId].
	 *
	 * The same predicate the request builder uses, so the list and the wire
	 * cannot disagree. A failed read keeps the previous answer rather than
	 * hiding configured tools; the list only has to be right, not instant.
	 */
	private fun refreshMediaConfiguredTools(providerId: String) {
		viewModelScope.launch {
			val tools = runCatching {
				val assistantProfile = repository.provider(providerId)
				mediaConfiguredToolNames(
					configFor = { MediaToolStore.load(secure, it) },
					workspaceAvailable = AppCapabilities.workspaceOk,
					assistantFacts = {
						assistantProfile?.let { profile ->
							MediaProviderFacts(profile.kind, repository.hasKey(profile.id), profile.baseUrl)
						}
					},
					profileFacts = { id ->
						repository.provider(id)?.let { profile ->
							MediaProviderFacts(profile.kind, repository.hasKey(profile.id), profile.baseUrl)
						}
					},
				)
			}.onFailure {
				Diag.w("media", "availability read failed provider=${Diag.shortId(providerId)}", it)
			}.getOrNull() ?: return@launch
			_state.update { it.copy(mediaConfiguredTools = tools) }
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
		refreshMediaConfiguredTools(providerId)
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
		refreshMediaConfiguredTools(agent.providerId)
	}

	fun create() {
		val agent = repository.newAgent()
		// A name is required to save, and a blank Name field left Save looking
		// broken even after a prompt had been imported. Start with a usable
		// name; it stays editable like any other field.
		val existingNames = _state.value.agents.map { it.name }.toSet()
		val baseName = context.getString(R.string.agents_default_name)
		var name = baseName
		var suffix = 2
		while (name in existingNames) {
			name = "$baseName $suffix"
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
		refreshMediaConfiguredTools(agent.providerId)
	}

	/**
	 * Open the editor with a configuration-workshop draft.
	 *
	 * The draft carries content only -- name, system prompt, character card. An
	 * update starts from the existing row and applies only the fields the
	 * proposal actually changed, so the user reviews the difference beside their
	 * own tools, model and approval rules; a create starts from the same defaults
	 * as **New assistant**. Either way nothing is written here: [save] is still
	 * the only writer, and the proposal cannot name a field it has no business
	 * setting.
	 */
	fun openProposal(request: AssistantEditRequest) {
		val agentId = request.agentId
		if (agentId == null) {
			create()
			applyProposalContent(request)
			return
		}
		viewModelScope.launch {
			val agent = runCatching { repository.byId(agentId) }.getOrNull()
			if (agent == null) {
				// The row was deleted between the proposal and the tap. Say so
				// instead of opening a blank editor that would create a new
				// assistant under the same name.
				_state.update { it.copy(error = context.getString(R.string.agents_proposal_missing)) }
				return@launch
			}
			// `edit` opens the editor immediately and loads the approval rules and
			// model list afterwards; applying the draft right after it keeps that
			// behaviour and leaves those loads in flight.
			edit(agent)
			applyProposalContent(request)
		}
	}

	/** Apply only the content fields the draft changed; null leaves a field alone. */
	private fun applyProposalContent(request: AssistantEditRequest) {
		updateDraft { draft ->
			draft.copy(
				name = request.name ?: draft.name,
				systemPrompt = request.systemPrompt ?: draft.systemPrompt,
				// An empty card is a deliberate clear: `decodeCard("")` is null and
				// the blank [RoleplayCard] is what the editor writes as "no card".
				card = request.cardJson?.let { RoleplayCodec.decodeCard(it) ?: RoleplayCard() }
					?: draft.card,
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
			// A save that fails keeps the editor open with the error in it.
			// Closing anyway would throw away the prompt, the tool list and
			// the rules -- the whole reason someone opened the editor -- and
			// report nothing. Same shape as `startScene` on the home screen.
			runCatching { repository.save(draft.toEntity(System.currentTimeMillis())) }
				.onFailure { failure ->
					Diag.w(
						"agent",
						"save failed id=${Diag.shortId(draft.id)} new=${draft.isNew}",
						failure,
					)
					_state.update {
						it.copy(
							error = context.getString(
								R.string.agents_save_failed,
								failure.message ?: failure::class.simpleName ?: "unknown error",
							),
						)
					}
					return@launch
				}
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
			_state.update { it.copy(editing = null, error = null) }
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
						assistantName = draft.name.trim()
							.ifBlank { context.getString(R.string.roleplay_character_fallback) },
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

	// --- importing a prompt from a workspace file --------------------------

	/**
	 * Open the workspace prompt importer.
	 *
	 * Workspaces are listed with the permanent ones first (the same order Files
	 * uses for its cross-workspace copy); the user then browses one workspace
	 * and picks a text file. The file is read through that workspace's
	 * [SessionWorkspace], so the same path fence and read guard apply as
	 * everywhere else. A temporary workspace is included on purpose: a prompt
	 * may have been written in a conversation that is still open.
	 */
	fun openPromptImport() {
		_state.update {
			it.copy(
				promptImport = PromptImportDraft(loading = true),
				promptImportPicked = null,
			)
		}
		viewModelScope.launch {
			val workspaces = withContext(Dispatchers.IO) {
				runCatching { conversations.workspaces() }
					.onFailure { Diag.w("agent", "prompt import workspace list failed", it) }
					.getOrDefault(emptyList())
			}.sortedWith(
				compareByDescending<WorkspaceEntity> { it.isPermanent }
					.thenBy { it.name.lowercase() },
			).map { PromptImportWorkspace(it.id, it.name, it.isPermanent) }
			_state.update { current ->
				val draft = current.promptImport ?: return@update current
				current.copy(
					promptImport = draft.copy(
						workspaces = workspaces,
						loading = false,
						error = if (workspaces.isEmpty()) {
							context.getString(R.string.prompt_import_no_workspaces)
						} else {
							null
						},
					),
				)
			}
		}
	}

	fun choosePromptImportWorkspace(workspaceId: String) {
		val draft = _state.value.promptImport ?: return
		val workspace = draft.workspaces.firstOrNull { it.id == workspaceId } ?: return
		loadPromptImportDirectory(workspace.id, workspace.name, "")
	}

	fun enterPromptImportDirectory(name: String) {
		val draft = _state.value.promptImport ?: return
		val workspaceId = draft.selectedWorkspaceId ?: return
		val path = if (draft.directory.isEmpty()) name else "${draft.directory}/$name"
		loadPromptImportDirectory(workspaceId, draft.selectedWorkspaceName, path)
	}

	fun promptImportUp() {
		val draft = _state.value.promptImport ?: return
		if (draft.selectedWorkspaceId == null || draft.directory.isEmpty()) return
		val parent = if (draft.directory.contains('/')) {
			draft.directory.substringBeforeLast('/')
		} else {
			""
		}
		loadPromptImportDirectory(draft.selectedWorkspaceId, draft.selectedWorkspaceName, parent)
	}

	fun promptImportChangeWorkspace() {
		_state.update { current ->
			val draft = current.promptImport ?: return@update current
			current.copy(
				promptImport = draft.copy(
					selectedWorkspaceId = null,
					selectedWorkspaceName = "",
					directory = "",
					entries = emptyList(),
					loading = false,
					error = null,
				),
			)
		}
	}

	fun closePromptImport() {
		_state.update { it.copy(promptImport = null) }
	}

	/** The screen has applied [AgentsUiState.promptImportPicked]; clear it. */
	fun consumePromptImportPicked() {
		_state.update { it.copy(promptImportPicked = null) }
	}

	fun pickPromptImportFile(name: String) {
		val draft = _state.value.promptImport ?: return
		val workspaceId = draft.selectedWorkspaceId ?: return
		val path = if (draft.directory.isEmpty()) name else "${draft.directory}/$name"
		_state.update { current ->
			val dialog = current.promptImport ?: return@update current
			current.copy(promptImport = dialog.copy(loading = true, error = null))
		}
		viewModelScope.launch {
			val read = withContext(Dispatchers.IO) {
				runCatching { readPromptImportFile(workspaceId, path, name) }
			}
			_state.update { current ->
				val dialog = current.promptImport ?: return@update current
				read.fold(
					onSuccess = { text ->
						current.copy(
							promptImport = null,
							promptImportPicked = PromptImportPicked(name, text),
						)
					},
					onFailure = { failure ->
						current.copy(
							promptImport = dialog.copy(
								loading = false,
								error = failure.message
									?: context.getString(R.string.prompt_import_failed),
							),
						)
					},
				)
			}
		}
	}

	private fun loadPromptImportDirectory(
		workspaceId: String,
		workspaceName: String,
		directory: String,
	) {
		_state.update { current ->
			val draft = current.promptImport ?: return@update current
			current.copy(
				promptImport = draft.copy(
					selectedWorkspaceId = workspaceId,
					selectedWorkspaceName = workspaceName,
					directory = directory,
					entries = emptyList(),
					loading = true,
					error = null,
				),
			)
		}
		viewModelScope.launch {
			val listed = withContext(Dispatchers.IO) {
				runCatching { listPromptImportEntries(workspaceId, directory) }
			}
			_state.update { current ->
				val draft = current.promptImport ?: return@update current
				// A slower listing for a folder the user already left must not
				// replace the one they are looking at.
				if (draft.selectedWorkspaceId != workspaceId || draft.directory != directory) {
					return@update current
				}
				listed.fold(
					onSuccess = {
						current.copy(
							promptImport = draft.copy(entries = it, loading = false, error = null),
						)
					},
					onFailure = { failure ->
						current.copy(
							promptImport = draft.copy(
								loading = false,
								error = failure.message
									?: context.getString(R.string.prompt_import_failed),
							),
						)
					},
				)
			}
		}
	}

	private suspend fun listPromptImportEntries(
		workspaceId: String,
		directory: String,
	): List<PromptImportEntry> {
		val workspace = conversations.workspace(workspaceId)
		val dir = when (val resolved = workspace.resolve(directory)) {
			is SessionWorkspace.Resolution.Rejected -> error(resolved.reason)
			is SessionWorkspace.Resolution.Ok -> resolved.file
		}
		if (!dir.isDirectory) error("not a directory: $directory")
		return dir.listFiles().orEmpty()
			.filterNot { Files.isSymbolicLink(it.toPath()) }
			.sortedWith(
				compareByDescending<File> { it.isDirectory }
					.thenBy { it.name.lowercase() },
			)
			.take(PROMPT_IMPORT_LIST_LIMIT)
			.map { PromptImportEntry(it.name, it.isDirectory) }
	}

	private suspend fun readPromptImportFile(
		workspaceId: String,
		path: String,
		name: String,
	): String {
		if (!isPromptText(name, null)) {
			error(context.getString(R.string.agents_prompt_file_type))
		}
		val workspace = conversations.workspace(workspaceId)
		val file = when (val resolved = workspace.resolve(path)) {
			is SessionWorkspace.Resolution.Rejected -> error(resolved.reason)
			is SessionWorkspace.Resolution.Ok -> resolved.file
		}
		if (!file.isFile) error("not a file: $path")
		if (file.length() > MAX_PROMPT_IMPORT_BYTES) {
			error(context.getString(R.string.agents_prompt_too_large))
		}
		return file.inputStream().use { readPromptBytes(it) }.let(::decodePromptText)
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
			runCatching { repository.delete(agent) }
				.onFailure { failure ->
					Diag.w("agent", "delete failed id=${Diag.shortId(agent.id)}", failure)
					_state.update {
						it.copy(
							error = context.getString(
								R.string.agents_delete_failed,
								failure.message ?: failure::class.simpleName ?: "unknown error",
							),
						)
					}
				}
				.onSuccess { Diag.i("agent", "deleted id=${Diag.shortId(agent.id)}") }
		}
	}

	/** Clears the shared error line; the row it rendered in owns the dismissal. */
	fun clearError() {
		_state.update { it.copy(error = null) }
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
