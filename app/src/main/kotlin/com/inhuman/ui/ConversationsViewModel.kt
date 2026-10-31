package com.inhuman.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.R
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.ConversationSearchHit
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
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
)

class ConversationsViewModel(
	private val context: Context,
	private val repository: ConversationRepository,
	private val agents: AgentRepository,
) : ViewModel() {

	private val _state = MutableStateFlow(ConversationsUiState())
	val state: StateFlow<ConversationsUiState> = _state.asStateFlow()

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
	fun startNewConversation(workspaceId: String? = null) {
		pendingWorkspaceId = workspaceId
		viewModelScope.launch {
			val available = agents.all()
			if (available.isEmpty()) {
				// Nothing to choose from: create with no assistant rather than
				// blocking. The default is seeded on database creation, so this
				// is only reachable if something went wrong there.
				pendingWorkspaceId = null
				create(null, workspaceId)
			} else {
				_state.update { it.copy(choosingAgent = available) }
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
			val conversation = repository.create(
				title = context.getString(R.string.chat_title_fallback),
				agent = agent,
				workspaceId = workspaceId,
			)
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
						"Deleted $deleted of ${targets.size} conversations. The rest could not be removed."
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
