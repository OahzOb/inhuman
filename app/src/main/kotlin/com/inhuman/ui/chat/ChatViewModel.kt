package com.inhuman.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolSpec
import com.inhuman.core.ai.policy.PolicySet
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.Branches
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.inhuman.core.ai.tools.SearchProvider
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.ai.tools.TimeTool
import com.inhuman.core.ai.tools.WebSearchTool
import com.inhuman.core.files.tools.FileTools
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Drives one conversation, including the tool loop.
 *
 * ## How streaming meets persistence
 *
 * Text is accumulated in memory while a reply arrives and written **once**, at
 * the end. Writing per token would put a database transaction behind every
 * chunk -- thousands for one answer -- and the write-ahead log would spend the
 * whole generation flushing. The cost is losing an in-flight reply if the
 * process dies, which is the right trade.
 *
 * ## The tool loop
 *
 * A turn can require several model round trips. The model asks for a tool, the
 * app runs it, the result goes back, and the model continues -- repeatedly,
 * until it answers without asking for another tool. That loop lives here rather
 * than in the provider because *which* tools exist and *whether a call is
 * allowed* are app policy, not transport.
 *
 * The loop is bounded by [MAX_TOOL_ROUNDS]. A model that keeps calling tools
 * would otherwise generate forever at the user's expense, and every iteration
 * re-sends the whole transcript, so an unbounded loop grows quadratically.
 */
class ChatViewModel(
	private val repository: ConversationRepository,
	private val agents: AgentRepository,
	private val secure: SecureStore,
	private val conversationId: String,
	/**
	 * Where `web_search` gets its answers, or null when none is configured.
	 *
	 * ## Why this is a constructor parameter and not a field with a default
	 *
	 * The provider has not been chosen. A null here is not an oversight waiting
	 * to be filled in with a hardcoded vendor -- it is the seam. Wiring a
	 * concrete service directly into the view model is the thing that would have
	 * to be unpicked to change it, so the view model takes the interface and
	 * stays ignorant of who implements it.
	 *
	 * When nothing is passed the search tool is **not registered at all**, rather
	 * than registered and always failing. The distinction matters: a model given
	 * a tool that errors will retry it and end up telling the user that search is
	 * broken, whereas a model without the tool will say it cannot search, which
	 * is true.
	 */
	private val searchProvider: SearchProvider? = null,
) : ViewModel() {

	private val _state = MutableStateFlow(ChatUiState())
	val state: StateFlow<ChatUiState> = _state.asStateFlow()

	private var conversation: ConversationEntity? = null
	private var streamJob: Job? = null

	/** The assistant behind this conversation, once loaded. */
	private var agent: AgentEntity? = null

	private var model: String? = null
	private var reasoningEffort: String? = null
	private var thinking: Boolean = false

	/**
	 * The context window, cached from the last model fetch.
	 *
	 * Held rather than looked up on demand because the publish points are not
	 * all suspend: [streamOneTurn] finishes a reply inside a `finally` and has
	 * no business awaiting a network call to draw a gauge.
	 *
	 * Null means "the provider does not say", which the gauge reports as
	 * unknown rather than filling in a plausible number.
	 */
	private var contextWindow: Int? = null

	/**
	 * Deltas for the answer currently arriving, keyed by message id.
	 *
	 * ## Why a channel, and why only the answer
	 *
	 * The Markdown renderer's incremental parser is fed a `Flow<String>` and
	 * appends every emission verbatim -- it does not compare against what it
	 * already holds. Feeding it the accumulated text would therefore duplicate
	 * the whole reply on every tick, so it has to be given only the *new* text,
	 * which is what lets it reparse from the last stable block rather than from
	 * the start.
	 *
	 * Only the answer gets one. Reasoning is rendered as plain dimmed text in
	 * its own collapsed section, never as Markdown, so a reasoning delta
	 * appended to this stream would be parsed as part of the answer and then
	 * vanish when the reply completed.
	 *
	 * A `Channel` rather than a `MutableSharedFlow`: this must not drop values.
	 * A reply is appended to at human speed so the buffer stays tiny, and a
	 * shared flow with a small buffer would silently lose text the moment the
	 * renderer fell behind.
	 *
	 * ## Why a channel, and why only the answer
	 *
	 * The Markdown renderer's incremental parser is fed text a piece at a time
	 * and appends every piece verbatim -- it does not compare against what it
	 * already holds. Feeding it the accumulated text would therefore duplicate
	 * the whole reply on every tick, so it has to be given only the *new* text,
	 * which is what lets it reparse from the last stable block rather than from
	 * the start.
	 *
	 * Only the answer gets one. Reasoning is rendered as plain dimmed text in
	 * its own collapsed section, never as Markdown, so a reasoning delta
	 * appended here would be parsed as part of the answer and then vanish when
	 * the reply completed.
	 *
	 * A `Channel` rather than a `MutableSharedFlow`: this must not drop values.
	 * A reply is appended to at human speed so the buffer stays tiny, and a
	 * shared flow with a small buffer would silently lose text the moment the
	 * renderer fell behind.
	 *
	 * ## Why the consumer iterates the channel
	 *
	 * `receiveAsFlow` exists in the coroutines artifact but does not resolve
	 * from this module's Kotlin metadata -- the same gap that hides
	 * `collectAsStreamingMarkdownState` in the Markdown library and makes
	 * `Channel` look like it is not a `Flow`. `Channel` is iterable and
	 * `receiveAsFlow` is a thin wrapper over exactly that iteration, so the
	 * consumer loop is the same mechanism with one less layer.
	 */
	private val replyDeltas = mutableMapOf<String, Channel<String>>()


	init {
		viewModelScope.launch {
			conversation = repository.conversation(conversationId)?.entity
			val c = conversation
			// The conversation's own settings win over the agent's, because a
			// conversation may have been switched to a different model after it
			// started. The agent supplies the defaults it was created with.
			agent = c?.agentId?.let { agents.byId(it) }
				?: agents.byId(Database.DEFAULT_AGENT_ID)
			model = c?.model ?: agent?.model
			reasoningEffort = c?.reasoningEffort ?: agent?.reasoningEffort
			thinking = c?.thinking ?: agent?.thinking ?: false

			refreshConfiguration()
			reload()
			publishModelState()
		}
	}

	/**
	 * Mirror the model settings into the state the UI reads.
	 *
	 * Called after every change rather than derived in the screen, because the
	 * label needs the fetched model list -- and "DeepSeek-V4.1-Flash" is what
	 * the provider calls it while "deepseek-flash" is what goes on the wire.
	 * Showing the id would be showing an implementation detail.
	 *
	 * The same list carries the context window, which is why it is read here
	 * too: it is per model, it changes when the model does, and the fetch has
	 * already happened.
	 */
	private suspend fun publishModelState() {
		// Read once and pass it down rather than letting the helper ask again:
		// this is a Keystore-backed read and the function is already on a path
		// that runs at startup and on every configuration refresh.
		val demo = demoEnabled()
		val fetched = runCatching { agents.models() }.getOrNull()?.getOrNull()
			?: demoModelsIfEnabled(demo)

		// Resolve all the way to a concrete id.
		//
		// "Provider default" is a real choice for *which model to send*, but it
		// is not an answer to "which effort levels does this model have" -- and
		// the id that will actually be sent is known: DEFAULT_MODEL, the same
		// constant the request uses. Leaving this null made the effort control
		// report that a model with three levels had none, because it looked up
		// capabilities by a null id.
		//
		// The API's own default is the honest fallback when the list has not
		// arrived: it is what the provider will use if nothing else is chosen.
		val id = model ?: agent?.model ?: fetched?.firstOrNull()?.id ?: DEFAULT_MODEL

		// The entry for the model that will actually be sent: capabilities are
		// per model, and looking them up by the choice rather than the resolved
		// id is how the effort control came to report that a model with three
		// levels had none.
		//
		// A **failed** fetch leaves the last known window standing rather than
		// blanking it -- losing the network is not a reason to stop knowing how
		// large the window is, and a null here would replace a real figure with
		// "unknown" for as long as the outage lasted. A fetch that succeeds and
		// does not list the model does clear it, because that is the provider
		// genuinely not saying.
		val fetchedModel = fetched?.firstOrNull { it.id == id }
		if (fetched != null) contextWindow = fetchedModel?.contextWindow

		if (fetched != null) {
			val label = fetchedModel?.displayName?.takeIf { it.isNotBlank() }
				?: if (model == null && agent?.model == null) "${id} (default)" else id

			_state.update {
				it.copy(
					// The choice, so the picker can show what was picked.
					model = model,
					// The resolved id, for looking anything up.
					effectiveModel = id,
					modelLabel = label,
					// Whether the model accepts images, so the composer can refuse
					// before staging one. The API does not refuse: it returns 200
					// and the model answers about a placeholder.
					modelSupportsVision = fetchedModel?.supportsVision == true,
				)
			}
		}

		_state.update {
			it.copy(
				reasoningEffort = reasoningEffort,
				thinking = thinking,
				context = contextOf(it.messages),
			)
		}
	}

	/**
	 * Fold the measured prompt size into the state the gauge reads.
	 *
	 * For the moments when a transcript is already published and only the
	 * measurement is new -- the end of a stream, where usage arrives. A reload
	 * does not call this: it rebuilds the whole list, so it reads the gauge in
	 * the same pass. Both go through [contextOf], which is where the arithmetic
	 * lives, so the ring and its panel cannot disagree about it.
	 */
	private fun refreshContext() {
		_state.update { it.copy(context = contextOf(it.messages)) }
	}

	/**
	 * The demo provider's model list, or null when demo mode is off.
	 *
	 * ## Why the app has to ask for this itself
	 *
	 * `AgentRepository.models()` builds a DeepSeek provider, and with no key
	 * stored it returns a failure without making a request. That is right for
	 * everything it feeds -- you cannot pick a model you have no account for --
	 * but it left the gauge with no window in demo mode, so a demo that had
	 * plainly sent 128 tokens of a 64,000-token window reported "this model
	 * does not report a context window". The measurement was there and the
	 * denominator was missing, which is a worse state than either alone.
	 *
	 * The reply itself does not depend on this: [runTurn] constructs the demo
	 * provider directly. Only the capability list was unreachable, so only the
	 * capability list is supplied here.
	 */
	private suspend fun demoModelsIfEnabled(demo: Boolean): List<RemoteModel>? =
		if (demo) runCatching { DemoProvider().listModels() }.getOrNull() else null

	/**
	 * The gauge reading for a transcript.
	 *
	 * ## Why the last reported figure rather than a sum of the visible messages
	 *
	 * `promptTokens` is what the provider actually counted for the whole
	 * request: the system prompt, the tool schemas, the conversation and every
	 * tool result. Summing the text on screen would miss the schemas -- a few
	 * thousand tokens of them -- and would be an estimate dressed as a
	 * measurement. The provider's own number is the only one that is true.
	 *
	 * ## Why scanning backwards is the right search
	 *
	 * The most recent reply that reported usage is the largest prompt so far,
	 * because a transcript only grows within a turn. Rows are in creation
	 * order, so the last match is the current reading; a reply that reported
	 * nothing (a cancelled stream, a provider that omits usage) is skipped
	 * rather than treated as zero.
	 */
	private fun contextOf(messages: List<ChatMessageUi>): ContextUsage {
		val used = messages
			.lastOrNull { it.usage?.promptTokens != null }
			?.usage
			?.promptTokens
			?.toLong()
		return ContextUsage(usedTokens = used, contextWindow = contextWindow)
	}

	/** Called after the model list is (re)loaded, so a picker can refresh. */
	suspend fun reloadModels(): List<RemoteModel> =
		agents.models(force = true).getOrNull().orEmpty()

	/**
	 * Switch the model for this conversation only.
	 *
	 * Not written back to the agent: switching models mid-conversation is a
	 * decision about *this* thread, and editing the assistant would change every
	 * other conversation held with it.
	 */
	fun setModel(modelId: String?) {
		model = modelId
		// Cleared rather than carried: effort levels are per-model, so keeping
		// one across a switch can send a value the new model rejects.
		reasoningEffort = null
		viewModelScope.launch {
			repository.setModel(conversationId, modelId)
			repository.setReasoning(conversationId, reasoningEffort, thinking)
			publishModelState()
		}
	}

	fun setThinking(enabled: Boolean) {
		thinking = enabled
		if (!enabled) reasoningEffort = null
		viewModelScope.launch {
			repository.setReasoning(conversationId, reasoningEffort, thinking)
			publishModelState()
		}
	}

	fun setReasoningEffort(level: String?) {
		reasoningEffort = level
		viewModelScope.launch {
			repository.setReasoning(conversationId, reasoningEffort, thinking)
			publishModelState()
		}
	}

	// --- attachments ---------------------------------------------------------

	/**
	 * Stage an image for the next message.
	 *
	 * Refused when the model cannot see, and the refusal is explicit rather than
	 * deferred to the API -- because the API does not refuse. Verified against
	 * the live service: sending an image to a text-only model returns **200**,
	 * the model receives a literal `[Unsupported Image]` placeholder, and it
	 * answers confidently about that instead. There is nothing to catch, so a
	 * user would get a wrong answer with no indication why.
	 */
	fun attachImage(displayName: String, mimeType: String, open: () -> java.io.InputStream) {
		viewModelScope.launch {
			// One source of truth, and the one the composer already read.
			//
			// This used to look the model up itself with `agents.models()` while
			// `publishModelState` -- which is what enables or disables the attach
			// button -- fell back to the demo model list when there is no key.
			// Two answers to "can this model see", so in demo mode the button was
			// enabled and every attempt was refused: the demo model advertises
			// image input, and this lookup could not find it. Which one was
			// authoritative is not a question worth answering twice; the
			// published answer is the one the user was already shown.
			val supports = _state.value.modelSupportsVision

			// Unknown counts as unsupported: with no list loaded there is no
			// evidence the model can see, and staging an attachment that will be
			// silently mishandled is the outcome worth avoiding.
			if (supports != true) {
				_state.update {
					it.copy(
						error = "The model in use cannot read images. " +
							"Pick one that does, from the title bar.",
					)
				}
				return@launch
			}

			val entity = conversation
			if (entity == null) {
				_state.update { it.copy(error = "This conversation is not ready yet.") }
				return@launch
			}

			val workspace = runCatching { repository.workspace(entity) }.getOrNull()
			if (workspace == null) {
				_state.update { it.copy(error = "This conversation has no workspace.") }
				return@launch
			}

			// Into an attachments folder, so a picture the user chose is not
			// confused with a file the assistant wrote. They appear in the same
			// browser, and the distinction matters when deciding what to delete.
			val transfer = WorkspaceTransfer(workspace)
			val outcome = withContext(Dispatchers.IO) {
				transfer.import("attachments/" + transfer.uniqueName(displayName)) { open() }
			}

			when (outcome) {
				is WorkspaceTransfer.Outcome.Failed ->
					_state.update { it.copy(error = outcome.reason) }

				is WorkspaceTransfer.Outcome.Ok ->
					_state.update {
						it.copy(
							pendingAttachments = it.pendingAttachments + PendingAttachment(
								relativePath = outcome.relativePath,
								mimeType = mimeType,
								sizeBytes = outcome.bytes,
							),
							error = null,
						)
					}
			}
		}
	}

	/**
	 * Drop a staged image, and the file behind it.
	 *
	 * The file goes too. It was copied in for this message, and leaving it would
	 * fill the workspace with pictures the user removed before sending.
	 */
	fun removeAttachment(attachment: PendingAttachment) {
		_state.update {
			it.copy(pendingAttachments = it.pendingAttachments.filterNot { a ->
				a.relativePath == attachment.relativePath
			})
		}
		viewModelScope.launch {
			val entity = conversation ?: return@launch
			withContext(Dispatchers.IO) {
				val workspace = runCatching { repository.workspace(entity) }.getOrNull()
					?: return@withContext
				(workspace.resolve(attachment.relativePath) as? SessionWorkspace.Resolution.Ok)
					?.file?.delete()
			}
		}
	}

	private suspend fun reload() {
		val loaded = repository.conversation(conversationId)
		val entities = loaded?.messages.orEmpty()

		// Only the points that actually branch, resolved for the whole
		// transcript in one query. A row asking the database for its own
		// siblings was one query per message plus another for every parent with
		// more than one child -- hundreds of round trips to answer "no" almost
		// every time.
		//
		// Keyed by parent id, because alternatives belong to the point in the
		// conversation rather than to any one reply: a message looks its own up
		// through `parentId`, which puts the switcher under the message below
		// the branch point.
		val branchInfo = repository.branchesIn(conversationId)

		// Which calls were refused, resolved in the same pass and for the same
		// reason: the outcome is a property of the tool result, and a row
		// searching the transcript for its own would be a scan per call.
		val declined = entities
			.filter { it.role == "tool" && it.content.startsWith(DECLINED_MARKER) }
			.mapNotNullTo(mutableSetOf()) { it.toolCallId }

		_state.update { current ->
			val rebuilt = entities.map { it.toUi(it.parentId?.let(branchInfo::get), declined) }
			current.copy(
				title = loaded?.entity?.title ?: current.title,
				messages = rebuilt,
				// `currentTurnMutated` is deliberately not assigned here. It is
				// derived from `messages`, so rebuilding the transcript *is*
				// recomputing it -- and assigning it here as well is what used to
				// leave a stale copy behind, because a plain `send()` never
				// reloads and so never came past this line to clear it.
				//
				// Read off the rebuilt list, for the same reason the mutation
				// flag is: the two controls that depend on the transcript have
				// to be computed from the transcript that is on screen.
				context = contextOf(rebuilt),
			)
		}
	}

	/**
	 * Answer again, replacing the reply at the end of the conversation.
	 *
	 * The new answer becomes a sibling of the old one rather than overwriting
	 * it. Regenerating is a request for another attempt, not a statement that
	 * the first was wrong, and a comparison is only possible if both exist.
	 *
	 * The user's message is reused, not resent: the transcript is the tree, and
	 * re-sending would append a duplicate question and change what the model
	 * sees for every turn after it.
	 */
	/**
	 * Whether the current turn changed the workspace.
	 *
	 * Delegates to [ChatUiState.currentTurnMutated] rather than repeating its
	 * arithmetic. This used to be a second implementation of the same predicate
	 * next to a stored flag -- three expressions of one fact, which is why one
	 * of them could be wrong without anyone noticing. The reasoning about why
	 * the answer is not simply "the last reply" now lives with the property.
	 */
	private fun currentTurnMutated(): Boolean = _state.value.currentTurnMutated

	fun regenerate() {
		if (_state.value.isStreaming) return

		val visible = _state.value.messages
		val lastReply = visible.lastOrNull { !it.fromUser && !it.isToolResult } ?: return

		// Refused here as well as in the UI.
		//
		// The button is hidden, but a check that lives only in the view is not a
		// rule -- it is a habit, and the next entry point added would not inherit
		// it. What is at stake is a second round of real writes, which is not
		// something to guard with layout alone.
		if (currentTurnMutated()) {
			_state.update {
				it.copy(
					error = "This reply changed files, so it cannot be regenerated. " +
						"Send a new message instead.",
				)
			}
			return
		}

		// The question this reply answers. Null means it is the first message,
		// which cannot happen for an assistant turn, but returning is safer
		// than guessing a parent.
		val questionId = lastReply.parentId ?: return

		val demo = demoEnabled()
		val apiKey = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY)
		if (!demo && apiKey.isNullOrBlank()) {
			_state.update { it.copy(error = "Add a DeepSeek API key first, or switch on demo mode.") }
			return
		}

		// Strip everything from the reply onward. runTurn appends the new answer
		// as a child of questionId, so the old reply stops being on the active
		// path without being touched.
		val cut = visible.indexOfFirst { it.id == lastReply.id }
		if (cut < 0) return

		_state.update {
			it.copy(messages = it.messages.take(cut), isStreaming = true, error = null)
		}

		streamJob = viewModelScope.launch {
			runTurn(apiKey = apiKey, demo = demo)
			// Reload after answering, so the new reply is recognised as a
			// sibling of the one it replaced.
			//
			// Without this the transcript on screen is correct but its branch
			// information is not: runTurn appends the reply with no siblings
			// known, and nothing recomputes them. The tree in the database has
			// the two replies under one parent the whole time -- only the view
			// model's copy was missing it, so the switcher never appeared and
			// the earlier answer could not be reached again.
			reload()
		}
	}

	/**
	 * Show a different reply at a branch point.
	 *
	 * Reloads afterwards rather than patching state locally: the selection
	 * changes the active path from that point down, so everything below it may
	 * differ, and computing that here would duplicate `activePath` badly.
	 */
	/**
	 * Show a different reply at a branch point.
	 *
	 * ## Why the parent is passed in rather than looked up
	 *
	 * The obvious implementation takes only the child id and finds the parent by
	 * searching the transcript -- which cannot work, and failed silently at
	 * first. `messages` holds the **active path**, so the reply being switched
	 * *to* is by definition not in it; the lookup found nothing and returned, and
	 * the arrow did nothing at all.
	 *
	 * The caller already knows both ids, because it is displaying the branch
	 * count and got them from the same query.
	 *
	 * Reloads afterwards rather than patching state locally: the selection
	 * changes the active path from that point down, so everything below it may
	 * differ, and computing that here would duplicate `activePath` badly.
	 */
	fun selectBranch(parentId: String, childId: String) {
		viewModelScope.launch {
			repository.selectBranch(parentId, childId)
			reload()
		}
	}

	/**
	 * Re-read the provider configuration.
	 *
	 * The view model is activity-scoped so a transcript survives a trip to
	 * settings; the cost is that `init` runs once, and a key added there would
	 * otherwise go unnoticed until the app restarted.
	 */
	fun refreshConfiguration() {
		val demo = demoEnabled()
		val hasKey = secure.hasUsable(SecureStore.KEY_DEEPSEEK_API_KEY)
		_state.update { it.copy(demoMode = demo, configured = demo || hasKey) }
	}

	private fun demoEnabled(): Boolean = secure.get(SecureStore.KEY_DEMO_MODE) == "1"

	fun onInputChange(text: String) = _state.update { it.copy(input = text) }

	fun clearError() = _state.update { it.copy(error = null) }

	fun stop() {
		streamJob?.cancel()
		streamJob = null
		_state.update { s ->
			s.copy(
				isStreaming = false,
				pendingApproval = null,
				messages = s.messages.map {
					if (it.isStreaming) it.copy(isStreaming = false) else it
				},
			)
		}
	}

	// --- approvals -----------------------------------------------------------

	fun approvePendingCall() {
		val pending = _state.value.pendingApproval ?: return
		_state.update { it.copy(pendingApproval = null) }
		viewModelScope.launch { continueAfterApproval(pending, approved = true) }
	}

	fun declinePendingCall() {
		val pending = _state.value.pendingApproval ?: return
		_state.update { it.copy(pendingApproval = null) }
		viewModelScope.launch { continueAfterApproval(pending, approved = false) }
	}

	// --- sending -------------------------------------------------------------

	fun send() {
		val text = _state.value.input.trim()
		val attachments = _state.value.pendingAttachments
		// Images alone are a complete message -- "what is this?" is often
		// implied -- but an empty message with nothing attached is not.
		if ((text.isEmpty() && attachments.isEmpty()) || _state.value.isStreaming) return

		val demo = demoEnabled()
		val apiKey = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY)
		if (!demo && apiKey.isNullOrBlank()) {
			_state.update { it.copy(error = "Add a DeepSeek API key first, or switch on demo mode.") }
			return
		}

		val parent = _state.value.messages.lastOrNull()?.id
		val userMessage = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parent,
			role = "user",
			content = text,
			createdAt = System.currentTimeMillis(),
			attachmentsJson = attachments.takeIf { it.isNotEmpty() }?.let(::encodeAttachments),
		)

		_state.update {
			it.copy(
				messages = it.messages + userMessage.toUi(),
				input = "",
				// Cleared only now, when the message that carries them is
				// committed. Clearing earlier would lose them if the send were
				// refused above.
				pendingAttachments = emptyList(),
				isStreaming = true,
				error = null,
			)
		}

		// The job is kept, so Stop can actually stop it.
		//
		// `stop()` cancelled `streamJob` and this never assigned it, so the
		// field was null for the life of the view model and the button did
		// nothing at all: the answer went on growing after Stop, and because
		// `isStreaming` was cleared the composer unlocked, so a second turn
		// could be started while the first was still writing into the same
		// message tree.
		streamJob = viewModelScope.launch {
			repository.appendMessage(userMessage, parent)
			if (_state.value.messages.count { it.fromUser } == 1) {
				// An image-only first message has no text to derive a title
				// from, so it falls back rather than leaving the list blank.
				repository.autoTitle(
					conversationId,
					text.ifBlank { attachments.firstOrNull()?.displayName ?: "Image" },
				)
				val stored = repository.conversation(conversationId)?.entity?.title
				if (stored != null) _state.update { it.copy(title = stored) }
			}
			runTurn(apiKey = apiKey, demo = demo)
		}
	}

	/**
	 * Run the model until it answers without asking for a tool.
	 *
	 * Each iteration appends the model's turn (with any tool calls), executes
	 * what was asked, appends the results, and goes round again.
	 */
	private suspend fun runTurn(apiKey: String?, demo: Boolean) {
		val provider: ChatProvider =
			if (demo) DemoProvider() else DeepSeekProvider(apiKey = apiKey!!)

		val tools = buildTools()
		val toolSpecs = tools.map { spec(it) }

		var rounds = 0
		while (rounds < MAX_TOOL_ROUNDS) {
			rounds++
			val outcome = streamOneTurn(provider, tools, toolSpecs)

			if (outcome.toolCalls.isEmpty()) {
				_state.update { it.copy(isStreaming = false) }
				return
			}

			// Execute them, in order. Approvals are collected first so a model
			// asking for three writes does not stack three dialogs.
			val executed = executeCalls(outcome, tools)
			if (!executed) {
				// The user declined something, or the turn was cancelled. The
				// transcript already holds the refusal, so the model can respond
				// to it on the next user message rather than being re-run now.
				_state.update { it.copy(isStreaming = false) }
				return
			}
		}

		// Hit the ceiling. Said plainly, because a silent stop looks like the
		// model simply had nothing more to say.
		_state.update {
			it.copy(
				isStreaming = false,
				error = "Stopped after $MAX_TOOL_ROUNDS tool rounds. " +
					"The assistant may be looping; send another message to continue.",
			)
		}
	}

	private class TurnOutcome(
		val messageId: String,
		val text: String,
		val reasoning: String,
		val toolCalls: List<ToolCallUi>,
		val usage: UsageUi?,
		val finish: String?,
		val failure: String?,
	)

	/** One model round trip: stream a reply and persist it. */
	private suspend fun streamOneTurn(
		provider: ChatProvider,
		tools: List<Tool>,
		toolSpecs: List<ToolSpec>,
	): TurnOutcome {
		val parent = _state.value.messages.lastOrNull()?.id
		val replyId = UUID.randomUUID().toString()

		// The delta stream this reply will be parsed from as it arrives.
		// Registered before the row exists, because the row's first render
		// already needs it: a reply that appeared with no deltas and gained
		// them a moment later would parse its first slice twice.
		val deltas = Channel<String>(Channel.UNLIMITED)
		replyDeltas[replyId] = deltas

		_state.update {
			it.copy(
				messages = it.messages + ChatMessageUi(
					id = replyId,
					parentId = parent,
					fromUser = false,
					isStreaming = true,
					deltas = deltas,
				),
			)
		}

		val history = buildWireHistory()
		val reasoning = StringBuilder()
		val answer = StringBuilder()
		val calls = linkedMapOf<Int, ToolCallUi>()
		var promptTokens: Int? = null
		var completionTokens: Int? = null
		var cachedTokens: Int? = null
		var reasoningTokens: Int? = null
		var finish: String? = null
		var failure: String? = null

		// Timed here rather than in the provider: what a user perceives as
		// latency is measured from the moment they see the spinner, which is
		// before any transport work has happened.
		val startedAt = System.currentTimeMillis()
		var firstTokenAt: Long? = null
		var finishedAt: Long? = null

		// Captured before the finally block so the closing work can see them.
		var finishedCalls: List<ToolCallUi> = emptyList()
		var wasStopped = false

		// Tracks what the incremental parser already has. A cursor rather than
		// an integer because "send the part that is new" has two ways to be
		// wrong -- re-sending a prefix duplicates the reply, skipping a slice
		// loses text -- and neither is visible after the stream has finished.
		// The invariant is tested in DeltaCursorTest.
		//
		// Declared here, outside the `try`, because the `finally` flushes the
		// last publish window through it. Inside the `try` it would be out of
		// scope in the `finally`, which is exactly how the final partial window
		// came to be dropped.
		val cursor = DeltaCursor()

		try {
			// When the screen was last given a snapshot of the streaming reply.
			//
			// ## Why the updates are throttled rather than per chunk
			//
			// Every chunk used to publish immediately, and each publish calls
			// `reasoning.toString()` and `answer.toString()` -- a full copy of
			// everything accumulated so far -- then rebuilds the whole message
			// list. Over one reply that is quadratic: a two-thousand-token
			// answer re-copies its own prefix hundreds of times, and the copies
			// are garbage as soon as they are made. The text is *shown* at a
			// frame rate regardless, so most of that work was thrown away
			// before anyone could read it.
			//
			// 40 ms is fast enough that the text still appears as it is
			// produced -- 25 updates a second against a display that draws 60 --
			// and it removes most of the copying. The last partial window is
			// flushed by `publishIfDue(force = true)` in the `finally` block
			// below, which is the only reason this throttle is not lossy.
			var publishedAt = 0L
			fun publishIfDue(force: Boolean = false) {
				val now = System.currentTimeMillis()
				if (!force && now - publishedAt < STREAM_PUBLISH_INTERVAL_MS) return
				publishedAt = now
				publishParts(replyId, reasoning, answer, calls, cursor)
			}

			provider.stream(
				ChatRequest(
					model = model ?: agent?.model ?: DEFAULT_MODEL,
					messages = history,
					tools = toolSpecs,
					// Taken from the assistant, and off by default: "hi" should
					// not cost a paragraph of thinking.
					thinking = thinking,
					reasoningEffort = reasoningEffort,
					userId = conversationId,
				),
			).collect { chunk ->
				if (firstTokenAt == null && chunk is StreamChunk.TextDelta) {
					firstTokenAt = System.currentTimeMillis()
				}
				when (chunk) {
					is StreamChunk.ReasoningDelta -> {
						reasoning.append(chunk.text)
						publishIfDue()
					}

					is StreamChunk.TextDelta -> {
						answer.append(chunk.text)
						publishIfDue()
					}

					is StreamChunk.ToolCallStart -> {
						calls[chunk.index] = ToolCallUi(
							index = chunk.index,
							id = chunk.id,
							name = chunk.name,
							state = ToolCallUi.State.Streaming,
						)
						// A structural change, not a token: shown immediately,
						// because the alternative is a tool card appearing late.
						publishIfDue(force = true)
					}

					is StreamChunk.ToolCallDelta -> {
						val existing = calls[chunk.index] ?: ToolCallUi(chunk.index, null, null)
						calls[chunk.index] = existing.copy(
							// Concatenated, not `?:`.
							//
							// The two halves of this used to disagree: the
							// decoder sent the name only on the opening frame,
							// while this line kept the *first* fragment and
							// discarded the rest. Whichever of them was right,
							// the other was silently wrong -- and the way it
							// failed was a call named `read_` instead of
							// `read_file`, which then matched no tool at all.
							//
							// Accumulating is the only reading that works for
							// both shapes: a name delivered once concatenates to
							// itself, and a name delivered in pieces assembles.
							name = chunk.nameDelta?.let { delta -> (existing.name ?: "") + delta }
								?: existing.name,
							// Concatenated, never parsed here: a fragment is not
							// valid JSON and parsing early is the classic bug.
							argumentsJson = existing.argumentsJson + (chunk.inputDelta ?: ""),
						)
						publishIfDue()
					}

					is StreamChunk.ToolCallEnd -> {
						calls[chunk.index]?.let { c ->
							calls[chunk.index] = c.copy(state = ToolCallUi.State.Ready)
						}
						// A call becoming ready is a state a person waits for.
						publishIfDue(force = true)
					}

					is StreamChunk.Usage -> {
						// Held as separate nullable fields rather than assembled
						// into a UsageUi here, because the timing fields are not
						// known until the stream ends and rebuilding the object
						// per chunk would publish a series of intermediate
						// numbers that flicker.
						promptTokens = chunk.promptTokens
						completionTokens = chunk.completionTokens
						cachedTokens = chunk.cachedPromptTokens
						reasoningTokens = chunk.reasoningTokens
					}

					is StreamChunk.Finish -> {
						finish = chunk.reason.describe()
						finishedAt = System.currentTimeMillis()
					}

					is StreamChunk.Failure -> {
						failure = chunk.message
						updateMessage(replyId) { it.copy(error = chunk.message) }
						_state.update { it.copy(error = chunk.message) }
					}

					is StreamChunk.TextStart,
					is StreamChunk.TextEnd,
					is StreamChunk.ReasoningStart,
					is StreamChunk.ReasoningEnd,
					is StreamChunk.Raw,
					-> Unit
				}
			}
		} finally {
			// Flush the last, partial publish window.
			//
			// `publishIfDue` below throttles to one update per 40 ms and is a
			// *local* function, which is precisely why this was missing: the
			// comment at the throttle claimed "the `finally` block publishes
			// regardless", but a local function declared inside the `try` is not
			// in scope in the `finally`, so the finally only ever called
			// `updateMessage`. Up to 40 ms of tokens were therefore absent from
			// `plainText` and from `parts` while the database row -- written
			// from these StringBuilders -- was complete. The symptom was a
			// bubble that stayed a few words short until something reloaded the
			// transcript, which reads as a rendering delay rather than as lost
			// state.
			//
			// Hoisting the publish into a real method is the fix; calling it
			// here is what makes the throttle non-lossy.
			publishParts(replyId, reasoning, answer, calls, cursor)
			finishedCalls = calls.values.toList()
			// The reply is done arriving, so it stays parsed from the plain
			// renderer from here on -- and the channel is dropped so an
			// abandoned collector cannot keep this reply's map entry alive.
			replyDeltas.remove(replyId)?.close()
			// Captured here, while the coroutine can still say whether it was
			// cancelled: by the time the writes below have run, being inside
			// NonCancellable means `isActive` no longer answers the question.
			wasStopped = !currentCoroutineContext().isActive
			// Assembled once, at the end, when every field is known.
			val endedAt = finishedAt ?: System.currentTimeMillis()
			val usage = if (
				promptTokens != null || completionTokens != null ||
				cachedTokens != null || reasoningTokens != null
			) {
				UsageUi(
					promptTokens = promptTokens,
					completionTokens = completionTokens,
					cachedPromptTokens = cachedTokens,
					reasoningTokens = reasoningTokens,
					timeToFirstTokenMs = firstTokenAt?.let { it - startedAt },
					generationMs = firstTokenAt?.let { endedAt - it },
				)
			} else {
				null
			}
			// `deltas = null` is the switch the renderer's documentation
			// describes and nothing performed: while it is non-null the reply
			// renders through the incremental parser, and `text` is ignored.
			// Clearing it here hands the finished reply to the plain renderer,
			// whose content is the same document -- so the swap is not visible,
			// and the last parse is a real one rather than a stream that never
			// ends. `replyDeltas.remove` below drops the channel, but the field
			// was captured into the message when it was built, so removing the
			// map entry alone left the reply on the streaming path for the rest
			// of the session.
			updateMessage(replyId) {
				it.copy(isStreaming = false, finishReason = finish, usage = usage, deltas = null)
			}
			// The gauge moves here rather than at the end of the turn, because
			// this is the moment the provider's count for this prompt exists --
			// and because a turn can take several round trips, each of which
			// resends a larger transcript than the last.
			refreshContext()

			val finalUsage = usage
			// ## Why the closing writes are NonCancellable
			//
			// This block runs when the turn ends *or* when it is stopped, and
			// Stop is the case that matters: a cancelled coroutine refuses to
			// suspend, so `appendMessage` would throw `CancellationException`
			// and the part of the answer already received would never reach the
			// database. The user would watch a paragraph appear, press Stop, and
			// find it gone the next time the conversation was opened -- while
			// still having been billed for it.
			//
			// Nothing here can hang: it is one insert, one state update and a
			// list membership test.
			withContext(NonCancellable) {
				if (answer.isNotEmpty() || reasoning.isNotEmpty() || finishedCalls.isNotEmpty()) {
					repository.appendMessage(
						MessageEntity(
							id = replyId,
							conversationId = conversationId,
							parentId = parent,
							role = "assistant",
							content = answer.toString(),
							reasoning = reasoning.toString().takeIf { it.isNotBlank() },
							model = model,
							finishReason = finish,
							// A turn that was stopped says so, rather than
							// looking like a reply that ended by itself.
							error = failure ?: if (wasStopped) STOPPED_MESSAGE else null,
							createdAt = System.currentTimeMillis(),
							promptTokens = finalUsage?.promptTokens,
							completionTokens = finalUsage?.completionTokens,
							cachedPromptTokens = finalUsage?.cachedPromptTokens,
							reasoningTokens = finalUsage?.reasoningTokens,
							timeToFirstTokenMs = finalUsage?.timeToFirstTokenMs,
							generationMs = finalUsage?.generationMs,
							toolCallsJson = finishedCalls.takeIf { it.isNotEmpty() }?.let { encodeCalls(it) },
						),
						parent,
					)
				} else {
					_state.update { s -> s.copy(messages = s.messages.filterNot { it.id == replyId }) }
				}
			}
		}

		return TurnOutcome(
			messageId = replyId,
			text = answer.toString(),
			reasoning = reasoning.toString(),
			toolCalls = finishedCalls,
			usage = null,
			finish = finish,
			failure = failure,
		)
	}

	/**
	 * Give every call of this round that was not run an answer of its own.
	 *
	 * ## Why a skipped call needs a result rather than nothing
	 *
	 * The round's calls were all recorded in `toolCallsJson` when the model
	 * asked for them. A tool result is what answers one, and the API requires
	 * every recorded call to be answered before the transcript can be sent
	 * again -- this file says so itself further down, where a result landing
	 * after a cancellation is described as producing "a request the API
	 * rejects". So leaving calls unanswered does not merely lose a chip's
	 * state: it makes the **next user message** a likely 400.
	 *
	 * The two ways to leave them unanswered were both real. Declining the
	 * *first* of several calls returned immediately, so the calls after it were
	 * never run and never answered. Pressing Stop during a tool had the same
	 * shape, because cancellation unwound the loop from inside the body.
	 *
	 * Deliberately not `NonCancellable` in its own right: it is called from
	 * [declinePendingCall]'s path while the turn is still live, and the stop
	 * path wraps it, so one insert per skipped call is not interruptible
	 * mid-round in the case that matters.
	 *
	 * @param after results at or below this index already exist; only the calls
	 *   after it need answering.
	 */
	private suspend fun answerSkippedCalls(outcome: TurnOutcome, after: Int) {
		for (skipped in outcome.toolCalls.filter { it.index > after }) {
			// `Failed` rather than `Declined`: the user refused one specific
			// call, and saying the others were refused too would be untrue.
			// The marker on the result is what distinguishes "did not run"
			// from "ran and broke".
			markCall(outcome.messageId, skipped.index, ToolCallUi.State.Failed)
			appendToolResult(
				skipped,
				"$SKIPPED_MARKER This call was not run, so it has no result.",
				isError = true,
				messageId = outcome.messageId,
			)
		}
	}

	/**
	 * Run the calls a turn asked for.
	 *
	 * @param tools the registry the turn was already built with, passed in
	 *   rather than rebuilt. `buildTools` constructs eight file tools, each
	 *   carrying a `StatFs` over the workspace root, and rebuilding the same
	 *   list to look up names the caller already holds is work with no
	 *   purpose -- on every round of the loop.
	 * @return false if the turn should stop -- the user declined, or the run was
	 *   cancelled mid-way.
	 */
	private suspend fun executeCalls(outcome: TurnOutcome, tools: List<Tool>): Boolean {
		val byName = tools.associateBy { it.name }

		// Read once per round rather than per call: the rules do not change
		// while a turn is running, and reading them per call would be three
		// queries times however many tools the model asked for.
		val policies = loadPolicies()

		// Guards the loop's `finally` so the normal path -- every call run and
		// answered -- does not pay a second pass over the list. Only an early
		// exit and a cancellation need the cleanup, and both of those set
		// `settled` or throw.
		var settled = false
		var reached = -1

		try {
		for (call in outcome.toolCalls) {
				reached = call.index
				val tool = byName[call.displayName]
				if (tool == null) {
					appendToolResult(call, "no such tool: ${call.displayName}", isError = true, outcome.messageId)
					continue
				}

				val parsed = parseArguments(call, outcome.messageId) ?: continue

				// The configured rules, before the tool's own opinion.
				//
				// `Deny` and `Blind` are refusals rather than questions, so they
				// have to be answered here -- `needsApproval` can only say "ask",
				// and asking about something the user has forbidden would be the
				// wrong question with a confusing answer.
				val policy = policies.effective(
					tool = tool.name,
					relativePath = resolvedPolicyPath(parsed),
				)
				if (policy == ToolPolicy.Deny || policy == ToolPolicy.Blind) {
					markCall(outcome.messageId, call.index, ToolCallUi.State.Failed)
					appendToolResult(
						call,
						// Deliberately the same sentence for both. `Blind` is a
						// stronger *implementation* of the same decision -- the
						// file is also hidden from `list_files` -- but telling
						// the model "you cannot even see this" invites it to
						// probe for the difference.
						"${REFUSED_MARKER} This path or tool is not available in this conversation.",
						isError = true,
						messageId = outcome.messageId,
					)
					continue
				}

				if (policy == ToolPolicy.Ask || tool.needsApproval(parsed)) {
					val summary = describeCall(call, parsed)
					_state.update {
						it.copy(pendingApproval = PendingApproval(outcome.messageId, call, summary))
					}
					// Suspend here until the user decides. The continuation is
					// resumed by approvePendingCall/declinePendingCall, which is
					// why the pending state is stored rather than a dialog shown.
					val approved = awaitApproval()
					if (!approved) {
						// The chip is set before the result is appended, and it has
						// to be: without this the call stayed `Ready` forever, so a
						// refused delete rendered as a call that was merely waiting
						// -- the one state a user is most likely to misread as "it
						// is about to happen".
						markCall(outcome.messageId, call.index, ToolCallUi.State.Declined)
						appendToolResult(
							call,
							DECLINED_MARKER + " The user declined this call.",
							isError = true,
							messageId = outcome.messageId,
						)
						answerSkippedCalls(outcome, after = call.index)
						settled = true
						return false
					}
				}

				markCall(outcome.messageId, call.index, ToolCallUi.State.Running)
				// Off the main thread, which is where this used to run.
				//
				// (A timeout policy layered on top of this would have to reckon
				// with the fact that `withTimeout` only interrupts at suspension
				// points -- see `ToolTimeoutSemanticsTest`.)
				//
				// `runTurn` is launched into `viewModelScope`, whose dispatcher is
				// `Dispatchers.Main.immediate`, and nothing between here and the
				// filesystem switched threads -- so `read_file` read, `write_file`
				// wrote, and every quota check walked the whole workspace tree on
				// the thread that draws the UI. The walks are the sharp edge: a
				// measured `write_file` costs about 106 ms in a 2,000-file
				// workspace, and a turn that writes several files pays it several
				// times over.
				//
				// Only the execution moves. Approval stays above, on the main
				// thread, because it suspends on the user and the continuation is
				// resumed from the UI.
				val result = withContext(Dispatchers.IO) { tool.execute(parsed) }
				markCall(
					outcome.messageId,
					call.index,
					if (result.isError) ToolCallUi.State.Failed else ToolCallUi.State.Done,
				)
				appendToolResult(call, result.content, result.isError, outcome.messageId)

				// Recorded whether it succeeded or not.
				//
				// A failed write still means the turn *tried* to change the
				// workspace, and regenerating would try again. Marking only the
				// successes would leave a reply that attempted a delete and was
				// refused looking like plain text -- which is precisely the reply
				// someone would be most inclined to re-run, and on a second attempt
				// the refusal might go the other way.
				if (tool.mutates) {
					// Marking the message is the whole of the bookkeeping.
					//
					// There used to be a `currentTurnMutated = true` here as well,
					// because `ChatUiState` stored the flag. It stores nothing now
					// (see the property's own note): the flag is read off the
					// transcript, so recording the mutation *is* raising the flag,
					// and there is no second copy to go stale. This line is
					// therefore load-bearing and the removed one was the bug.
					_state.update { s ->
						s.copy(
							messages = s.messages.map {
								if (it.id == outcome.messageId) it.copy(mutated = true) else it
							},
						)
					}
					// Written separately, and this is not optional: the reply was
					// persisted when the model stopped streaming, and its tools run
					// afterwards. Updating only the in-memory copy makes the guard
					// vanish on the next launch, which is the worst version of it --
					// the control would come back and the write would happen again.
					repository.markMutated(outcome.messageId)
				}
			}
		} finally {
			// The stop path, and the reason this is a `finally` rather than a
			// statement after the loop.
			//
			// Pressing Stop cancels the turn, which unwinds this loop from
			// inside the body -- so the calls after the one that was running
			// were recorded in `toolCallsJson` and never answered. That is the
			// same invalid transcript as declining the first call, reached a
			// different way.
			//
			// `settled` keeps the normal path from paying a second pass: when
			// every call ran, there is nothing to answer. `NonCancellable`
			// because the cancellation has already happened by the time this
			// runs and each appended result is an insert.
			//
			// Deliberately not around the whole `try`: that would make Stop
			// unable to interrupt a tool, which is the one thing it exists to
			// do. Only the bookkeeping is protected.
			if (!settled) {
				withContext(NonCancellable) { answerSkippedCalls(outcome, after = reached) }
			}
		}
		return true
	}

	// --- approval plumbing ---------------------------------------------------

	private var approvalContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

	private suspend fun awaitApproval(): Boolean =
		kotlinx.coroutines.suspendCancellableCoroutine { cont ->
			approvalContinuation = cont
		}

	private suspend fun continueAfterApproval(pending: PendingApproval, approved: Boolean) {
		approvalContinuation?.let { cont ->
			approvalContinuation = null
			if (cont.isActive) cont.resumeWith(Result.success(approved))
		}
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * What the model can do.
	 *
	 * ## The selection is applied last, and to everything
	 *
	 * Every tool goes through the same filter, including the ones with no
	 * dependency. It would be easy to let `read_time` through unasked because it
	 * is harmless, and that is exactly the shape of hole this setting exists to
	 * close: "harmless" is a judgement the app makes, and an assistant set up
	 * for chat alone should have no tools because someone said so.
	 *
	 * ## Why a missing workspace is reported rather than swallowed
	 *
	 * The first version returned an empty list on failure, and for file tools
	 * that was merely quiet: the agent had no hands and would say so. It becomes
	 * a security question the moment a tool's availability is a boundary,
	 * because then "the tools could not be built" and "the sandbox failed to
	 * start" are the same code path -- and failing open is the wrong direction.
	 * It is reported now, and the turn does not run on a silent failure.
	 */
	private fun buildTools(): List<Tool> {
		val entity = conversation

		// The conversation's own copy wins, falling back to the assistant's.
		// Copied at creation so that editing an assistant cannot change what a
		// conversation already held is able to do.
		val selected = entity?.tools ?: agent?.tools

		if (entity == null) {
			return listOf(TimeTool()).filter { ToolCatalog.permits(selected, it.name) }
		}

		val fileTools = runCatching { FileTools.all(repository.workspace(entity)) }
			.getOrElse { failure ->
				_state.update {
					it.copy(
						error = "The workspace for this conversation could not be opened, " +
							"so file tools are unavailable: ${failure.message}",
					)
				}
				emptyList()
			}

		return buildList {
			addAll(fileTools)
			add(TimeTool())
			// Search is registered only when something can answer it. A tool that
			// is present and always fails teaches the model that the tool is
			// broken and it keeps retrying; one that is absent teaches it that it
			// cannot search, and it says so.
			searchProvider?.let { add(WebSearchTool(it)) }
		}.filter { ToolCatalog.permits(selected, it.name) }
	}

	private fun spec(tool: Tool) = ToolSpec(
		name = tool.name,
		description = tool.description,
		parameters = tool.parameters,
	)

	/** A sentence the user can act on, rather than the raw JSON. */
	private fun describeCall(call: ToolCallUi, args: JsonObject): String = when (call.displayName) {
		"delete_file" -> "Delete ${stringArg(args, "path") ?: "a path"} from this conversation's folder?"
		"write_file" -> "Write to ${stringArg(args, "path") ?: "a file"} in this conversation's folder?"
		else -> "Run ${call.displayName}?"
	}

	private fun stringArg(args: JsonObject, name: String): String? =
		args[name]?.let { runCatching { it.toString().trim('"') }.getOrNull() }

	/**
	 * The approval rules in force for this conversation.
	 *
	 * A failure here returns no rules rather than throwing. Failing to *read*
	 * the configuration is not a reason to refuse every tool call -- the
	 * unconfigured state is the default the app ships with, and a database that
	 * cannot answer one query is about to fail the tool call anyway.
	 */
	private suspend fun loadPolicies(): PolicySet = runCatching {
		val entity = repository.conversation(conversationId)?.entity
		repository.policies().forConversation(conversationId, entity?.agentId)
	}.getOrElse { PolicySet.None }

	/**
	 * The path a policy rule should be matched against, or null.
	 *
	 * Resolved through the workspace and then relativised, **never** used as the
	 * model wrote it. A rule is stored under a normalised relative path, so
	 * matching a raw argument would let `a/../private/x.md` -- or an absolute
	 * path, or a leading `./` -- compare against a rule key that means something
	 * else. This is the same reason `SessionWorkspace.resolve` exists, applied
	 * to a table lookup instead of a file open.
	 *
	 * Returns null when the arguments name no path (`read_time`, `web_search`),
	 * which is the signal that only a tool-keyed rule can apply.
	 */
	private fun resolvedPolicyPath(args: JsonObject): String? {
		val raw = stringArg(args, "path") ?: return null
		val entity = conversation ?: return null
		val workspace = runCatching { repository.workspace(entity) }.getOrNull() ?: return null
		return when (val r = workspace.resolve(raw)) {
			is SessionWorkspace.Resolution.Ok -> workspace.relativize(r.file)
			// A path the sandbox rejects is refused later by the tool itself.
			// Reporting it as "no path" here means the file's own rule does not
			// apply, which is correct: there is no file.
			is SessionWorkspace.Resolution.Rejected -> null
		}
	}

	/**
	 * The call's arguments, or null after recording why they were unusable.
	 *
	 * ## Why this suspends instead of launching
	 *
	 * The failure was reported from a **detached** coroutine, so the caller
	 * moved on before the tool result existed. The next thing the caller does
	 * is loop back into `streamOneTurn`, which rebuilds the wire history from
	 * the transcript -- and the transcript has to answer every `tool_call` the
	 * assistant message declared. Sending a turn with a call and no result is a
	 * request the API rejects, and the result then landed afterwards, out of
	 * order, as a permanent unmatched tool message.
	 *
	 * Awaiting it costs one write and removes the race entirely.
	 */
	private suspend fun parseArguments(call: ToolCallUi, messageId: String): JsonObject? =
		try {
			Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }) as? JsonObject
				?: buildJsonObject { }
		} catch (e: Exception) {
			// Arguments that never became valid JSON. Reported rather than
			// retried: the model produced them, and it is the one that can fix
			// them on the next turn.
			appendToolResult(
				call,
				"could not parse arguments: ${e.message}",
				isError = true,
				messageId = messageId,
			)
			null
		}

	private suspend fun appendToolResult(
		call: ToolCallUi,
		content: String,
		isError: Boolean,
		messageId: String,
	) {
		val parent = _state.value.messages.lastOrNull()?.id
		val entity = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parent,
			role = "tool",
			content = content,
			error = if (isError) content else null,
			createdAt = System.currentTimeMillis(),
			toolCallId = call.id,
			toolName = call.displayName,
		)
		repository.appendMessage(entity, parent)
		_state.update { it.copy(messages = it.messages + entity.toUi()) }
	}

	private fun markCall(messageId: String, index: Int, state: ToolCallUi.State) {
		updateMessage(messageId) { m ->
			m.copy(
				toolCalls = m.toolCalls.map { if (it.index == index) it.copy(state = state) else it },
			)
		}
	}

	/**
	 * The transcript as the provider needs it.
	 *
	 * Tool results are separate messages in storage but must be attached to the
	 * assistant turn that requested them on the wire, which is what this
	 * reassembles. The assistant's system prompt goes first, because it is the
	 * standing instruction for the whole conversation rather than something
	 * said once.
	 */
	private fun buildWireHistory(): List<ChatMessage> {
		val visible = _state.value.messages.dropLast(1)
		val out = mutableListOf<ChatMessage>()

		agent?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
			out += ChatMessage(Role.System, it)
		}

		for (m in visible) {
			when {
				m.toolResultFor != null -> out += ChatMessage(
					role = Role.Tool,
					content = m.plainText,
					toolCallId = m.toolResultFor,
				)

				m.fromUser -> out += ChatMessage(
					role = Role.User,
					content = m.plainText,
					// Read here rather than at attachment time: a transcript is
					// rebuilt on every turn and holding every image's bytes in
					// memory for the conversation's lifetime would grow without
					// bound. Through the sandbox, like every other file access.
					images = loadImages(m.attachments),
				)

				else -> out += ChatMessage(
					role = Role.Assistant,
					content = m.plainText,
					reasoning = m.reasoningText.takeIf { it.isNotBlank() },
					toolCalls = m.toolCalls.mapNotNull { c ->
						val id = c.id ?: return@mapNotNull null
						com.inhuman.core.ai.ToolCall(id, c.displayName, c.argumentsJson.ifBlank { "{}" })
					},
				)
			}
		}
		return out
	}

	/**
	 * Read attachment bytes for a message about to be sent.
	 *
	 * A file that has been deleted since it was attached is skipped rather than
	 * failing the turn. It is the user's own workspace and they may have tidied
	 * it; losing the message over a picture they removed would be the wrong
	 * trade.
	 */
	private fun loadImages(attachments: List<PendingAttachment>): List<com.inhuman.core.ai.ChatImage> {
		if (attachments.isEmpty()) return emptyList()
		val entity = conversation ?: return emptyList()
		val workspace = runCatching { repository.workspace(entity) }.getOrNull() ?: return emptyList()
		return attachments.mapNotNull { attachment ->
			val file = (workspace.resolve(attachment.relativePath) as? SessionWorkspace.Resolution.Ok)
				?.file ?: return@mapNotNull null
			if (!file.exists() || file.length() > workspace.limits.maxReadBytes) return@mapNotNull null
			runCatching {
				com.inhuman.core.ai.ChatImage(attachment.mimeType, file.readBytes())
			}.getOrNull()
		}
	}

	/**
	 * Attachments as stored, so the transcript can rebuild them.
	 *
	 * Paths and MIME types only -- the bytes stay in the workspace, which is the
	 * single source of truth for what an image contains and stays correct if the
	 * user replaces the file.
	 */
	private fun encodeAttachments(attachments: List<PendingAttachment>): String =
		attachments.joinToString(prefix = "[", postfix = "]") { a ->
			buildJsonObject {
				put("path", a.relativePath)
				put("mime", a.mimeType)
				put("size", a.sizeBytes)
			}.toString()
		}



	private fun encodeCalls(calls: List<ToolCallUi>): String = buildJsonObject {
		put("v", 1)
	}.toString().let { _ ->
		// Stored as a small JSON array so a schema change is detectable.
		calls.joinToString(prefix = "[", postfix = "]") { c ->
			buildJsonObject {
				put("index", c.index)
				c.id?.let { put("id", it) }
				c.name?.let { put("name", it) }
				put("arguments", c.argumentsJson)
			}.toString()
		}
	}

	private fun updateParts(
		id: String,
		reasoning: String,
		answer: String,
		calls: List<ToolCallUi>,
	) {
		updateMessage(id) { m ->
			val parts = buildList {
				if (reasoning.isNotEmpty()) {
					add(AssistantPart("r", AssistantPart.Kind.Reasoning, reasoning))
				}
				if (answer.isNotEmpty()) {
					add(AssistantPart("t", AssistantPart.Kind.Text, answer))
				}
			}
			m.copy(
				parts = parts,
				plainText = answer,
				reasoningText = reasoning,
				toolCalls = calls,
			)
		}
	}

	/**
	 * Publish the reply so far: the text, and the new part of it to the
	 * incremental Markdown parser.
	 *
	 * A method rather than a lambda declared inside the streaming `try`, because
	 * the `finally` has to be able to call it and a local function is not in
	 * scope there. That scoping detail is the whole of the defect it fixes: the
	 * throttle's comment promised the finally published the last window, and it
	 * could not have.
	 */
	private fun publishParts(
		id: String,
		reasoning: StringBuilder,
		answer: StringBuilder,
		calls: Map<Int, ToolCallUi>,
		cursor: DeltaCursor,
	) {
		updateParts(id, reasoning.toString(), answer.toString(), calls.values.toList())

		// Hand the *new* answer text to the incremental parser. Only the
		// answer: reasoning is rendered as plain dimmed text in its own
		// collapsed section, so a reasoning delta appended here would be parsed
		// as part of the answer. The cursor guarantees each character is
		// delivered exactly once -- re-sending a prefix duplicates the reply and
		// skipping a slice loses text, and neither is visible after the stream
		// has ended.
		cursor.next(answer.toString())?.let { delta -> replyDeltas[id]?.trySend(delta) }
	}

	private fun updateMessage(id: String, transform: (ChatMessageUi) -> ChatMessageUi) {
		_state.update { s ->
			s.copy(messages = s.messages.map { if (it.id == id) transform(it) else it })
		}
	}

	private fun FinishReason.describe(): String = when (this) {
		FinishReason.Stop -> "stop"
		FinishReason.ToolCalls -> "wants a tool"
		FinishReason.Length -> "hit the output limit"
		FinishReason.ContentFilter -> "filtered"
		FinishReason.Cancelled -> "stopped"
		FinishReason.Unknown -> "ended early"
	}

	companion object {
		const val DEFAULT_MODEL = "deepseek-flash"

		/**
		 * Prefix on the tool result that records a refusal.
		 *
		 * ## Why a marker in the text rather than a column
		 *
		 * `ToolCallUi.State.Declined` existed from the start but was never
		 * assigned, so a declined call drew as "ready" -- including after a
		 * reload, because the tool-call list stored on the message carries no
		 * per-call outcome and `decodeCalls` has only ever been able to say
		 * "done". The two callers that render this need the same answer, and
		 * only one of them has the live view model.
		 *
		 * The result text is where the outcome already lives, so it is where
		 * the marker goes. A dedicated column would be a schema version and a
		 * migration for one chip's label, and the information it would hold is
		 * a strict subset of what is in this string.
		 *
		 * ## The cost, stated
		 *
		 * A transcript written before this change reads back as "done" for a
		 * call that was refused; the tool result underneath it still says the
		 * user declined, so nothing is lost, only the chip's word for it.
		 */
		internal const val DECLINED_MARKER = "[declined]"

		/**
		 * Marks a result for a call that was recorded but never run.
		 *
		 * Distinct from [DECLINED_MARKER] on purpose: the user declined one
		 * call, and it would be wrong to report the rest as refused. Distinct
		 * from a plain error too, because nothing was attempted.
		 */
		internal const val SKIPPED_MARKER = "[skipped]"

		/**
		 * Marks a result for a call the configured rules refused.
		 *
		 * Distinct from [DECLINED_MARKER], which is the user answering a
		 * question. This is a rule the user configured earlier answering one for
		 * them, and the transcript should not read as though they were asked
		 * again and said no.
		 */
		internal const val REFUSED_MARKER = "[refused]"

		/**
		 * Written onto a reply the user stopped part-way.
		 *
		 * The row keeps whatever had arrived, so the transcript shows the
		 * fragment that was actually produced and billed -- and says why it
		 * stops there, rather than looking like a model that trailed off.
		 */
		const val STOPPED_MESSAGE = "Stopped before the reply finished."

		/**
		 * Ceiling on model round trips within one user message.
		 *
		 * A model that keeps calling tools would otherwise generate forever at
		 * the user's expense, and because every round re-sends the whole
		 * transcript the cost of a runaway loop grows quadratically.
		 */
		const val MAX_TOOL_ROUNDS = 8

		/**
		 * How often a streaming reply is copied into the visible state.
		 *
		 * 40 ms is 25 updates a second, against a display that draws 60, and it
		 * is fast enough that text still reads as arriving. What it avoids is
		 * re-copying the whole accumulated answer for every token; see the
		 * comment at the top of the collect.
		 */
		const val STREAM_PUBLISH_INTERVAL_MS = 40L
	}
}

private fun roleFromWire(value: String): Role = when (value) {
	"system" -> Role.System
	"assistant" -> Role.Assistant
	"tool" -> Role.Tool
	else -> Role.User
}

private fun MessageEntity.toUi(
	branches: Branches? = null,
	declined: Set<String> = emptySet(),
	/**
	 * The delta stream for this message, when it is the reply arriving now.
	 *
	 * Passed in rather than looked up: `toUi` is a row-to-UI conversion with no
	 * access to the view model's live map, and a row that asked the map for
	 * itself would be doing it on every recomposition of every row.
	 */
	deltas: kotlinx.coroutines.channels.Channel<String>? = null,
): ChatMessageUi {
	val parts = buildList {
		reasoning?.takeIf { it.isNotBlank() }?.let {
			add(AssistantPart("r", AssistantPart.Kind.Reasoning, it))
		}
		if (content.isNotBlank()) {
			add(AssistantPart("t", AssistantPart.Kind.Text, content))
		}
	}
	return ChatMessageUi(
		id = id,
		parentId = parentId,
		fromUser = roleFromWire(role) == Role.User,
		isToolResult = roleFromWire(role) == Role.Tool,
		toolResultFor = toolCallId,
		toolName = toolName,
		parts = parts,
		plainText = content,
		reasoningText = reasoning.orEmpty(),
		// Present only while this reply is the one arriving; see replyDeltas.
		deltas = deltas,
		finishReason = finishReason,
		error = error,
		usage = if (promptTokens != null || completionTokens != null || timeToFirstTokenMs != null) {
			UsageUi(
				promptTokens = promptTokens,
				completionTokens = completionTokens,
				cachedPromptTokens = cachedPromptTokens,
				reasoningTokens = reasoningTokens,
				timeToFirstTokenMs = timeToFirstTokenMs,
				generationMs = generationMs,
			)
		} else {
			null
		},
		toolCalls = toolCallsJson?.let { raw ->
			decodeCalls(raw) { call -> call.id != null && call.id in declined }
		}.orEmpty(),
		branches = branches,
		branchParentId = parentId,
		mutated = mutated,
		attachments = decodeAttachments(attachmentsJson),
	)
}

/**
 * Attachments as stored, so a loaded transcript can rebuild them.
 *
 * Paths and MIME types only -- the bytes stay in the workspace, which is the
 * single source of truth for what an image contains and stays correct if the
 * user replaces the file.
 *
 * Top-level rather than a member of the view model because `toUi` is
 * top-level and converts rows without one.
 */
private fun decodeAttachments(raw: String?): List<PendingAttachment> {
	if (raw.isNullOrBlank()) return emptyList()
	return runCatching {
		(Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray)
			.orEmpty()
			.mapNotNull { item ->
				val obj = item as? JsonObject ?: return@mapNotNull null
				val path = obj["path"]?.toString()?.trim('"') ?: return@mapNotNull null
				PendingAttachment(
					relativePath = path,
					mimeType = obj["mime"]?.toString()?.trim('"') ?: "image/*",
					sizeBytes = obj["size"]?.toString()?.trim('"')?.toLongOrNull() ?: 0L,
				)
			}
	}.getOrDefault(emptyList())
}

/**
 * Stored as a JSON array of small objects; a malformed value is ignored.
 *
 * @param wasDeclined which calls the transcript records as refused. A call's
 *   outcome is not in this column -- the column holds what the model asked for,
 *   not what came of it -- so the one state that is not "done" has to be
 *   supplied from the tool results alongside it.
 */
private fun decodeCalls(
	raw: String,
	wasDeclined: (ToolCallUi) -> Boolean = { false },
): List<ToolCallUi> = runCatching {
	Json.parseToJsonElement(raw).let { el ->
		(el as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { item ->
			val obj = item as? JsonObject ?: return@mapNotNull null
			val call = ToolCallUi(
				index = obj["index"]?.toString()?.toIntOrNull() ?: 0,
				id = obj["id"]?.toString()?.trim('"'),
				name = obj["name"]?.toString()?.trim('"'),
				argumentsJson = obj["arguments"]?.toString()?.trim('"').orEmpty(),
				state = ToolCallUi.State.Done,
			)
			if (wasDeclined(call)) call.copy(state = ToolCallUi.State.Declined) else call
		}
	}
}.getOrDefault(emptyList())
