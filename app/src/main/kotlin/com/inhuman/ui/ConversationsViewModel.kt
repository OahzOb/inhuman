package com.inhuman.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.R
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.ConversationSearchHit
import com.inhuman.core.data.DataPaths
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.SessionMode
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.engine.roleplay.RoleplayCard
import com.inhuman.core.engine.roleplay.RoleplayCodec
import com.inhuman.core.engine.roleplay.RoleplayLimitError
import com.inhuman.core.engine.roleplay.RoleplayLimits
import com.inhuman.core.engine.roleplay.RoleplayPrompt
import com.inhuman.core.engine.roleplay.RoleplayScene
import com.inhuman.core.engine.roleplay.RoleplaySnapshot
import com.inhuman.core.engine.roleplay.RoleplayStory
import com.inhuman.core.engine.roleplay.RoleplayStoryLoader
import com.inhuman.core.engine.roleplay.RoleplayValidator
import com.inhuman.core.engine.roleplay.StoryFolder
import com.inhuman.core.engine.roleplay.StoryListing
import com.inhuman.core.engine.roleplay.StoryOutcome
import com.inhuman.core.files.WorkspaceTransfer
import com.inhuman.ui.chat.ConversationBundleCodec
import com.inhuman.ui.chat.ConversationBundleZip
import com.inhuman.ui.chat.SpeechCache
import com.inhuman.ui.roleplay.RoleplaySceneCreator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class ConversationsUiState(
	val conversations: List<ConversationEntity> = emptyList(),
	/** Names by agent id, so a row can say which assistant it belongs to. */
	val agentNames: Map<String, String> = emptyMap(),
	/** Workspace display names and lifetime, for delete confirmations. */
	val workspaceNames: Map<String, String> = emptyMap(),
	val permanentWorkspaceIds: Set<String> = emptySet(),
	/** Permanent workspaces offered by the Create sheet, in display order. */
	val permanentWorkspaces: List<com.inhuman.core.data.db.WorkspaceEntity> = emptyList(),
	/**
	 * Conversations whose workspace holds at least one finished speech file.
	 *
	 * Any voice, not the current one: this is the "there is something to hear
	 * in here" hint on the home list. Which replies are playable in the current
	 * voice is answered inside the transcript, where the voice is known.
	 */
	val speechReadyConversations: Set<String> = emptySet(),
	val loaded: Boolean = false,
	/** Multi-select state for deleting several conversations at once. */
	val selecting: Boolean = false,
	val selectedIds: Set<String> = emptySet(),
	val deletingSelection: Boolean = false,
	val error: String? = null,
	/** What the home search field currently contains. */
	val searchQuery: String = "",
	val searchResults: List<ConversationSearchHit> = emptyList(),
	val searching: Boolean = false,
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
	/**
	 * Conversations whose delete is in flight.
	 *
	 * The row only leaves the list once the delete has succeeded, so a
	 * confirmation is still on screen while the work runs. Without this, a
	 * delete that failed (or merely took a moment) could be asked for again
	 * from the same dialog.
	 */
	val deletingIds: Set<String> = emptySet(),
	/**
	 * Whether the next new conversation starts as a continuous session.
	 *
	 * A saved preference, not the state of an existing conversation: the Create
	 * sheet owns the choice, and turning one conversation off later must not
	 * silently flip this.
	 */
	val newConversationContinuous: Boolean = true,
	/**
	 * The new-role-play-scene dialog, non-null only while it is open.
	 *
	 * The character list lives in the dialog's state rather than in the screen:
	 * it is read when the dialog opens, offered once, and dropped when it
	 * closes. A card edited on the assistants screen (or an assistant deleted)
	 * while the form is open must not move the choice under the user's finger,
	 * which is why this is a snapshot rather than a second observed flow.
	 */
	val sceneDialog: SceneDraft? = null,
)

/**
 * A character card the scene dialog may offer.
 *
 * The decoded card is kept beside the assistant row so the picker can show a
 * description and the create path can render the greeting without decoding the
 * same JSON twice. In this source the name is the assistant's, not a field on
 * the card: a card stored on an assistant is not owned by an assistant editor,
 * and the name the user typed there is the one the transcript will be titled
 * with. (A card *file* can name itself; that is the materials source.)
 */
data class SceneCharacter(
	val agent: AgentEntity,
	val card: RoleplayCard,
) {
	val name: String get() = agent.name.trim()

	/**
	 * The one-line reminder of who this character is, for the picker row.
	 *
	 * [RoleplayCard.description] is the field meant for it, but every card field
	 * is optional and a card with only a personality is legal. Falling back
	 * keeps the row from being a bare name that says nothing about which of two
	 * characters it is.
	 */
	val description: String
		get() = card.description.trim()
			.ifBlank { card.personality.trim() }
			.ifBlank { card.scenario.trim() }
}

/**
 * Where a new scene's character comes from.
 *
 * [Assistant] is the original shape: the card lives on the assistant and the
 * assistant is the character. [Materials] is the workspace-files shape: the
 * card is `rp/<story>/card.json` in a permanent workspace, and the assistant is
 * only the model/tools configuration copied onto the new conversation. Both
 * create the same kind of conversation and freeze the same kind of snapshot.
 */
enum class SceneSource { Assistant, Materials }

/** A permanent workspace offered as role-play material storage. */
data class SceneWorkspaceChoice(
	val id: String,
	val name: String,
)

/**
 * What the open "new role-play scene" dialog is holding.
 *
 * A draft rather than a [RoleplayScene]: every scene field is optional, and the
 * dialog is the only place these values exist before the conversation does. The
 * screen edits it through [ConversationsViewModel.updateScene]; nothing is
 * written until Start.
 */
data class SceneDraft(
	val characters: List<SceneCharacter> = emptyList(),
	val selectedAgentId: String? = null,
	/**
	 * Every assistant, for the materials source.
	 *
	 * The workspace source does not take its card from an assistant, but it
	 * still needs one for provider/model/tools -- and as the fallback name when
	 * the card file has no `name` field. Unlike [characters] this list is not
	 * filtered to decodable cards.
	 */
	val assistants: List<AgentEntity> = emptyList(),
	/** Where the character comes from; see [SceneSource]. */
	val source: SceneSource = SceneSource.Assistant,
	/** Permanent workspaces, read when the dialog opens. */
	val workspaces: List<SceneWorkspaceChoice> = emptyList(),
	val selectedWorkspaceId: String? = null,
	/** Workspace-relative folder currently being browsed ("" is the root). */
	val browsePath: String = "",
	val folders: List<StoryFolder> = emptyList(),
	val foldersTruncated: Boolean = false,
	/** Whether [browsePath] itself directly contains a `card.json`. */
	val folderHasCard: Boolean = false,
	/** The validated story the materials source will start, once chosen. */
	val story: RoleplayStory? = null,
	val storyIssues: List<RoleplayValidator.Issue> = emptyList(),
	/** A folder-level failure (missing/unreadable), distinct from file issues. */
	val storyError: String? = null,
	val storyLoading: Boolean = false,
	val world: String = "",
	val premise: String = "",
	val userPersona: String = "",
	val format: String = "",
	val notes: String = "",
	/**
	 * True until the character read has finished.
	 *
	 * While true the "no role-play characters" message is withheld: the read is
	 * a local table scan, and the empty message would otherwise flash on a
	 * dialog that is about to have rows in it -- which reads as a fact about the
	 * install rather than as a read in flight.
	 */
	val loading: Boolean = true,
	/**
	 * True while the conversation row is being written.
	 *
	 * The dialog cannot be dismissed in that window: the row may already exist,
	 * and a dismissal would drop the navigation to it. The write is short, so
	 * the only alternatives would be a spinner over a half-live dialog or a
	 * retry that cannot tell whether the first attempt landed.
	 */
	val creating: Boolean = false,
	/** Why the last create attempt failed, shown in the dialog so it can retry. */
	val error: String? = null,
) {
	val selectedCharacter: SceneCharacter?
		get() = characters.firstOrNull { it.agent.id == selectedAgentId }

	/** The assistant that supplies provider/model/tools (and the fallback name). */
	val selectedAssistant: AgentEntity?
		get() = assistants.firstOrNull { it.id == selectedAgentId }

	val selectedWorkspace: SceneWorkspaceChoice?
		get() = workspaces.firstOrNull { it.id == selectedWorkspaceId }

	/**
	 * The story's character name: the card's own name, then the chosen
	 * assistant's name, then the folder name. Never blank for a loaded story,
	 * because a folder with no usable name would make an unnamed title.
	 */
	fun storyCharacterName(): String {
		val loaded = story ?: return ""
		return loaded.card.name.trim()
			.ifBlank { selectedAssistant?.name?.trim().orEmpty() }
			.ifBlank { loaded.folder.substringAfterLast('/') }
	}

	/**
	 * The scene these fields describe.
	 *
	 * Trimmed because the renderer trims every field anyway; storing the
	 * whitespace would only make the character count disagree with the prompt.
	 * In the materials source the scene comes from `scene.json` instead, so this
	 * is only read in the assistant source.
	 */
	fun toScene(): RoleplayScene = RoleplayScene(
		world = world.trim(),
		premise = premise.trim(),
		userPersona = userPersona.trim(),
		format = format.trim(),
		notes = notes.trim(),
	)

	/** Every limit the typed scene breaks, in field order, total last. */
	val sceneErrors: List<RoleplayLimitError> get() = RoleplayLimits.sceneErrors(toScene())

	/**
	 * Whether Start can be tapped.
	 *
	 * Both sources need a story and a model configuration; the assistant source
	 * takes its card from the assistant, while the materials source needs a
	 * folder that loaded cleanly (warnings are fine).
	 */
	val canStart: Boolean
		get() = !loading && !creating && when (source) {
			SceneSource.Assistant ->
				selectedCharacter != null && sceneErrors.isEmpty()

			SceneSource.Materials ->
				!storyLoading &&
					selectedAssistant != null &&
					story != null &&
					storyError == null &&
					storyIssues.none { !it.warning } &&
					sceneErrors.isEmpty()
		}
}

class ConversationsViewModel(
	private val context: Context,
	private val repository: ConversationRepository,
	private val agents: AgentRepository,
	private val secure: SecureStore,
) : ViewModel() {

	private val _state = MutableStateFlow(
		ConversationsUiState(newConversationContinuous = secure.newConversationContinuous()),
	)
	val state: StateFlow<ConversationsUiState> = _state.asStateFlow()

	/** The one scene-creation path, shared with the workshop's proposal card. */
	private val sceneCreator by lazy { RoleplaySceneCreator(repository) }

	/**
	 * Remember the Create-sheet choice for later conversations.
	 *
	 * It is written when the switch is touched, not when a conversation is
	 * created: the next new conversation should inherit the last explicit
	 * choice even if this one is abandoned.
	 */
	fun setNewConversationContinuous(enabled: Boolean) {
		secure.setNewConversationContinuous(enabled)
		_state.update { it.copy(newConversationContinuous = enabled) }
	}

	/**
	 * Rescan the workspaces for finished speech files.
	 *
	 * A directory check per conversation, not a database column: the audio is
	 * ordinary workspace content, and the files are the only truth about
	 * whether a read is still free. Called when the home list becomes visible
	 * and when it first loads, never during a chat.
	 */
	fun refreshSpeechReady() {
		val conversations = _state.value.conversations
		if (conversations.isEmpty()) {
			_state.update { it.copy(speechReadyConversations = emptySet()) }
			return
		}
		viewModelScope.launch {
			val ready = withContext(Dispatchers.IO) {
				val workspaces = DataPaths(context.filesDir)
				conversations.asSequence()
					.filter { conversation ->
						val dir = File(
							workspaces.workspaceDir(conversation.workspaceId),
							"generated/speech",
						)
						val names = dir.listFiles()
							?.filter { file -> file.isFile && file.length() > 44 }
							?.map { file -> file.name }
							.orEmpty()
						SpeechCache.hasAnyAudio(names)
					}
					.map { it.id }
					.toSet()
			}
			_state.update { it.copy(speechReadyConversations = ready) }
		}
	}

	/**
	 * The workspace chosen in the Create sheet, if any.
	 *
	 * Null means a new temporary workspace. Held until the assistant is chosen
	 * because the conversation row cannot be created before that decision.
	 */
	private var pendingWorkspaceId: String? = null
	private var searchJob: Job? = null

	init {
		viewModelScope.launch {
			repository.observeAll().collect { list ->
				val ids = list.mapTo(mutableSetOf()) { it.id }
				_state.update { current ->
					current.copy(
						conversations = list,
						loaded = true,
						selectedIds = current.selectedIds.intersect(ids),
						// Keep selection mode while nothing is checked yet. The
						// old test required a selected id, so a background turn
						// updating a conversation dismissed the checkboxes before
						// the user had picked anything.
						selecting = current.selecting &&
							(current.selectedIds.isEmpty() || current.selectedIds.any { it in ids }),
					)
				}
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
						permanentWorkspaces = list
							.filter { w -> w.isPermanent }
							.sortedBy { w -> w.name.lowercase() },
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
	 *
	 * @param workspaceId an existing permanent workspace, or null for a new
	 *   temporary one.
	 */
	fun startNewConversation(
		workspaceId: String? = null,
		onCreated: (String) -> Unit = {},
	) {
		pendingWorkspaceId = workspaceId
		viewModelScope.launch {
			// The assistant read failed once as an uncaught exception that
			// ended the process; report it where the Create sheet was instead.
			val available = runCatching { agents.all() }
				.onFailure { failure ->
					Diag.w("conv", "assistant list read failed", failure)
					_state.update {
						it.copy(
							error = context.getString(
								R.string.chat_new_conversation_failed,
								failure.message ?: failure::class.simpleName ?: "unknown error",
							),
						)
					}
				}
				.getOrNull() ?: return@launch
			when {
				available.isEmpty() -> {
					// Nothing to choose from: create with no assistant rather
					// than blocking. The default is seeded on database creation,
					// so this is only reachable if something went wrong there.
					pendingWorkspaceId = null
					create(null, workspaceId, onCreated)
				}

				available.size == 1 -> {
					// One assistant is not a choice. Asking anyway was a tap
					// that only confirmed there was nothing to decide.
					pendingWorkspaceId = null
					create(available.first(), workspaceId, onCreated)
				}

				else -> _state.update { it.copy(choosingAgent = available) }
			}
		}
	}

	fun cancelChoosing() {
		pendingWorkspaceId = null
		_state.update { it.copy(choosingAgent = null) }
	}

	/** Create with the chosen assistant and hand back the new id. */
	fun chooseAgent(agent: AgentEntity?, onCreated: (String) -> Unit) {
		val workspaceId = pendingWorkspaceId
		pendingWorkspaceId = null
		_state.update { it.copy(choosingAgent = null) }
		create(agent, workspaceId, onCreated)
	}

	/**
	 * Open the built-in role-play workshop, creating it on first use.
	 *
	 * The workspace is a permanent workspace the app owns; it is the only place
	 * where the role-play authoring helpers and the scene-proposal tool exist.
	 * Creating it lazily keeps first launch unchanged and makes the entry a
	 * normal product surface rather than a migration side effect.
	 *
	 * @param name the display name to use when creating it. Passed in rather than
	 *   read here because a view model holds the *application* context, whose
	 *   resources follow the system locale -- the activity's locale override is
	 *   not on it. Reading the name here stored "RP workshop" under a Chinese UI.
	 * @param onWorkspace called with the workshop's id on the main thread; the
	 *   caller navigates to the workspace detail screen (the workshop hub).
	 */
	fun openWorkshop(name: String, onWorkspace: (String) -> Unit) {
		openBuiltIn(name, R.string.workshop_open_failed, onWorkspace) {
			repository.ensureWorkshop(name)
		}
	}

	/**
	 * Open the built-in configuration workshop, creating it on first use.
	 *
	 * The same shape as [openWorkshop], and the same name-passing rule: the
	 * activity resolves the localized name because this view model only sees the
	 * application context's locale.
	 */
	fun openStudio(name: String, onWorkspace: (String) -> Unit) {
		openBuiltIn(name, R.string.studio_open_failed, onWorkspace) {
			repository.ensureStudio(name)
		}
	}

	/** The one ensure-then-navigate path both built-in workshops share. */
	private fun openBuiltIn(
		name: String,
		errorRes: Int,
		onWorkspace: (String) -> Unit,
		ensure: suspend () -> WorkspaceEntity,
	) {
		viewModelScope.launch {
			runCatching {
				withContext(Dispatchers.IO) { ensure() }
			}.onSuccess { workspace ->
				onWorkspace(workspace.id)
			}.onFailure { failure ->
				// The display name is user content, so it stays out of the
				// diagnostics: the failure and the caller are enough to find it.
				Diag.w("workshop", "open failed", failure)
				_state.update {
					it.copy(
						error = context.getString(
							errorRes,
							failure.message ?: failure::class.simpleName ?: "unknown error",
						),
					)
				}
			}
		}
	}

	/**
	 * Open the new-scene dialog and read the role-play characters it offers.
	 *
	 * Read on open rather than observed. A scene is configured once, and a
	 * continuously observed list would change under the user while they type (a
	 * card edited on the assistants screen, an assistant deleted); the row the
	 * user picked is the copy taken here.
	 *
	 * The list is filtered by [RoleplayCodec.decodeCard] rather than by "rpJson
	 * is non-empty". That decoder also rejects a malformed or blank card, so a
	 * card this release cannot use is not offered as a character that would
	 * silently play without one; a plain assistant is simply absent.
	 */
	fun openSceneDialog() {
		if (_state.value.sceneDialog != null) return
		_state.update { it.copy(sceneDialog = SceneDraft()) }
		viewModelScope.launch {
			val available = runCatching { agents.all() }
				.onFailure { Diag.w("scene", "character list read failed", it) }
				.getOrDefault(emptyList())
			val characters = available.mapNotNull { agent ->
				val card = RoleplayCodec.decodeCard(agent.rpJson)
					?: return@mapNotNull null
				SceneCharacter(agent, card)
			}
			// Only permanent workspaces: a temporary one belongs to a single
			// conversation and would be deleted with it, taking the story files
			// a new scene just read.
			val workspaces = runCatching { repository.workspaces() }
				.onFailure { Diag.w("scene", "workspace list read failed", it) }
				.getOrDefault(emptyList())
				.filter { it.isPermanent }
				.map { SceneWorkspaceChoice(it.id, it.name) }
			_state.update { current ->
				// Closed while the read ran: do not resurrect the dialog.
				val draft = current.sceneDialog ?: return@update current
				current.copy(
					sceneDialog = draft.copy(
						characters = characters,
						assistants = available,
						workspaces = workspaces,
						// The first character is the default, because a picker
						// with exactly one row should not also require a tap to
						// select it. The assistant list does the same for the
						// materials source.
						selectedAgentId = draft.selectedAgentId
							?: characters.firstOrNull()?.agent?.id
							?: available.firstOrNull()?.id,
						selectedWorkspaceId = draft.selectedWorkspaceId
							?: workspaces.firstOrNull()?.id,
						loading = false,
					),
				)
			}
			// The user can switch to the materials source while this read is in
			// flight; that click found no workspace to list, and the update above
			// is the first moment there is one. Without this second attempt the
			// folder list would stay empty until the source was toggled again.
			val after = _state.value.sceneDialog
			if (after?.source == SceneSource.Materials &&
				after.selectedWorkspaceId != null &&
				after.folders.isEmpty() &&
				!after.storyLoading
			) {
				refreshSceneFolders()
			}
		}
	}

	/**
	 * Switch the scene source, keeping the assistant choice where it still
	 * exists and choosing that source's default otherwise.
	 */
	fun setSceneSource(source: SceneSource) {
		val draft = _state.value.sceneDialog ?: return
		if (draft.source == source) return
		val candidates = when (source) {
			SceneSource.Assistant -> draft.characters.map { it.agent.id }
			SceneSource.Materials -> draft.assistants.map { it.id }
		}
		_state.update { current ->
			current.sceneDialog?.let {
				current.copy(
					sceneDialog = it.copy(
						source = source,
						selectedAgentId = it.selectedAgentId?.takeIf { id -> id in candidates }
							?: candidates.firstOrNull(),
					),
				)
			} ?: current
		}
		if (source == SceneSource.Materials) refreshSceneFolders()
	}

	/** Choose the permanent workspace the story folder lives in. */
	fun selectSceneWorkspace(workspaceId: String) {
		val draft = _state.value.sceneDialog ?: return
		if (draft.selectedWorkspaceId == workspaceId && draft.browsePath.isEmpty()) return
		_state.update { current ->
			current.sceneDialog?.let {
				current.copy(
					sceneDialog = it.copy(
						selectedWorkspaceId = workspaceId,
						browsePath = "",
						folders = emptyList(),
						folderHasCard = false,
						story = null,
						storyIssues = emptyList(),
						storyError = null,
					),
				)
			} ?: current
		}
		refreshSceneFolders()
	}

	/** Enter one folder in the materials browser. */
	fun enterSceneFolder(path: String) {
		_state.update { current ->
			current.sceneDialog?.let {
				current.copy(
					sceneDialog = it.copy(
						browsePath = path,
						folders = emptyList(),
						folderHasCard = false,
						story = null,
						storyIssues = emptyList(),
						storyError = null,
					),
				)
			} ?: current
		}
		refreshSceneFolders()
	}

	/** Go one level up in the materials browser. */
	fun sceneFolderUp() {
		val draft = _state.value.sceneDialog ?: return
		if (draft.browsePath.isEmpty()) return
		enterSceneFolder(draft.browsePath.substringBeforeLast('/', ""))
	}

	/**
	 * Read the folder currently being browsed as a story.
	 *
	 * Kept as an explicit action rather than validating on every folder change:
	 * browsing through a tree of unrelated folders should not print card errors
	 * for each one. The button is only offered where `card.json` exists.
	 */
	fun useSceneFolder() {
		val draft = _state.value.sceneDialog ?: return
		val workspaceId = draft.selectedWorkspaceId ?: return
		val path = draft.browsePath
		if (path.isEmpty() || !draft.folderHasCard) return
		_state.update { current ->
			current.sceneDialog?.let {
				current.copy(
					sceneDialog = it.copy(storyLoading = true, storyError = null, storyIssues = emptyList()),
				)
			} ?: current
		}
		viewModelScope.launch {
			val outcome = withContext(Dispatchers.IO) {
				runCatching {
					RoleplayStoryLoader.load(repository.workspace(workspaceId), path)
				}.getOrElse { failure ->
					StoryOutcome.Failed(failure.message ?: "could not read the folder")
				}
			}
			_state.update { current ->
				val d = current.sceneDialog ?: return@update current
				// The user may have navigated elsewhere while the read ran.
				if (d.selectedWorkspaceId != workspaceId || d.browsePath != path) {
					return@update current
				}
				val next = when (outcome) {
					is StoryOutcome.Ok -> d.copy(
						story = outcome.story,
						storyIssues = outcome.story.warnings,
						storyError = null,
						storyLoading = false,
					)

					is StoryOutcome.Invalid -> d.copy(
						story = null,
						storyIssues = outcome.issues,
						storyError = null,
						storyLoading = false,
					)

					is StoryOutcome.Failed -> d.copy(
						story = null,
						storyIssues = emptyList(),
						storyError = outcome.reason,
						storyLoading = false,
					)
				}
				current.copy(sceneDialog = next)
			}
		}
	}

	/**
	 * List the folders under the draft's current path.
	 *
	 * A folder read is filesystem work and happens off the main thread; the
	 * generation check after it returns keeps a slow listing for a folder the
	 * user already left from replacing the current one.
	 */
	private fun refreshSceneFolders() {
		val draft = _state.value.sceneDialog ?: return
		if (draft.source != SceneSource.Materials) return
		val workspaceId = draft.selectedWorkspaceId ?: return
		val path = draft.browsePath
		Diag.d("scene", "folders read workspace=${Diag.shortId(workspaceId)} path='$path'")
		_state.update { current ->
			current.sceneDialog?.let {
				current.copy(sceneDialog = it.copy(storyLoading = true, storyError = null))
			} ?: current
		}
		viewModelScope.launch {
			val listing = withContext(Dispatchers.IO) {
				runCatching {
					RoleplayStoryLoader.list(repository.workspace(workspaceId), path)
				}.getOrElse { failure ->
					StoryListing.Failed(failure.message ?: "could not list the folder")
				}
			}
			if (listing is StoryListing.Ok) {
				Diag.d(
					"scene",
					"folders listed path='${listing.path}' entries=${listing.entries.size} " +
						"hasCard=${listing.hasCardHere} truncated=${listing.truncated}",
				)
			} else if (listing is StoryListing.Failed) {
				Diag.w("scene", "folders failed: ${listing.reason}")
			}
			_state.update { current ->
				val d = current.sceneDialog ?: return@update current
				if (d.source != SceneSource.Materials ||
					d.selectedWorkspaceId != workspaceId ||
					d.browsePath != path
				) {
					return@update current
				}
				val next = when (listing) {
					is StoryListing.Ok -> d.copy(
						folders = listing.entries,
						foldersTruncated = listing.truncated,
						folderHasCard = listing.hasCardHere,
						storyLoading = false,
						storyError = null,
					)

					is StoryListing.Failed -> d.copy(
						folders = emptyList(),
						foldersTruncated = false,
						folderHasCard = false,
						storyLoading = false,
						storyError = listing.reason,
					)
				}
				current.copy(sceneDialog = next)
			}
		}
	}

	/** Close the scene dialog, unless the conversation is already being written. */
	fun cancelScene() {
		if (_state.value.sceneDialog?.creating == true) return
		_state.update { it.copy(sceneDialog = null) }
	}

	/** Change the open scene draft in place; the dialog's only writer. */
	fun updateScene(transform: (SceneDraft) -> SceneDraft) {
		_state.update { current ->
			current.sceneDialog?.let { current.copy(sceneDialog = transform(it)) } ?: current
		}
	}

	/**
	 * Create the conversation behind the open scene dialog.
	 *
	 * The conversation is created like an ordinary new one -- a temporary
	 * workspace, the same continuous-session preference -- and then given the
	 * three things that make it a scene:
	 *
	 * - **The title is the character's name.** A scene has no name of its own,
	 *   and the home list has to be readable at a glance; the character is the
	 *   one fact actually chosen before the conversation existed.
	 * - **Tools are an explicit empty list.** `null` would copy the assistant's
	 *   tool set, so a story would start with whatever file and web tools the
	 *   assistant happens to carry. `emptyList()` writes the real `"[]"` (none)
	 *   selection; the conversation tool sheet is where the user adds them
	 *   later, which is deliberately not a field in this dialog.
	 * - **The greeting is a stored assistant row**, written by the repository in
	 *   the same transaction as the conversation. The character spoke first;
	 *   faking a user message so the history begins with a user turn would make
	 *   the transcript lie about who said what, so that shape is handled on the
	 *   wire (`RoleplayPrompt.KICKOFF`) instead.
	 *
	 * A failure keeps the dialog open with the reason inside it: the form is the
	 * only copy of what the user typed, and closing it over a retryable database
	 * error would throw that away to report a message about a screen they are no
	 * longer looking at.
	 */
	fun startScene(onCreated: (String) -> Unit) {
		val draft = _state.value.sceneDialog ?: return
		if (!draft.canStart) return
		// One create path for both sources. The differences are exactly what a
		// scene freezes: which card, which scene, which name, and -- for the
		// workspace source -- which permanent workspace the story files live in.
		val assistant: AgentEntity
		val card: RoleplayCard
		val cardJson: String
		val scene: RoleplayScene
		val characterName: String
		val workspaceId: String?
		when (draft.source) {
			SceneSource.Assistant -> {
				val character = draft.selectedCharacter ?: return
				assistant = character.agent
				card = character.card
				// The raw JSON, not a re-encoded card: an unknown field written
				// by a newer release survives into the snapshot.
				cardJson = character.agent.rpJson
				scene = draft.toScene()
				characterName = character.name
				workspaceId = null
			}

			SceneSource.Materials -> {
				val agent = draft.selectedAssistant ?: return
				val story = draft.story ?: return
				// Warnings (unknown fields) are fine; blocking issues are not.
				if (draft.storyIssues.any { !it.warning }) return
				assistant = agent
				card = story.card
				cardJson = story.cardJson
				scene = story.scene ?: RoleplayScene()
				characterName = draft.storyCharacterName()
				workspaceId = draft.selectedWorkspaceId ?: return
			}
		}
		Diag.i(
			"scene",
			"create source=${draft.source} assistant=${Diag.shortId(assistant.id)} " +
				"workspace=${Diag.shortId(workspaceId)} chars=${RoleplayLimits.sceneChars(scene)}",
		)
		_state.update { current ->
			current.sceneDialog?.let {
				current.copy(sceneDialog = it.copy(creating = true, error = null))
			} ?: current
		}
		viewModelScope.launch {
			// The scene copies the assistant prompt, name and card at creation:
			// editing the assistant later must not rewrite a story already in
			// progress. The creator writes the frozen persona and the greeting in
			// the same transaction as the conversation, so a scene never exists
			// unpinned and then silently picks a different character on its first
			// turn -- and it is the same writer the workshop's proposal card uses.
			val result = runCatching {
				sceneCreator.create(
					title = characterName.ifBlank { context.getString(R.string.scene_title) },
					agent = assistant,
					// Assistant source: null is a new temporary workspace, exactly
					// as the ordinary "New conversation" choice means it. Materials
					// source: the permanent workspace holding the story files, so
					// the agent can later be given file tools over the same tree.
					workspaceId = workspaceId,
					cardJson = cardJson,
					card = card,
					characterName = characterName.ifBlank { assistant.name },
					systemPrompt = assistant.systemPrompt,
					scene = scene,
					sessionMode = newConversationSessionMode(),
				)
			}
			result.onSuccess { conversation ->
				Diag.i(
					"scene",
					"created id=${Diag.shortId(conversation.id)} " +
						"agent=${Diag.shortId(conversation.agentId)} " +
						"workspace=${Diag.shortId(conversation.workspaceId)} " +
						"cardChars=${RoleplayLimits.cardChars(card)}",
				)
				_state.update { it.copy(sceneDialog = null) }
				onCreated(conversation.id)
			}.onFailure { failure ->
				Diag.w(
					"scene",
					"create failed assistant=${Diag.shortId(assistant.id)} " +
						"workspace=${Diag.shortId(workspaceId)}",
					failure,
				)
				val message = context.getString(
					R.string.chat_new_conversation_failed,
					failure.message ?: failure::class.simpleName ?: "unknown error",
				)
				_state.update { current ->
					current.sceneDialog?.let {
						current.copy(sceneDialog = it.copy(creating = false, error = message))
					} ?: current
				}
			}
		}
	}

	/**
	 * The session mode a new conversation starts in.
	 *
	 * One expression shared by the ordinary and the scene creation paths: the
	 * Create sheet's choice applies to every new conversation, and two copies of
	 * this conditional would be two places for the preference to drift.
	 */
	private fun newConversationSessionMode(): String =
		if (secure.newConversationContinuous()) SessionMode.CONTINUOUS else SessionMode.CLASSIC

	private fun create(
		agent: AgentEntity?,
		workspaceId: String?,
		onCreated: ((String) -> Unit)? = null,
	) {
		Diag.i(
			"conv",
			"create agent=${Diag.shortId(agent?.id)} workspace=${Diag.shortId(workspaceId)}",
		)
		viewModelScope.launch {
			// A create that fails must not take the process down with it: the
			// Create sheet has already closed, so the home screen's error row
			// is the only place the failure can be reported. Nothing is lost
			// here -- a conversation either exists or it does not -- but the
			// tap that failed has to say so.
			val conversation = runCatching {
				repository.create(
					title = context.getString(R.string.chat_title_fallback),
					agent = agent,
					workspaceId = workspaceId,
					sessionMode = newConversationSessionMode(),
				)
			}.onFailure { failure ->
				Diag.w(
					"conv",
					"create failed agent=${Diag.shortId(agent?.id)} " +
						"workspace=${Diag.shortId(workspaceId)}",
					failure,
				)
				_state.update {
					it.copy(
						error = context.getString(
							R.string.chat_new_conversation_failed,
							failure.message ?: failure::class.simpleName ?: "unknown error",
						),
					)
				}
			}.getOrNull() ?: return@launch
			Diag.i(
				"conv",
				"created id=${Diag.shortId(conversation.id)} " +
					"agent=${Diag.shortId(conversation.agentId)} " +
					"workspace=${Diag.shortId(conversation.workspaceId)}",
			)
			onCreated?.invoke(conversation.id)
		}
	}

	/**
	 * Import a portable conversation bundle into a new conversation.
	 *
	 * The bundle is untrusted input. The codec validates the format; only
	 * attachment paths that the bundle's own messages reference are copied; and
	 * every file goes through [WorkspaceTransfer], so the quota, per-file cap,
	 * free-space reserve and path fence are the same ones a user-picked file
	 * obeys.
	 *
	 * The target assistant is the local default: the bundle does not carry an
	 * assistant, and an id from the source install would be meaningless here.
	 * The messages' model labels are kept as history, not as a setting.
	 */
	fun importConversationBundle(uri: Uri, onResult: (String?, String?) -> Unit) {
		viewModelScope.launch {
			val outcome = withContext(Dispatchers.IO) {
				runCatching {
					val input = context.contentResolver.openInputStream(uri)
						?: error("could not open the bundle")
					val archive = input.use { ConversationBundleZip.read(it) }
						?: error("not a valid conversation bundle")
					val bundle = ConversationBundleCodec.decode(archive.json)
						?: error("not a valid conversation bundle")
					if (bundle.messages.isEmpty()) error("the bundle has no messages")
					val agent = agents.defaultAgent() ?: agents.all().firstOrNull()
					val conversation = repository.create(
						title = bundle.title.trim().ifBlank { "Imported conversation" }.take(200),
						agent = agent,
						sessionMode = SessionMode.parse(bundle.sessionMode),
					)
					val workspace = repository.workspace(conversation)
					val transfer = WorkspaceTransfer(workspace)
					val referenced = bundle.messages
						.flatMap { ConversationBundleCodec.attachmentPaths(it.attachmentsJson) }
						.toSet()
					var copiedFiles = 0
					for ((path, bytes) in archive.files) {
						if (path !in referenced) continue
						val result = transfer.import(destinationPath = path, maxBytes = null) {
							bytes.inputStream()
						}
						if (result is WorkspaceTransfer.Outcome.Ok) copiedFiles++
					}
					var parent: String? = null
					var index = 0
					for (message in bundle.messages) {
						val id = UUID.randomUUID().toString()
						val createdAt = message.createdAt.takeIf { it > 0 }
							?: (System.currentTimeMillis() + index)
						repository.appendMessage(
							MessageEntity(
								id = id,
								conversationId = conversation.id,
								parentId = parent,
								role = message.role,
								content = message.content,
								reasoning = message.reasoning,
								reasoningSignature = message.reasoningSignature,
								providerState = message.providerState,
								model = message.model,
								finishReason = message.finishReason,
								createdAt = createdAt,
								promptTokens = message.promptTokens,
								completionTokens = message.completionTokens,
								cachedPromptTokens = message.cachedPromptTokens,
								reasoningTokens = message.reasoningTokens,
								timeToFirstTokenMs = message.timeToFirstTokenMs,
								generationMs = message.generationMs,
								error = message.error,
								toolCallsJson = message.toolCallsJson,
								toolCallId = message.toolCallId,
								toolName = message.toolName,
								changedFilesJson = message.changedFilesJson,
								attachmentsJson = message.attachmentsJson,
								mutated = message.mutated,
							),
							parent,
						)
						parent = id
						index++
					}
					Diag.i(
						"import",
						"conversation messages=${bundle.messages.size} " +
							"attachments=$copiedFiles sessionMode=${conversation.sessionMode}",
					)
					conversation.id
				}
			}
			outcome.onSuccess { onResult(it, null) }
				.onFailure { failure ->
					Diag.w("import", "conversation bundle failed", failure)
					onResult(null, failure.message ?: "Could not import the conversation.")
				}
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
		// One attempt per conversation at a time. The row disappears from the
		// list only when the delete succeeds, so the dialog is still open while
		// it runs; a second tap used to start a second delete of the same id.
		if (conversation.id in _state.value.deletingIds) return
		Diag.i("conv", "delete id=${Diag.shortId(conversation.id)}")
		_state.update {
			it.copy(
				deletePreviewCounts = emptyMap(),
				deletingIds = it.deletingIds + conversation.id,
			)
		}
		viewModelScope.launch {
			runCatching { repository.delete(conversation) }
				.onSuccess {
					Diag.i("conv", "deleted id=${Diag.shortId(conversation.id)}")
				}
				.onFailure { failure ->
					// This used to throw inside the coroutine: the dialog stayed
					// open forever and the user was told nothing. A delete that
					// fails is a thing to report, not to crash on.
					Diag.w(
						"conv",
						"delete failed id=${Diag.shortId(conversation.id)}",
						failure,
					)
					_state.update {
						it.copy(error = context.getString(R.string.home_delete_failed))
					}
				}
			_state.update { it.copy(deletingIds = it.deletingIds - conversation.id) }
		}
	}

	fun setPinned(conversation: ConversationEntity, pinned: Boolean) {
		Diag.d("conv", "pin id=${Diag.shortId(conversation.id)} pinned=$pinned")
		viewModelScope.launch { repository.setPinned(conversation.id, pinned) }
	}

	/**
	 * Rename a conversation from the home list.
	 *
	 * The chat menu has always had this; the list did not, so changing a title
	 * meant loading the whole transcript to reach a rename dialog. The
	 * repository call is the same one that path makes, which is why a temporary
	 * workspace's display name stays in step with its conversation.
	 *
	 * A failed rename reports through the same error row the delete and the
	 * create use; a title edit that silently did nothing would look like a
	 * typo on the user's part.
	 */
	fun rename(conversation: ConversationEntity, title: String) {
		val trimmed = title.trim()
		if (trimmed.isEmpty()) return
		Diag.i("conv", "rename id=${Diag.shortId(conversation.id)} chars=${trimmed.length}")
		viewModelScope.launch {
			runCatching { repository.rename(conversation.id, trimmed) }
				.onFailure { failure ->
					Diag.w("conv", "rename failed id=${Diag.shortId(conversation.id)}", failure)
					_state.update {
						it.copy(
							error = context.getString(
								R.string.home_rename_failed,
								failure.message ?: failure::class.simpleName ?: "unknown error",
							),
						)
					}
				}
		}
	}

	fun beginSelection(id: String? = null) {
		_state.update { current ->
			current.copy(
				selecting = true,
				selectedIds = if (id == null) current.selectedIds else current.selectedIds + id,
			)
		}
	}

	fun toggleSelection(id: String) {
		_state.update { current ->
			val next = if (id in current.selectedIds) {
				current.selectedIds - id
			} else {
				current.selectedIds + id
			}
			current.copy(selectedIds = next, selecting = true)
		}
	}

	fun cancelSelection() {
		_state.update { it.copy(selecting = false, selectedIds = emptySet()) }
	}

	/**
	 * Select every conversation, or clear the checks when they all are.
	 *
	 * The set is derived from the list at tap time rather than stored as an
	 * "all" flag. A conversation created by a running turn must not become
	 * selected without a tap, and the delete dialog must name exactly the rows
	 * the user saw checked.
	 */
	fun toggleSelectAll() {
		_state.update { current ->
			val all = current.conversations.mapTo(mutableSetOf()) { it.id }
			val next = if (all.isNotEmpty() && current.selectedIds.containsAll(all)) {
				emptySet<String>()
			} else {
				all
			}
			current.copy(selecting = true, selectedIds = next)
		}
	}

	fun clearError() {
		_state.update { it.copy(error = null) }
	}

	/** Delete every selected conversation, leaving permanent workspaces intact. */
	fun deleteSelected() {
		val current = _state.value
		if (current.deletingSelection || current.selectedIds.isEmpty()) return
		val targets = current.conversations.filter { it.id in current.selectedIds }
		if (targets.isEmpty()) {
			cancelSelection()
			return
		}
		_state.update { it.copy(deletingSelection = true, deletePreviewCounts = emptyMap()) }
		viewModelScope.launch {
			val deleted = repository.deleteConversations(targets)
			_state.update {
				it.copy(
					deletingSelection = false,
					selecting = false,
					selectedIds = emptySet(),
					error = if (deleted == targets.size) {
						null
					} else {
						context.getString(R.string.home_bulk_delete_partial, deleted, targets.size)
					},
				)
			}
			Diag.i("conv", "bulk delete requested=${targets.size} deleted=$deleted")
		}
	}

	/**
	 * Debounced global search.
	 *
	 * The field is deliberately not backed by a database flow: each keystroke
	 * would re-run the `LIKE` query, and a title like "test" can match many
	 * message rows. A 250 ms delay is enough to make typing feel immediate while
	 * keeping the query count proportional to pauses, not keys.
	 */
	fun onSearchQueryChange(query: String) {
		_state.update { it.copy(searchQuery = query) }
		searchJob?.cancel()
		if (query.isBlank()) {
			_state.update { it.copy(searchResults = emptyList(), searching = false) }
			return
		}
		searchJob = viewModelScope.launch {
			delay(250)
			_state.update { it.copy(searching = true) }
			val results = runCatching { repository.search(query) }
				.onFailure { failure ->
					Diag.w("search", "query failed chars=${query.length}", failure)
				}
				.getOrDefault(emptyList())
			// A slower query for an older prefix must not overwrite the results
			// for what the user is typing now.
			if (_state.value.searchQuery == query) {
				_state.update {
					it.copy(searchResults = results, searching = false)
				}
			}
		}
	}

	fun clearSearch() {
		searchJob?.cancel()
		_state.update { it.copy(searchQuery = "", searchResults = emptyList(), searching = false) }
	}
}
