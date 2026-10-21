package com.inhuman.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.TurnNotifications
import com.inhuman.TurnStatus
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
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.Branches
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.SnapshotStore
import com.inhuman.core.data.SnapshotStats
import com.inhuman.core.data.SnapshotTurn
import com.inhuman.core.data.WorkspaceChange
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
import kotlinx.coroutines.withTimeoutOrNull
import com.inhuman.core.ai.tools.FetchProvider
import com.inhuman.core.ai.tools.SearchProvider
import com.inhuman.core.ai.tools.SshExecTool
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.ai.tools.TimeTool
import com.inhuman.core.ai.tools.WebFetchTool
import com.inhuman.core.ai.tools.WebSearchTool
import com.inhuman.core.ai.ssh.SshTransport
import com.inhuman.core.files.tools.FileTools
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Locale
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
 * The loop is bounded by the user's tool-round setting (default
 * [DEFAULT_TOOL_ROUNDS]). A model that keeps calling tools would otherwise
 * generate forever at the user's expense, and every iteration re-sends the
 * whole transcript, so an unbounded loop grows quadratically.
 */
class ChatViewModel(
	private val repository: ConversationRepository,
	private val agents: AgentRepository,
	private val secure: SecureStore,
	private val conversationId: String,
	/**
	 * Where the web tools get their answers, or null when none is configured.
	 *
	 * ## Why these are constructor parameters and not fields with a default
	 *
	 * The providers are interfaces, and the concrete ones are chosen at the
	 * wiring point. A null here is not an oversight waiting to be filled in with
	 * a hardcoded vendor -- it is the seam. Wiring a service directly into the
	 * view model is the thing that would have to be unpicked to change it.
	 *
	 * When nothing is passed the tool is **not registered at all**, rather than
	 * registered and always failing. The distinction matters: a model given a
	 * tool that errors will retry it and end up telling the user that search is
	 * broken, whereas a model without the tool will say it cannot search, which
	 * is true.
	 *
	 * Search and fetch are separate seams because they are separate paid APIs
	 * with different failure modes, and one can exist without the other. Each
	 * closure is evaluated per turn, so changing a web backend or key in
	 * Settings takes effect without reopening the conversation.
	 */
	private val searchProvider: () -> SearchProvider? = { null },
	private val fetchProvider: () -> FetchProvider? = { null },
	/** The SSH transport, or null when the feature is not wired. */
	private val sshTransport: SshTransport? = null,
	/**
	 * Whether the global SSH switch is on.
	 *
	 * Read per turn rather than copied: the user can change it in Settings
	 * while this view model stays alive, and a tool that is still registered
	 * after being switched off is a gate that opens the wrong way.
	 */
	private val sshToolEnabled: () -> Boolean = { false },
	/**
	 * Called whenever a stream starts or stops.
	 *
	 * The app layer owns the foreground service; the view model deliberately
	 * knows nothing about Android services or notifications.
	 */
	private val onTurnStatus: (TurnStatus) -> Unit = {},
) : ViewModel() {

	private val _state = MutableStateFlow(ChatUiState())
	val state: StateFlow<ChatUiState> = _state.asStateFlow()

	/** The last actual provider body, for the read-only wire inspector. */
	private val _wire = MutableStateFlow<WireSnapshot?>(null)
	val wire: StateFlow<WireSnapshot?> = _wire.asStateFlow()

	private var turnStartedAt = 0L

	init {
		viewModelScope.launch {
			_state.map { notificationStatus() }
				.distinctUntilChanged()
				.collect { status ->
					if (status.streaming) {
						TurnNotifications.registerStop(conversationId) { stop() }
					} else {
						TurnNotifications.clearStop(conversationId)
					}
					onTurnStatus(status)
				}
		}
	}

	/**
	 * A status with only the facts the notification layer needs.
	 *
	 * It deliberately does not include the transcript: a new token must not
	 * republish a notification, and the phase changes are enough to tell the
	 * user what the turn is doing. The preview is filled only on completion.
	 */
	private fun notificationStatus(): TurnStatus {
		val current = _state.value
		val streaming = current.isStreaming
		if (streaming && turnStartedAt == 0L) turnStartedAt = System.currentTimeMillis()
		val lastAssistant = current.messages.lastOrNull { !it.fromUser && !it.isToolResult }
		val call = lastAssistant?.toolCalls?.lastOrNull {
			it.state == ToolCallUi.State.Streaming ||
				it.state == ToolCallUi.State.Ready ||
				it.state == ToolCallUi.State.Running
		}
		val phase = when {
			current.pendingApproval != null -> "Waiting for approval"
			current.pendingSshConsent != null -> "Waiting for SSH consent"
			current.isCompacting -> "Compacting context"
			call != null -> "Using ${call.displayName}"
			streaming && lastAssistant?.plainText.isNullOrBlank() -> "Thinking"
			streaming -> "Writing reply"
			else -> "Finished"
		}
		val status = TurnStatus(
			conversationId = conversationId,
			title = current.title,
			phase = phase,
			startedAt = if (turnStartedAt == 0L) System.currentTimeMillis() else turnStartedAt,
			streaming = streaming,
			preview = if (!streaming) lastAssistant?.plainText?.take(240) else null,
			failed = !streaming && current.error != null,
		)
		if (!streaming) turnStartedAt = 0L
		return status
	}

	private var conversation: ConversationEntity? = null
	/** The provider profile this conversation copied at creation. */
	private var providerId: String = com.inhuman.core.data.db.ProviderIds.DEEPSEEK
	private var providerName: String = "DeepSeek"
	private var streamJob: Job? = null
	private var sshAllowed: Boolean = false

	/** The user message that anchors the in-flight turn's rollback snapshot. */
	private var turnAnchorId: String? = null
	private var turnSnapshotReady = false
	private var turnIrreversible = false

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
	 * Answer snapshots for the reply currently arriving, keyed by message id.
	 *
	 * ## Why snapshots and not deltas
	 *
	 * The incremental renderer appends whatever it is given. The previous
	 * protocol sent only the new slice. That is correct for a collector that
	 * started before the first slice, but not when the first composition lands
	 * after one: the catch-up appended the accumulated text and the queued
	 * first slice was appended again, producing `HiHi there!`. A conflated
	 * channel carrying the **accumulated answer** lets `DeltaCursor` decide the
	 * missing suffix; replaying an older snapshot is a no-op.
	 *
	 * Conflated is enough because the accumulated text is also in the message
	 * state and the cursor catches up from it. If the renderer falls behind,
	 * the latest snapshot is the one that matters; no text is lost because it
	 * contains every earlier slice.
	 *
	 * Only the answer gets one. Reasoning is rendered as plain dimmed text in
	 * its own collapsed section, never as Markdown.
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
			providerId = c?.providerId ?: agent?.providerId
				?: com.inhuman.core.data.db.ProviderIds.DEEPSEEK
			providerName = runCatching { agents.provider(providerId)?.name }
				.getOrNull() ?: "Provider"
			model = c?.model ?: agent?.model
			reasoningEffort = c?.reasoningEffort ?: agent?.reasoningEffort
			thinking = c?.thinking ?: agent?.thinking ?: false

			refreshConfiguration()
			reload()
			publishModelState()
			Diag.i(
				"chat",
				"open conv=${Diag.shortId(conversationId)} " +
					"model=${model ?: "provider-default"} agent=${Diag.shortId(agent?.id)} " +
					"messages=${_state.value.messages.size} demo=${demoEnabled()}",
			)
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
		val fetchedResult = runCatching { agents.models(providerId) }
		fetchedResult.exceptionOrNull()?.let {
			// The failure used to be swallowed into the fallback with no trace:
			// a model list that never loads looks exactly like one that loaded
			// and listed nothing. The message is the one the user sees in the
			// picker, so it belongs in the record too.
			Diag.w("models", "fetch failed: ${it.javaClass.simpleName}: ${it.message}")
		}
		val fetched = fetchedResult.getOrNull()?.getOrNull()
			?: demoModelsIfEnabled(demo)

		// Resolve all the way to a concrete id.
		//
		// "Provider default" is a real choice for *which model to send*, but it
		// is not an answer to "which effort levels does this model have" -- and
		// the id that will actually be sent is the first model the provider
		// lists when nothing explicit is chosen. Leaving this null made the
		// effort control report that a model with three levels had none,
		// because it looked up capabilities by a null id.
		//
		// The API's own default is the honest fallback when the list has not
		// arrived: it is what the provider will use if nothing else is chosen.
		val id = model ?: agent?.model ?: fetched?.firstOrNull()?.id ?: "default"

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
		Diag.d(
			"models",
			"resolved id=$id window=${contextWindow ?: "-"} " +
				"label=${_state.value.modelLabel}",
		)
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
	 * `AgentRepository.models()` builds the selected provider, and with no key
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

	/**
	 * The provider's model list, for the picker.
	 *
	 * Returns a [Result] rather than an already-unwrapped list. It used to be
	 * `agents.models(force = true).getOrNull().orEmpty()`, which collapsed two
	 * different answers into one: a provider that lists nothing, and a fetch that
	 * failed. The picker wrapped the call in `runCatching` and read
	 * `exceptionOrNull()`, so it was reading a failure that could never arrive --
	 * its error branch was unreachable and a user with no API key saw an empty
	 * dialog with no explanation of why.
	 */
	/**
	 * The provider's model list, from the in-memory cache unless [force].
	 *
	 * The picker reads the cached copy first and refreshes in the background.
	 * That copy is already there because opening the conversation resolved the
	 * effective model, so blocking the picker on a forced refetch made a fast
	 * dialog depend on a slow network for no new information.
	 */
	suspend fun models(force: Boolean = false): Result<List<RemoteModel>> =
		agents.models(providerId = providerId, force = force)

	/** The models already held in memory, without making a request. */
	suspend fun cachedModels(): List<RemoteModel> =
		agents.cachedModelList(providerId).orEmpty()

	/**
	 * Switch the model for this conversation only.
	 *
	 * Not written back to the agent: switching models mid-conversation is a
	 * decision about *this* thread, and editing the assistant would change every
	 * other conversation held with it.
	 */
	fun setModel(modelId: String?) {
		model = modelId
		Diag.i("config", "model conv=${Diag.shortId(conversationId)} -> ${modelId ?: "provider-default"}")
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
		Diag.i("config", "thinking conv=${Diag.shortId(conversationId)} -> $enabled")
		if (!enabled) reasoningEffort = null
		viewModelScope.launch {
			repository.setReasoning(conversationId, reasoningEffort, thinking)
			publishModelState()
		}
	}

	fun setReasoningEffort(level: String?) {
		reasoningEffort = level
		Diag.i("config", "effort conv=${Diag.shortId(conversationId)} -> ${level ?: "-"}")
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
				Diag.w(
					"attach",
					"refused: model=${_state.value.effectiveModel ?: "-"} reports no image input",
				)
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
				Diag.w("attach", "refused: workspace did not open conv=${Diag.shortId(entity.id)}")
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
				is WorkspaceTransfer.Outcome.Failed -> {
					Diag.w(
						"attach",
						"import failed name=$displayName bytes=? reason=${outcome.reason}",
					)
					_state.update { it.copy(error = outcome.reason) }
				}

				is WorkspaceTransfer.Outcome.Ok -> {
					// The send path reads images with `maxReadBytes` and skips
					// anything larger. Importing up to `maxWriteBytes` and only
					// discovering the mismatch at send time looked like the image
					// had been attached and then silently vanished, so the two
					// limits are reconciled here and the staged file is removed.
					val imageLimit = workspace.limits.maxReadBytes
					if (outcome.bytes > imageLimit) {
						val staged = (workspace.resolve(outcome.relativePath) as? SessionWorkspace.Resolution.Ok)?.file
						runCatching { staged?.delete() }
						Diag.w(
							"attach",
							"refused too large path=${outcome.relativePath} " +
								"bytes=${outcome.bytes} limit=$imageLimit",
						)
						_state.update {
							it.copy(
								error = "Images must be ${mib(imageLimit)} or smaller; " +
									"that one is ${mib(outcome.bytes)}.",
							)
						}
						return@launch
					}
					Diag.d(
						"attach",
						"imported path=${outcome.relativePath} bytes=${outcome.bytes} " +
							"mime=$mimeType",
					)
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
		var entities = loaded?.messages.orEmpty()

		// A process can die while an approval or SSH-consent dialog is open.
		// The assistant row with its `tool_calls` was already persisted, but
		// the tool rows were not, and the next request is then rejected by
		// DeepSeek: "assistant message with 'tool_calls' must be followed by
		// tool messages". Close the dangling tail with explicit interrupted
		// results before the conversation is read or sent again.
		if (repairDanglingToolCalls(entities)) {
			entities = repository.conversation(conversationId)?.messages.orEmpty()
		}

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
		sshAllowed = loaded?.entity?.sshAllowed ?: false
		val workspace = loaded?.entity?.let { entity ->
			runCatching { repository.workspaceRecord(entity.workspaceId) }
				.onFailure { Diag.w("workspace", "workspace lookup failed", it) }
				.getOrNull()
		}
		val workspaceConversations = loaded?.entity?.let { entity ->
			runCatching { repository.workspaceConversationCount(entity.workspaceId) }
				.getOrDefault(1)
		} ?: 1
		val sharedWorkspace = workspace?.isPermanent == true && workspaceConversations > 1

		// What the last turn can safely undo. A mutating turn needs a snapshot
		// that was captured before its first write; an irreversible tool means
		// no rollback at all, because deleting the transcript would leave the
		// remote effects untouched.
		val lastUser = entities.lastOrNull { it.role == "user" }
		val lastTurn = lastUser?.let { user ->
			entities.dropWhile { it.id != user.id }
		}.orEmpty()
		val irreversible = lastTurn.any { message ->
			decodeCalls(message.toolCallsJson.orEmpty()).any { call ->
				ToolCatalog.all.firstOrNull { it.name == call.displayName }?.reversible == false
			}
		}
		val hasSnapshot = lastUser != null &&
			runCatching {
				repository.hasStartCheckpoint(conversationId, lastUser.id)
			}.getOrElse { failure ->
				Diag.w("rollback", "could not read snapshot state", failure)
				false
			}
		val snapshotTurns = runCatching { repository.snapshotTurns(conversationId) }
			.getOrElse { failure ->
				Diag.w("rollback", "could not list snapshots", failure)
				emptyList<SnapshotTurn>()
			}
		// One read of the directories, not two: `snapshotTurns` already carries
		// each turn's bytes, so the totals need no second traversal.
		val snapshotStats = SnapshotStats(
			turns = snapshotTurns.size,
			bytes = snapshotTurns.sumOf { it.bytes },
		)
		// A snapshot is stronger evidence than the `mutated` row: the process
		// can die after a tool changed the workspace and before markMutated is
		// written. If the snapshot exists, restore it even when every row still
		// says "not mutated".
		val mutated = lastTurn.any { it.mutated } || hasSnapshot
		val rollbackAvailable = lastUser != null && !irreversible && !sharedWorkspace &&
			(!mutated || hasSnapshot)
		val rollbackBlockedReason = when {
			lastUser == null -> null
			irreversible -> "This turn ran SSH commands; remote effects cannot be rolled back."
			sharedWorkspace ->
				"Files are shared with other conversations in this workspace, so " +
					"rewinding this turn's file changes is disabled."
			mutated && !hasSnapshot ->
				"The workspace snapshot for this turn is missing, so it cannot be rewound safely."
			else -> null
		}
		val regenerationPoints = if (sharedWorkspace) {
			emptyList()
		} else {
			regenerationPointsFor(lastUser, lastTurn)
		}
		// A summary is active only while its boundary is on the active path and
		// something follows it. A stale row from a branch that was switched away
		// from is ignored rather than applied to the wrong prefix.
		val compaction = runCatching { repository.contextSummary(conversationId) }
			.getOrNull()
			?.let { summary ->
				val boundary = entities.indexOfFirst { it.id == summary.throughMessageId }
				if (boundary < 0 || boundary >= entities.lastIndex) {
					null
				} else {
					ContextCompactionUi(
						throughMessageId = summary.throughMessageId,
						summary = summary.summary,
						omittedTurns = entities.take(boundary + 1).count { it.role == "user" },
						omittedMessages = boundary + 1,
					)
				}
			}
		turnAnchorId = lastUser?.id
		turnSnapshotReady = hasSnapshot
		turnIrreversible = irreversible

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
				workspaceName = workspace?.name.orEmpty(),
				workspaceIsPermanent = workspace?.isPermanent == true,
				workspaceConversationCount = workspaceConversations,
				messages = rebuilt,
				sshAllowed = sshAllowed,
				pendingSshConsent = null,
				rollbackAvailable = rollbackAvailable,
				rollbackBlockedReason = rollbackBlockedReason,
				snapshotStats = snapshotStats,
				snapshotTurns = snapshotTurns,
				snapshotRetention = snapshotRetention(),
				toolRoundLimit = toolRoundLimit(),
				regenerationPoints = regenerationPoints,
				compaction = compaction,
				// Read off the rebuilt list: the remaining transcript-derived
				// values have to agree with the messages that are on screen.
				context = contextOf(rebuilt),
			)
		}
	}

	/**
	 * Replace the request projection with a summary of older turns.
	 *
	 * The original rows are untouched. This is deliberately user-triggered:
	 * silently changing what the model was told is worse than a visible 400,
	 * and a summary call costs money on a real provider. Demo mode uses a local
	 * deterministic summary so the whole path stays testable offline.
	 */
	fun compact(keepTurns: Int = 2, retryAfter: Boolean = false) {
		val current = _state.value
		if (current.isStreaming || current.isCompacting) return
		val plan = planContextCompaction(current.messages, keepTurns)
		if (plan == null) {
			_state.update {
				it.copy(error = "There are not enough earlier complete turns to compact.")
			}
			return
		}
		val dropped = current.messages.take(plan.boundaryIndex + 1)
		_state.update { it.copy(isCompacting = true, error = null) }

		viewModelScope.launch {
			val demo = demoEnabled()
			val summary = if (demo) {
				demoCompactionSummary(dropped)
			} else {
				summarize(dropped)
			}
			if (summary == null) {
				_state.update { it.copy(isCompacting = false) }
				return@launch
			}
			val now = _state.value.messages
			val boundary = now.indexOfFirst { it.id == plan.throughMessageId }
			if (boundary < 0 || boundary >= now.lastIndex) {
				_state.update {
					it.copy(
						isCompacting = false,
						error = "The conversation changed while it was being summarized, " +
							"so the summary was discarded.",
					)
				}
				return@launch
			}
			runCatching {
				repository.saveContextSummary(
					conversationId = conversationId,
					throughMessageId = plan.throughMessageId,
					summary = summary,
				)
			}.onFailure { failure ->
				Diag.w("compact", "could not save summary", failure)
				_state.update {
					it.copy(
						isCompacting = false,
						error = "Could not store the summary; the full transcript is still used.",
					)
				}
				return@launch
			}
			Diag.i(
				"compact",
				"summary conv=${Diag.shortId(conversationId)} " +
					"through=${Diag.shortId(plan.throughMessageId)} " +
					"omitted=${plan.omittedMessages} chars=${summary.length}",
			)
			_state.update {
				it.copy(
					isCompacting = false,
					compaction = ContextCompactionUi(
						throughMessageId = plan.throughMessageId,
						summary = summary,
						omittedTurns = plan.omittedTurns,
						omittedMessages = plan.omittedMessages,
					),
				)
			}
			if (retryAfter) {
				// The failed request is retried on the compacted projection. The
				// summary is persisted first, so a retry that fails again does not
				// lose the work the user just paid for.
				regenerate()
			}
		}
	}

	/**
	 * Retry the failed answer without resending the user's message.
	 *
	 * [regenerate] keeps the tool definitions for the cache prefix but suppresses
	 * calls, so a network failure after a write cannot execute that write twice.
	 * It also requires an answer row to replace; a failure before one exists has
	 * no retry button because there is nothing safe to re-run.
	 */
	fun retryLastTurn() {
		if (_state.value.isStreaming || _state.value.isCompacting) return
		if (_state.value.messages.none { !it.fromUser && !it.isToolResult }) {
			_state.update { it.copy(error = "There is no answer to retry yet.") }
			return
		}
		regenerate()
	}

	/** Compact older turns, then retry the answer that overflowed. */
	fun compactAndRetry() {
		compact(keepTurns = 2, retryAfter = true)
	}

	/** Return to sending the full transcript. */
	fun clearCompaction() {
		if (_state.value.isStreaming || _state.value.isCompacting) return
		viewModelScope.launch {
			runCatching { repository.clearContextSummary(conversationId) }
				.onFailure { Diag.w("compact", "could not clear summary", it) }
			_state.update { it.copy(compaction = null) }
		}
	}

	private suspend fun summarize(messages: List<ChatMessageUi>): String? {
		if (!agents.hasKey(providerId)) {
			_state.update {
				it.copy(
					isCompacting = false,
					error = "Add an API key for $providerName first, or switch on demo mode.",
				)
			}
			return null
		}
		val provider = agents.provider(providerId)?.let { agents.providerFromRow(it) }
		val summaryModel = model ?: agent?.model
			?: agents.models(providerId).getOrNull()?.firstOrNull()?.id
		if (provider == null || summaryModel == null) {
			_state.update {
				it.copy(
					isCompacting = false,
					error = "No usable model is available to summarize this conversation.",
				)
			}
			return null
		}
		val transcript = compactionTranscript(messages)
		val output = StringBuilder()
		var failure: String? = null
		val completed = withTimeoutOrNull(90_000) {
			provider.stream(
				ChatRequest(
					model = summaryModel,
					messages = listOf(
						ChatMessage(Role.System, COMPACTION_SYSTEM_PROMPT),
						ChatMessage(
							Role.User,
							"Summarize this earlier part of the conversation:\n\n$transcript",
						),
					),
					maxTokens = 900,
					thinking = false,
					userId = "$conversationId-compact",
				),
			).collect { chunk ->
				when (chunk) {
					is StreamChunk.TextDelta -> output.append(chunk.text)
					is StreamChunk.Failure -> failure = chunk.message
					else -> Unit
				}
			}
			true
		}
		if (completed == null) {
			_state.update {
				it.copy(isCompacting = false, error = "The summarizer timed out; nothing changed.")
			}
			return null
		}
		if (failure != null) {
			_state.update {
				it.copy(
					isCompacting = false,
					error = "The summarizer failed: $failure. The full transcript is still used.",
				)
			}
			return null
		}
		val summary = output.toString().trim()
		if (summary.isBlank()) {
			_state.update {
				it.copy(
					isCompacting = false,
					error = "The summarizer returned nothing; the full transcript is still used.",
				)
			}
			return null
		}
		return summary
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
	fun regenerate() {
		if (_state.value.isStreaming) {
			Diag.d("turn", "regenerate ignored: streaming")
			return
		}

		val visible = _state.value.messages
		val lastReply = visible.lastOrNull { !it.fromUser && !it.isToolResult }
		if (lastReply == null) {
			Diag.d("turn", "regenerate ignored: no reply")
			return
		}

		// The retry keeps the tool **definitions** so the request prefix can
		// still match the original tool-bearing prompt and hit the context
		// cache, but sends `tool_choice = "none"`. The model therefore cannot
		// run SSH, write a file or spend money again. Removing the definitions
		// instead (the previous implementation) made the prompt a different,
		// much smaller prefix and guaranteed a cache miss after any tool turn.
		val questionId = lastReply.parentId
		if (questionId == null) {
			Diag.d("turn", "regenerate ignored: reply has no parent")
			return
		}

		val demo = demoEnabled()
		if (!demo && !agents.hasKey(providerId)) {
			Diag.w("turn", "regenerate refused: no API key and demo mode off")
			_state.update {
				it.copy(error = "Add an API key for $providerName first, or switch on demo mode.")
			}
			return
		}

		// Strip everything from the reply onward. runTurn appends the new answer
		// as a child of questionId, so the old reply stops being on the active
		// path without being touched.
		val cut = visible.indexOfFirst { it.id == lastReply.id }
		if (cut < 0) {
			Diag.w("turn", "regenerate ignored: reply is not in the transcript")
			return
		}

		Diag.i(
			"turn",
			"regenerate conv=${Diag.shortId(conversationId)} " +
				"reply=${Diag.shortId(lastReply.id)} messages=${visible.size}",
		)
		_state.update {
			it.copy(messages = it.messages.take(cut), isStreaming = true, error = null)
		}

		val changedFiles = lastReply.changedFiles
		streamJob = viewModelScope.launch {
			runTurn(demo = demo, suppressToolCalls = true)
			// The files belong to the user turn, not to this particular answer:
			// a regenerated sibling must keep the same "changed files" list.
			if (changedFiles.isNotEmpty()) {
				val replacement = _state.value.messages
					.lastOrNull { !it.fromUser && !it.isToolResult }
				if (replacement != null) {
					runCatching {
						repository.setChangedFiles(
							replacement.id,
							encodeChangedFiles(changedFiles),
						)
					}
					_state.update { s ->
						s.copy(
							messages = s.messages.map {
								if (it.id == replacement.id) {
									it.copy(changedFiles = changedFiles)
								} else {
									it
								}
							},
						)
					}
				}
			}
			// Reloading here used to make the completed answer flash. Only the
			// new reply's branch metadata is missing; fetch that and leave the
			// transcript alone.
			refreshReplyBranches(questionId)
			refreshTurnMeta()
		}
	}

	/**
	 * Attach branch metadata to the newest reply without rebuilding the list.
	 *
	 * The old answer is deliberately not in `messages` after a regenerate: it is
	 * a sibling, not the active path. The switcher under the new reply still has
	 * to know both ids, so one branch query is enough.
	 */
	private suspend fun refreshReplyBranches(parentId: String) {
		val branches = runCatching { repository.branches(parentId) }.getOrNull() ?: return
		_state.update { s ->
			val index = s.messages.indexOfLast { !it.fromUser && !it.isToolResult }
			if (index < 0) return@update s
			s.copy(
				messages = s.messages.toMutableList().also { list ->
					val message = list[index]
					if (message.parentId == parentId) {
						list[index] = message.copy(branches = branches)
					}
				},
			)
		}
	}

	/**
	 * Regenerate from a completed tool-group boundary in the last turn.
	 *
	 * Everything below the boundary is deleted, not kept as a sibling: its
	 * workspace effects are restored to that checkpoint, so an old branch
	 * containing tool results that no longer match the workspace would be
	 * misleading. The prefix stays, including its tool results, and the model
	 * is asked again **with tools**.
	 */
	fun regenerateFrom(point: RegenerationPoint) {
		if (_state.value.isStreaming) return
		val visible = _state.value.messages
		val boundaryIndex = visible.indexOfFirst { it.id == point.afterMessageId }
		if (boundaryIndex < 0) {
			_state.update { it.copy(error = "That point is no longer in the conversation.") }
			return
		}
		val boundary = visible[boundaryIndex]
		if (point.blockedReason != null) {
			_state.update { it.copy(error = point.blockedReason) }
			return
		}
		val dropped = visible.drop(boundaryIndex + 1)
		if (dropped.any { it.hasIrreversibleCall() }) {
			_state.update {
				it.copy(error = "The later part of this turn ran SSH; those effects cannot be undone.")
			}
			return
		}
		val lastUser = visible.lastOrNull { it.fromUser } ?: return
		val startAvailable = _state.value.regenerationPoints
			.firstOrNull { it.checkpointId == SnapshotStore.START }
			?.checkpointAvailable == true
		val checkpointAvailable = point.checkpointAvailable
		val prefixLocal = visible.take(boundaryIndex + 1).any { it.isLocalMutation() }
		val turnLocal = visible.any { it.isLocalMutation() }
		if ((point.checkpointId == SnapshotStore.START && turnLocal && !startAvailable) ||
			((prefixLocal || startAvailable) && !checkpointAvailable)
		) {
			_state.update {
				it.copy(error = "The checkpoint for that point is missing, so nothing was changed.")
			}
			return
		}

		val demo = demoEnabled()
		if (!demo && !agents.hasKey(providerId)) {
			_state.update {
				it.copy(error = "Add an API key for $providerName first, or switch on demo mode.")
			}
			return
		}
		val entity = conversation ?: return

		Diag.i(
			"turn",
			"regenerate-from conv=${Diag.shortId(conversationId)} " +
				"point=${Diag.shortId(point.checkpointId)}",
		)
		streamJob = viewModelScope.launch {
			if (checkpointAvailable) {
				val restored = runCatching {
					repository.restoreCheckpoint(entity, lastUser.id, point.checkpointId)
				}.getOrElse { failure ->
					Diag.w("rollback", "checkpoint restore threw", failure)
					false
				}
				if (!restored) {
					_state.update {
						it.copy(
							error = "Could not restore that checkpoint, so nothing was changed.",
						)
					}
					return@launch
				}
			}
			runCatching {
				repository.deleteDescendants(boundary.id, conversationId)
			}.onFailure { failure ->
				_state.update { it.copy(error = "Could not clear the later turns: ${failure.message}") }
				return@launch
			}
			_state.update {
				it.copy(
					messages = visible.take(boundaryIndex + 1),
					isStreaming = true,
					error = null,
				)
			}
			turnAnchorId = lastUser.id
			turnSnapshotReady = startAvailable
			runTurn(demo = demo, includeTools = true)
			recordChangedFiles()
			refreshTurnMeta()
		}
	}

	/**
	 * Update everything derived from the completed turn without reloading the
	 * transcript.
	 *
	 * The old path called [reload] here, which re-read every row and replaced
	 * the whole visible list after the last token. That produced a visible
	 * flash at the end of every reply -- the streamed row was replaced by an
	 * equal row, and the list briefly re-laid out. The transcript is already in
	 * the state and already persisted; only the controls and the checkpoint
	 * metadata need recomputing.
	 */
	private suspend fun refreshTurnMeta() {
		val entities = repository.conversation(conversationId)?.messages ?: return
		val lastUser = entities.lastOrNull { it.role == "user" }
		val lastTurn = lastUser?.let { user ->
			entities.dropWhile { it.id != user.id }
		}.orEmpty()
		val irreversible = lastTurn.any { it.hasIrreversibleCall() }
		val hasSnapshot = lastUser != null && runCatching {
			repository.hasStartCheckpoint(conversationId, lastUser.id)
		}.getOrDefault(false)
		val snapshotTurns = runCatching { repository.snapshotTurns(conversationId) }
			.getOrElse { emptyList<SnapshotTurn>() }
		val snapshotStats = SnapshotStats(
			turns = snapshotTurns.size,
			bytes = snapshotTurns.sumOf { it.bytes },
		)
		val mutated = lastTurn.any { it.mutated } || hasSnapshot
		val sharedWorkspace = _state.value.workspaceIsPermanent &&
			_state.value.workspaceConversationCount > 1
		val rollbackAvailable = lastUser != null && !irreversible && !sharedWorkspace &&
			(!mutated || hasSnapshot)
		val rollbackBlockedReason = when {
			lastUser == null -> null
			irreversible -> "This turn ran SSH commands; remote effects cannot be rolled back."
			sharedWorkspace ->
				"Files are shared with other conversations in this workspace, so " +
					"rewinding this turn's file changes is disabled."
			mutated && !hasSnapshot ->
				"The workspace snapshot for this turn is missing, so it cannot be rewound safely."
			else -> null
		}
		val regenerationPoints = if (sharedWorkspace) {
			emptyList()
		} else {
			regenerationPointsFor(lastUser, lastTurn)
		}
		// A summary is active only while its boundary is on the active path and
		// something follows it. A stale row from a branch that was switched away
		// from is ignored rather than applied to the wrong prefix.
		val compaction = runCatching { repository.contextSummary(conversationId) }
			.getOrNull()
			?.let { summary ->
				val boundary = entities.indexOfFirst { it.id == summary.throughMessageId }
				if (boundary < 0 || boundary >= entities.lastIndex) {
					null
				} else {
					ContextCompactionUi(
						throughMessageId = summary.throughMessageId,
						summary = summary.summary,
						omittedTurns = entities.take(boundary + 1).count { it.role == "user" },
						omittedMessages = boundary + 1,
					)
				}
			}
		turnAnchorId = lastUser?.id
		turnSnapshotReady = hasSnapshot
		turnIrreversible = irreversible
		_state.update {
			it.copy(
				isStreaming = false,
				rollbackAvailable = rollbackAvailable,
				rollbackBlockedReason = rollbackBlockedReason,
				snapshotStats = snapshotStats,
				snapshotTurns = snapshotTurns,
				snapshotRetention = snapshotRetention(),
				toolRoundLimit = toolRoundLimit(),
				regenerationPoints = regenerationPoints,
				compaction = compaction,
				context = contextOf(it.messages),
			)
		}
	}


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
		Diag.i(
			"branch",
			"select parent=${Diag.shortId(parentId)} child=${Diag.shortId(childId)}",
		)
		viewModelScope.launch {
			repository.selectBranch(parentId, childId)
			reload()
		}
	}

	/**
	 * Rewind to just before the last user message, putting it back in the composer.
	 *
	 * The transcript and the workspace move together: if the turn changed local
	 * files, the pre-turn snapshot is restored **before** any rows are deleted.
	 * A failure to restore leaves the conversation intact. A turn containing an
	 * irreversible tool is refused outright -- deleting its rows while its
	 * remote effects remain would be exactly the half-rollback this is meant to
	 * prevent.
	 *
	 * Only the last user message is accepted. Rewinding an older one is the same
	 * operation with a larger discard, and the UI only offers it on the tail.
	 */
	fun rollbackTo(messageId: String) {
		if (_state.value.isStreaming) {
			_state.update { it.copy(error = "Stop the reply before rewinding the conversation.") }
			return
		}
		val target = _state.value.messages.lastOrNull { it.fromUser }?.takeIf { it.id == messageId }
			?: return
		val turn = _state.value.messages.dropWhile { it.id != target.id }
		val irreversible = turn.any { message ->
			message.toolCalls.any { call ->
				ToolCatalog.all.firstOrNull { it.name == call.displayName }?.reversible == false
			}
		}
		if (irreversible) {
			_state.update {
				it.copy(
					error = "This turn ran SSH commands; their remote effects cannot " +
						"be rolled back, so the conversation was left intact.",
				)
			}
			return
		}
		val mutated = turn.any { it.mutated }
		Diag.i(
			"turn",
			"rollback conv=${Diag.shortId(conversationId)} " +
				"message=${Diag.shortId(target.id)} mutated=$mutated",
		)
		viewModelScope.launch {
			// A snapshot is also evidence of a mutation whose row update may
			// have been lost with the process. Restore whenever one exists,
			// even if `mutated` is false.
			val entity = conversation
			val hasSnapshot = entity != null && runCatching {
				repository.hasStartCheckpoint(conversationId, target.id)
			}.getOrDefault(false)
			if (mutated || hasSnapshot) {
				val restored = entity != null && runCatching {
					repository.restoreCheckpoint(entity, target.id, SnapshotStore.START)
				}.getOrElse { failure ->
					Diag.w("rollback", "snapshot restore threw", failure)
					false
				}
				if (!restored) {
					_state.update {
						it.copy(
							error = "Could not restore the workspace snapshot, so the " +
								"conversation was left intact.",
						)
					}
					return@launch
				}
				Diag.i("rollback", "workspace restored")
			}
			runCatching {
				repository.deleteSubtree(
					rootId = target.id,
					parentId = target.parentId,
					conversationId = conversationId,
				)
			}.onFailure { failure ->
				Diag.w("turn", "rollback delete failed", failure)
				_state.update { it.copy(error = "Could not rewind the conversation: ${failure.message}") }
				return@launch
			}
			repository.deleteTurnSnapshot(conversationId, target.id)
			_state.update {
				it.copy(
					input = target.plainText,
					pendingAttachments = target.attachments,
					isStreaming = false,
					error = null,
					pendingApproval = null,
					pendingSshConsent = null,
				)
			}
			reload()
		}
	}

	/**
	 * How many turns of checkpoints to keep. Current turn always included.
	 *
	 * The minimum is 1: a local mutation needs its start checkpoint even if the
	 * user wants no history, and the setting governs history rather than
	 * whether the in-flight turn can be undone.
	 */
	private fun snapshotRetention(): Int =
		secure.get(SecureStore.KEY_SNAPSHOT_TURNS)?.toIntOrNull()?.coerceIn(1, 10) ?: 3

	fun setSnapshotRetention(turns: Int) {
		val keep = turns.coerceIn(1, 10)
		secure.put(SecureStore.KEY_SNAPSHOT_TURNS, keep.toString())
		viewModelScope.launch {
			repository.pruneSnapshots(conversationId, keep)
			reload()
		}
	}

	/** How many tool rounds one turn may run before it stops and says so. */
	private fun toolRoundLimit(): Int =
		secure.get(SecureStore.KEY_TOOL_ROUNDS)?.toIntOrNull()
			?.coerceIn(MIN_TOOL_ROUNDS, MAX_TOOL_ROUNDS)
			?: DEFAULT_TOOL_ROUNDS

	fun setToolRoundLimit(rounds: Int) {
		val limit = rounds.coerceIn(MIN_TOOL_ROUNDS, MAX_TOOL_ROUNDS)
		secure.put(SecureStore.KEY_TOOL_ROUNDS, limit.toString())
		_state.update { it.copy(toolRoundLimit = limit) }
		Diag.i("turn", "tool round limit set to $limit")
	}

	/**
	 * Rename this conversation.
	 *
	 * The title is generated from the first message and is not always a good
	 * summary; the row is what the user scans later, so it has to be fixable.
	 * The repository method already existed and had no caller.
	 */
	fun renameConversation(title: String) {
		val next = title.trim()
		if (next.isEmpty() || next == _state.value.title) return
		viewModelScope.launch {
			runCatching { repository.rename(conversationId, next) }
				.onSuccess {
					_state.update { it.copy(title = next) }
					Diag.i("chat", "renamed conv=${Diag.shortId(conversationId)}")
				}
				.onFailure { failure ->
					Diag.w("chat", "rename failed conv=${Diag.shortId(conversationId)}", failure)
					_state.update { it.copy(error = "Could not rename the conversation.") }
				}
		}
	}

	/**
	 * Keep this conversation's workspace after the conversation is deleted.
	 *
	 * Only the workspace row changes; the directory is already where it will
	 * stay. The new name is editable later from the Workspaces screen.
	 */
	fun saveWorkspaceAsPermanent(name: String) {
		val entity = conversation ?: return
		viewModelScope.launch {
			runCatching { repository.promoteWorkspace(entity, name) }
				.onSuccess { workspace ->
					if (workspace != null) {
						_state.update {
							it.copy(
								workspaceName = workspace.name,
								workspaceIsPermanent = true,
								workspaceConversationCount = repository.workspaceConversationCount(
									workspace.id,
								),
							)
						}
						Diag.i("workspace", "promoted conv=${Diag.shortId(conversationId)}")
					} else {
						_state.update { it.copy(error = "Workspace not found.") }
					}
				}
				.onFailure { failure ->
					Diag.w("workspace", "promote failed", failure)
					_state.update { it.copy(error = "Could not save the workspace.") }
				}
		}
	}

	/** Turn demo mode on from the chat, where the disabled composer explains itself. */
	fun enableDemoMode() {
		secure.put(SecureStore.KEY_DEMO_MODE, "1")
		viewModelScope.launch {
			refreshConfiguration()
			// The model list and vision flag were published before demo was on;
			// without this the demo model is text-only to the attach button.
			publishModelState()
			Diag.i("config", "demo mode enabled from chat")
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
		val hasKey = agents.hasKey(providerId)
		Diag.d("config", "demo=$demo provider=$providerId hasKey=$hasKey")
		_state.update {
			it.copy(
				demoMode = demo,
				configured = demo || hasKey,
				providerName = providerName,
				toolRoundLimit = toolRoundLimit(),
			)
		}
	}

	private fun demoEnabled(): Boolean = secure.get(SecureStore.KEY_DEMO_MODE) == "1"

	fun onInputChange(text: String) = _state.update { it.copy(input = text) }

	fun clearError() = _state.update { it.copy(error = null) }

	fun stop() {
		Diag.i("turn", "stop requested streaming=${_state.value.isStreaming}")
		streamJob?.cancel()
		streamJob = null
		sshConsentContinuation?.cancel()
		sshConsentContinuation = null
		_state.update { s ->
			s.copy(
				isStreaming = false,
				pendingApproval = null,
				pendingSshConsent = null,
				messages = s.messages.map {
					if (it.isStreaming) it.copy(isStreaming = false) else it
				},
			)
		}
	}

	// --- approvals -----------------------------------------------------------

	fun approvePendingCall() {
		val pending = _state.value.pendingApproval ?: return
		Diag.i("approval", "allow tool=${pending.call.displayName} summary=${pending.summary}")
		_state.update { it.copy(pendingApproval = null) }
		viewModelScope.launch { continueAfterApproval(pending, approved = true) }
	}

	fun declinePendingCall() {
		val pending = _state.value.pendingApproval ?: return
		Diag.i("approval", "decline tool=${pending.call.displayName} summary=${pending.summary}")
		_state.update { it.copy(pendingApproval = null) }
		viewModelScope.launch { continueAfterApproval(pending, approved = false) }
	}

	// --- sending -------------------------------------------------------------

	fun send() {
		val text = _state.value.input.trim()
		val attachments = _state.value.pendingAttachments
		// Images alone are a complete message -- "what is this?" is often
		// implied -- but an empty message with nothing attached is not.
		if ((text.isEmpty() && attachments.isEmpty()) || _state.value.isStreaming ||
			_state.value.isCompacting
		) {
			Diag.d(
				"turn",
				"send ignored chars=${text.length} attachments=${attachments.size} " +
					"streaming=${_state.value.isStreaming} compacting=${_state.value.isCompacting}",
			)
			return
		}

		val demo = demoEnabled()
		if (!demo && !agents.hasKey(providerId)) {
			Diag.w("turn", "send refused: no API key and demo mode off provider=$providerId")
			_state.update {
				it.copy(error = "Add an API key for $providerName first, or switch on demo mode.")
			}
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
		Diag.i(
			"turn",
			"send conv=${Diag.shortId(conversationId)} chars=${text.length} " +
				"attachments=${attachments.size} demo=$demo " +
				"messages=${_state.value.messages.size}",
		)
		streamJob = viewModelScope.launch {
			repository.appendMessage(userMessage, parent)
			// Older turns' snapshots stay: the retention setting decides how
			// many are kept, not the act of starting a new turn.
			turnAnchorId = userMessage.id
			turnSnapshotReady = false
			turnIrreversible = false
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
			runTurn(demo = demo)
			recordChangedFiles()
			refreshTurnMeta()
		}
	}

	/**
	 * Build the body the next send would use, without sending it.
	 *
	 * The preview goes through the provider's own serializer, so the system
	 * message (including a compaction summary), the tool schemas, the current
	 * draft and the request parameters are the same objects the network would
	 * receive. Demo mode has no provider body and says so rather than inventing
	 * one.
	 */
	fun previewNextRequest() {
		if (_state.value.isStreaming || _state.value.isCompacting) return
		_wire.value = null
		viewModelScope.launch {
			val demo = demoEnabled()
			if (demo) {
				_state.update {
					it.copy(error = "Demo mode has no provider request to inspect.")
				}
				return@launch
			}
			val provider = agents.provider(providerId)?.let { agents.providerFromRow(it) }
			if (provider == null) {
				_state.update {
					it.copy(error = "Add an API key for $providerName to preview a request.")
				}
				return@launch
			}
			val turnModel = model ?: agent?.model
				?: agents.models(providerId).getOrNull()?.firstOrNull()?.id
			if (turnModel == null) {
				_state.update { it.copy(error = "Choose a model for $providerName first.") }
				return@launch
			}
			val result = runCatching {
				withContext(Dispatchers.IO) {
					val policies = loadPolicies()
					val tools = buildTools(policies)
					val request = ChatRequest(
						model = turnModel,
						messages = buildWireHistory(includeCurrent = true),
						tools = tools.map(::spec),
						thinking = thinking,
						reasoningEffort = reasoningEffort,
						userId = conversationId,
					)
					provider.previewRequest(request)
				}
			}
			val body = result.getOrNull()
			if (body == null) {
				val reason = result.exceptionOrNull()?.message
				_state.update {
					it.copy(
						error = if (reason == null) {
							"This provider does not expose a request body."
						} else {
							"Could not build the request: $reason"
						},
					)
				}
				return@launch
			}
			_wire.value = WireSnapshot(
				requestJson = Json { prettyPrint = true }
					.encodeToString(JsonObject.serializer(), body),
				model = turnModel,
				capturedAt = System.currentTimeMillis(),
				preview = true,
			)
			_state.update { it.copy(error = null) }
		}
	}

	/**
	 * Write the turn's changed paths onto its final assistant row.
	 *
	 * The snapshot diff is the source of truth, so this reports what actually
	 * differs after the tools ran rather than trusting each tool to volunteer a
	 * path. It runs once, on IO, before [reload] re-reads the row.
	 */
	private suspend fun recordChangedFiles() {
		val entity = conversation ?: return
		val anchor = turnAnchorId ?: return
		val changes = runCatching {
			repository.workspaceChanges(entity, anchor)
		}.getOrElse { failure ->
			Diag.w("rollback", "could not diff workspace", failure)
			emptyList()
		}
		runCatching { repository.pruneSnapshots(conversationId, snapshotRetention()) }
			.onFailure { Diag.w("rollback", "could not prune snapshots", it) }
		runCatching { retainCurrentCheckpoints(anchor) }
			.onFailure { Diag.w("rollback", "could not trim checkpoints", it) }
		if (changes.isEmpty()) return
		val target = _state.value.messages
			.dropWhile { it.id != anchor }
			.lastOrNull { !it.fromUser && !it.isToolResult }
			?: return
		runCatching { repository.setChangedFiles(target.id, encodeChangedFiles(changes)) }
			.onFailure { Diag.w("rollback", "could not store changed files", it) }
		_state.update { s ->
			s.copy(
				messages = s.messages.map {
					if (it.id == target.id) it.copy(changedFiles = changes) else it
				},
			)
		}
		Diag.i("rollback", "recorded ${changes.size} changed files")
	}

	/**
	 * Delete checkpoint manifests no longer on the active transcript.
	 *
	 * Regenerating from the middle of a turn replaces the rest of that turn;
	 * the old checkpoints after the chosen boundary must not accumulate. The
	 * start and every complete current tool group are kept.
	 */
	private suspend fun retainCurrentCheckpoints(anchor: String) {
		val turn = _state.value.messages.dropWhile { it.id != anchor }
		val keep = mutableSetOf(SnapshotStore.START)
		var index = 1
		while (index < turn.size) {
			val message = turn[index]
			if (message.fromUser || message.isToolResult || message.toolCalls.isEmpty()) {
				index++
				continue
			}
			var next = index + 1
			var lastResult: String? = null
			while (next < turn.size && turn[next].isToolResult) {
				lastResult = turn[next].id
				next++
			}
			lastResult?.let { keep += it }
			index = next
		}
		repository.retainCheckpoints(conversationId, anchor, keep)
	}

	/**
	 * The last turn's tool-group boundaries, in order.
	 *
	 * Each point is the last tool result of a complete assistant tool-calls
	 * block. Partial groups are excluded: keeping an assistant `tool_calls`
	 * without every matching result is an invalid wire history, and replaying
	 * from there would be rejected before the model saw anything.
	 */
	private suspend fun regenerationPointsFor(
		lastUser: MessageEntity?,
		lastTurn: List<MessageEntity>,
	): List<RegenerationPoint> {
		if (lastUser == null) return emptyList()
		val startAvailable = repository.hasStartCheckpoint(conversationId, lastUser.id)
		val startIrreversible = lastTurn.any { it.hasIrreversibleCall() }
		val startBlocked = when {
			startIrreversible ->
				"The rest of this turn ran SSH; those effects cannot be undone."
			lastTurn.any { it.hasLocalMutation() } && !startAvailable ->
				"No start checkpoint exists for this turn."
			else -> null
		}
		val points = mutableListOf(
			RegenerationPoint(
				checkpointId = SnapshotStore.START,
				label = "From your message",
				afterMessageId = lastUser.id,
				checkpointAvailable = startAvailable,
				blockedReason = startBlocked,
			),
		)

		var index = 1 // lastTurn[0] is the user message
		var group = 0
		while (index < lastTurn.size) {
			val message = lastTurn[index]
			val callsJson = message.toolCallsJson
			if (message.role != "assistant" || callsJson.isNullOrBlank()) {
				index++
				continue
			}
			val calls = decodeCalls(callsJson)
			if (calls.isEmpty()) {
				index++
				continue
			}
			var next = index + 1
			val results = mutableListOf<MessageEntity>()
			while (next < lastTurn.size && lastTurn[next].role == "tool") {
				results += lastTurn[next]
				next++
			}
			val resultIds = results.mapNotNull { it.toolCallId }.toSet()
			if (results.isEmpty() || calls.any { it.id != null && it.id !in resultIds }) {
				break
			}
			val boundary = results.last()
			val dropped = lastTurn.dropWhile { it.id != boundary.id }.drop(1)
			val droppedIrreversible = dropped.any { it.hasIrreversibleCall() }
			val checkpointAvailable = repository.hasCheckpoint(
				conversationId,
				lastUser.id,
				boundary.id,
			)
			val prefixLocal = lastTurn.takeWhile { it.id != boundary.id }
				.any { it.hasLocalMutation() }
			// A read-only group before the first local write has no checkpoint
			// of its own, but its workspace is exactly the start checkpoint.
			val effectiveCheckpointId = if (
				!checkpointAvailable && !prefixLocal && startAvailable
			) {
				SnapshotStore.START
			} else {
				boundary.id
			}
			val effectiveAvailable = checkpointAvailable ||
				(effectiveCheckpointId == SnapshotStore.START && startAvailable)
			val blocked = when {
				droppedIrreversible ->
					"The rest of this turn ran SSH; those effects cannot be undone."
				startAvailable && !effectiveAvailable ->
					"The checkpoint after this tool group is missing."
				!startAvailable && prefixLocal ->
					"No checkpoint exists for this turn."
				else -> null
			}
			val names = calls.mapNotNull { it.displayName }.distinct()
			group++
			points += RegenerationPoint(
				checkpointId = effectiveCheckpointId,
				label = if (names.isEmpty()) {
					"After tool group $group"
				} else {
					"After tool group $group: ${names.joinToString(", ")}"
				},
				afterMessageId = boundary.id,
				checkpointAvailable = effectiveAvailable,
				blockedReason = blocked,
			)
			index = next
		}
		return points
	}

	private fun MessageEntity.hasIrreversibleCall(): Boolean =
		decodeCalls(toolCallsJson.orEmpty()).any { call ->
			ToolCatalog.all.firstOrNull { it.name == call.displayName }?.reversible == false
		}

	private fun MessageEntity.hasLocalMutation(): Boolean =
		decodeCalls(toolCallsJson.orEmpty()).any { call ->
			ToolCatalog.all.firstOrNull { it.name == call.displayName }
				?.let { it.mutates && it.reversible } == true
		}

	private fun ChatMessageUi.hasIrreversibleCall(): Boolean =
		toolCalls.any { call ->
			ToolCatalog.all.firstOrNull { it.name == call.displayName }?.reversible == false
		}

	private fun ChatMessageUi.isLocalMutation(): Boolean =
		toolCalls.any { call ->
			ToolCatalog.all.firstOrNull { it.name == call.displayName }
				?.let { it.mutates && it.reversible } == true
		}

	/**
	 * Run the model until it answers without asking for a tool.
	 *
	 * Each iteration appends the model's turn (with any tool calls), executes
	 * what was asked, appends the results, and goes round again.
	 */
	private suspend fun runTurn(
		demo: Boolean,
		includeTools: Boolean = true,
		suppressToolCalls: Boolean = false,
	) {
		val provider: ChatProvider? =
			if (demo) {
				DemoProvider()
			} else {
				agents.provider(providerId)?.let { agents.providerFromRow(it) }
			}
		if (provider == null) {
			_state.update {
				it.copy(
					isStreaming = false,
					error = "No API key is configured for $providerName.",
				)
			}
			return
		}

		val turnModel = model ?: agent?.model
			?: if (demo) {
				demoModelsIfEnabled(true)?.firstOrNull()?.id
			} else {
				agents.models(providerId).getOrNull()?.firstOrNull()?.id
			}
		if (turnModel == null) {
			_state.update {
				it.copy(
					isStreaming = false,
					error = "Choose a model for $providerName first.",
				)
			}
			return
		}

		// Read once, before the tools are built, because the rule set decides
		// what `list_files` is even allowed to show. A path the user marked
		// `Blind` has to be absent from the listing, not merely refused when
		// named -- a filename leaks on its own.
		val policies = loadPolicies()
		// A regenerate request keeps the transcript and the tool definitions so
		// the request prefix remains the same and can hit the context cache; a
		// transient system instruction tells the model not to call one. The
		// app still refuses to execute any call it receives in this mode. The
		// demo provider scripts calls from request.tools and ignores
		// instructions, so demo mode gets no tools here.
		val tools = if (includeTools && (!suppressToolCalls || !demo)) {
			buildTools(policies)
		} else {
			emptyList()
		}
		val toolSpecs = tools.map { spec(it) }
		// Read once for the whole turn: changing the setting mid-stream must not
		// move the ceiling under a loop that is already running.
		val maxRounds = toolRoundLimit()
		Diag.i(
			"turn",
			"run start provider=${provider.id} " +
				"model=$turnModel thinking=$thinking " +
				"effort=${reasoningEffort ?: "-"} tools=${tools.size} " +
				"suppressTools=$suppressToolCalls " +
				"maxRounds=$maxRounds demo=$demo",
		)

		var rounds = 0
		while (rounds < maxRounds) {
			rounds++
			val outcome = streamOneTurn(provider, turnModel, tools, toolSpecs, suppressToolCalls)

			// The model was told not to call tools. If one arrives anyway,
			// executing it would defeat the retry's safety, so stop with the
			// reason rather than replaying a write.
			if (suppressToolCalls && outcome.toolCalls.isNotEmpty()) {
				Diag.w("turn", "provider called a tool despite the retry instruction")
				_state.update {
					it.copy(
						isStreaming = false,
						error = "The model tried to call a tool during a text-only retry. " +
							"Nothing was executed.",
					)
				}
				return
			}

			if (outcome.toolCalls.isEmpty()) {
				Diag.i(
					"turn",
					"run end rounds=$rounds reply=${Diag.shortId(outcome.messageId)} " +
						"failure=${outcome.failure != null}",
				)
				_state.update { it.copy(isStreaming = false) }
				return
			}

			// Execute them, in order. Approvals are collected first so a model
			// asking for three writes does not stack three dialogs.
			val executed = executeCalls(outcome, tools)
			if (!executed) {
				Diag.i("turn", "run end declined rounds=$rounds")
				// The user declined something, or the turn was cancelled. The
				// transcript already holds the refusal, so the model can respond
				// to it on the next user message rather than being re-run now.
				_state.update { it.copy(isStreaming = false) }
				return
			}
		}

		// Hit the ceiling. Said plainly, because a silent stop looks like the
		// model simply had nothing more to say.
		Diag.w("turn", "round limit reached rounds=$rounds")
		_state.update {
			it.copy(
				isStreaming = false,
				error = "Stopped after $maxRounds tool rounds. " +
					"The assistant may be looping; raise the limit in the chat menu, " +
					"or send another message to continue.",
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
		modelId: String,
		tools: List<Tool>,
		toolSpecs: List<ToolSpec>,
		suppressToolCalls: Boolean = false,
	): TurnOutcome {
		val parent = _state.value.messages.lastOrNull()?.id
		val replyId = UUID.randomUUID().toString()

		// The delta stream this reply will be parsed from as it arrives.
		// Registered before the row exists, because the row's first render
		// already needs it: a reply that appeared with no deltas and gained
		// them a moment later would parse its first slice twice.
		val deltas = Channel<String>(Channel.CONFLATED)
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

		val history = buildWireHistory().let { base ->
			if (suppressToolCalls) {
				base + ChatMessage(Role.System, NO_TOOL_RETRY_INSTRUCTION)
			} else {
				base
			}
		}
		Diag.i(
			"stream",
			"request history=${history.size} tools=${toolSpecs.size} " +
				"reply=${Diag.shortId(replyId)}",
		)
		val reasoning = StringBuilder()
		val answer = StringBuilder()
		val calls = linkedMapOf<Int, ToolCallUi>()
		var promptTokens: Int? = null
		var completionTokens: Int? = null
		var cachedTokens: Int? = null
		var reasoningTokens: Int? = null
		var finish: String? = null
		var failure: String? = null
		var chunks = 0

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

		// Whether text has arrived after a tool call started. A model's "I'll
		// search for that" is a preamble, not the answer; without this flag the
		// tool section closed the moment the preamble streamed, which is the
		// opposite of keeping the current work visible.
		var answerAfterCalls = false

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
				publishParts(replyId, reasoning, answer, calls, cursor, answerAfterCalls)
			}

			val request = ChatRequest(
				model = modelId,
				messages = history,
				tools = toolSpecs,
				// Taken from the assistant, and off by default: "hi" should
				// not cost a paragraph of thinking.
				thinking = thinking,
				reasoningEffort = reasoningEffort,
				userId = conversationId,
			)
			provider.previewRequest(request)?.let { body ->
				_wire.value = WireSnapshot(
					requestJson = Json { prettyPrint = true }
						.encodeToString(JsonObject.serializer(), body),
					model = modelId,
					capturedAt = System.currentTimeMillis(),
				)
			}
			provider.stream(request).collect { chunk ->
				chunks++
				if (firstTokenAt == null && chunk is StreamChunk.TextDelta) {
					firstTokenAt = System.currentTimeMillis()
					Diag.d("stream", "first text after ${firstTokenAt - startedAt}ms")
				}
				when (chunk) {
					is StreamChunk.ReasoningDelta -> {
						reasoning.append(chunk.text)
						publishIfDue()
					}

					is StreamChunk.TextDelta -> {
						if (calls.isNotEmpty()) answerAfterCalls = true
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
						Diag.e("stream", "failure chunk: ${chunk.message}")
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
			publishParts(replyId, reasoning, answer, calls, cursor, answerAfterCalls)
			finishedCalls = calls.values.toList()
			// The channel is removed from the live map and closed. The message
			// keeps its reference so the streaming renderer can settle on the
			// final text without the blank frame a switch to the asynchronous
			// plain parser caused; a later reload rebuilds the row without it.
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
			// describes, but performing it here produced a blank frame: the
			// plain renderer parses asynchronously, so for one composition the
			// new state had nothing to draw and the finished reply blinked.
			// Keep the streaming state -- its content is already the final
			// document and the channel is closed -- until the row is rebuilt
			// from the database (reopen, branch switch, reload).
			updateMessage(replyId) {
				it.copy(isStreaming = false, finishReason = finish, usage = usage)
			}
			// The gauge moves here rather than at the end of the turn, because
			// this is the moment the provider's count for this prompt exists --
			// and because a turn can take several round trips, each of which
			// resends a larger transcript than the last.
			refreshContext()

			val finalUsage = usage
			Diag.i(
				"stream",
				"end finish=${finish ?: "-"} failure=${failure != null} stopped=$wasStopped " +
					"chunks=$chunks textChars=${answer.length} " +
					"reasoningChars=${reasoning.length} calls=${finishedCalls.size} " +
					"prompt=${finalUsage?.promptTokens ?: "-"} " +
					"completion=${finalUsage?.completionTokens ?: "-"} " +
					"cache=${finalUsage?.cachedPromptTokens ?: "-"} " +
					"ttft=${finalUsage?.timeToFirstTokenMs ?: "-"} " +
					"gen=${finalUsage?.generationMs ?: "-"} " +
					"ms=${System.currentTimeMillis() - startedAt}",
			)
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
				} else if (failure != null) {
					// A turn that failed before producing anything still gets a
					// row, with the reason and no content.
					//
					// It used to be dropped: the condition above asks for text,
					// reasoning or tool calls, and a first-request network
					// failure has none of the three -- so the placeholder was
					// removed and nothing was written. The only trace was the
					// dismissible error row, which is gone by the next launch,
					// leaving a question with no answer and no sign that one had
					// been attempted.
					repository.appendMessage(
						MessageEntity(
							id = replyId,
							conversationId = conversationId,
							parentId = parent,
							role = "assistant",
							content = "",
							model = model,
							error = failure,
							createdAt = System.currentTimeMillis(),
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
			Diag.d("tool", "skip name=${skipped.displayName} index=${skipped.index}")
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
					Diag.w("tool", "unknown name=${call.displayName} index=${call.index}")
					appendToolResult(call, "no such tool: ${call.displayName}", isError = true, outcome.messageId)
					continue
				}

				val parsed = (parseArguments(call, outcome.messageId) ?: continue).let { raw ->
					if (tool.name == "ssh_exec") SshExecTool.normalizeArguments(raw) else raw
				}

				// Never show a consent dialogue for a call the tool would reject
				// as malformed. The dialogue is the strongest warning in the
				// app; it must describe a call that can actually happen.
				if (tool.name == "ssh_exec") {
					val missing = when {
						stringArg(parsed, "host").isNullOrBlank() -> "host"
						stringArg(parsed, "username").isNullOrBlank() -> "username"
						stringArg(parsed, "command").isNullOrBlank() -> "command"
						else -> null
					}
					if (missing != null) {
						markCall(outcome.messageId, call.index, ToolCallUi.State.Failed)
						appendToolResult(
							call,
							"ssh_exec: $missing is required",
							isError = true,
							messageId = outcome.messageId,
						)
						continue
					}
				}

				// A tool can be switched off in Settings while a turn is already
				// running; the registry was built before that change. Re-check
				// the global gate here so "off" means off for this call too.
				if (tool.name == "ssh_exec" && !sshToolEnabled()) {
					Diag.i("ssh", "refused: global switch off")
					markCall(outcome.messageId, call.index, ToolCallUi.State.Failed)
					appendToolResult(
						call,
						"${REFUSED_MARKER} SSH is disabled in Settings.",
						isError = true,
						messageId = outcome.messageId,
					)
					continue
				}

				// Settings can revoke every conversation's consent (turning the
				// global switch off does exactly that). The in-memory flag is
				// stale after such a round trip: off, then on again. Read the
				// persisted answer before trusting it, or the second enabling
				// inherits a consent the user revoked.
				if (tool.name == "ssh_exec" && sshAllowed) {
					val persisted = runCatching { repository.sshAllowed(conversationId) }
						.getOrNull()
					if (persisted == false) {
						sshAllowed = false
						_state.update { it.copy(sshAllowed = false) }
						Diag.i("ssh", "persisted consent gone; asking again")
					}
				}

				// The configured rules, before the tool's own opinion.
				//
				// `Deny` and `Blind` are refusals rather than questions, so they
				// have to be answered here -- `needsApproval` can only say "ask",
				// and asking about something the user has forbidden would be the
				// wrong question with a confusing answer.
				val policyPath = resolvedPolicyPath(parsed)
				val policy = policies.effective(
					tool = tool.name,
					relativePath = policyPath,
				)
				if (policy == ToolPolicy.Deny || policy == ToolPolicy.Blind) {
					Diag.i(
						"policy",
						"refused tool=${tool.name} path=${policyPath ?: "-"} policy=$policy",
					)
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

				// The capability gate, before the per-call policy. SSH is not a
				// workspace copy; the first call in a conversation needs an
				// explicit acknowledgement that later calls inherit. The gate is
				// deliberately *not* `needsApproval`: that is a per-call question,
				// this is a grant of a capability.
				if (tool.name == "ssh_exec" && !sshAllowed) {
					val host = stringArg(parsed, "host").orEmpty()
					val username = stringArg(parsed, "username").orEmpty()
					val sshCommand = stringArg(parsed, "command").orEmpty()
					Diag.i(
						"ssh",
						"consent ask host=$host user=$username commandChars=${sshCommand.length}",
					)
					_state.update {
						it.copy(
							pendingSshConsent = SshConsent(
								messageId = outcome.messageId,
								call = call,
								host = host,
								username = username,
								command = sshCommand,
								hostKeyFingerprint = stringArg(parsed, "hostKeyFingerprint")
									?.trim()
									?.takeIf { it.isNotEmpty() },
							),
						)
					}
					val allowed = awaitSshConsent()
					if (!allowed) {
						markCall(outcome.messageId, call.index, ToolCallUi.State.Declined)
						appendToolResult(
							call,
							DECLINED_MARKER +
								" The user declined SSH access for this conversation.",
							isError = true,
							messageId = outcome.messageId,
						)
						answerSkippedCalls(outcome, after = call.index)
						settled = true
						return false
					}
					sshAllowed = true
					runCatching { repository.setSshAllowed(conversationId, true) }
						.onFailure { Diag.w("ssh", "could not persist consent", it) }
					_state.update { it.copy(sshAllowed = true) }
					Diag.i("ssh", "consent allow host=$host")
					if (!sshToolEnabled()) {
						Diag.i("ssh", "global switch turned off during consent")
						markCall(outcome.messageId, call.index, ToolCallUi.State.Failed)
						appendToolResult(
							call,
							"${REFUSED_MARKER} SSH was disabled in Settings before the call ran.",
							isError = true,
							messageId = outcome.messageId,
						)
						continue
					}
				}

				if (policy == ToolPolicy.Ask || tool.needsApproval(parsed)) {
					val summary = describeCall(call, parsed)
					Diag.i(
						"approval",
						"ask tool=${tool.name} path=${policyPath ?: "-"} " +
							"rule=${if (policy == ToolPolicy.Ask) "policy" else "tool"} " +
							"args=${parsed.toString().length}B",
					)
					_state.update {
						it.copy(
							pendingApproval = PendingApproval(
								messageId = outcome.messageId,
								call = call,
								summary = summary,
								details = if (tool.name == "ssh_exec") {
									sshApprovalDetails(parsed)
								} else {
									null
								},
							),
						)
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

				// Secure the pre-turn workspace before the first local mutation.
				// A rollback that cannot restore the files must not be offered;
				// refusing the mutation is safer than deleting the turn later
				// and leaving the changes behind.
				if (tool.mutates) {
					if (!tool.reversible) {
						turnIrreversible = true
					} else if (!turnSnapshotReady) {
						val anchor = turnAnchorId
							?: _state.value.messages.lastOrNull { it.fromUser }?.id
						val entity = conversation
						val captured = anchor != null && entity != null &&
							runCatching {
								repository.captureStartCheckpoint(entity, anchor)
							}.getOrElse { failure ->
								Diag.w("rollback", "snapshot capture threw", failure)
								false
							}
						if (!captured) {
							Diag.w("rollback", "refused mutation: no snapshot")
							markCall(outcome.messageId, call.index, ToolCallUi.State.Failed)
							appendToolResult(
								call,
								"Could not create a rollback snapshot, so this " +
									"workspace change was refused.",
								isError = true,
								messageId = outcome.messageId,
							)
							continue
						}
						turnSnapshotReady = true
						Diag.i("rollback", "snapshot captured for turn")
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
				Diag.i(
					"tool",
					"run name=${tool.name} path=${policyPath ?: "-"} " +
						"args=${parsed.toString().length}B",
				)
				val startedToolAt = System.currentTimeMillis()
				val result = withContext(Dispatchers.IO) { tool.execute(parsed) }
				val elapsed = System.currentTimeMillis() - startedToolAt
				Diag.i(
					"tool",
					"done name=${tool.name} ok=${!result.isError} " +
						"bytes=${result.content.length} ms=$elapsed",
				)
				if (result.isError) {
					// The message is the tool's own explanation ("not found",
					// "quota exceeded"), not file content; 240 chars keeps the
					// line readable without dropping the useful half.
					Diag.w("tool", "error name=${tool.name} ${result.content.take(240)}")
				}
				markCall(
					outcome.messageId,
					call.index,
					if (result.isError) ToolCallUi.State.Failed else ToolCallUi.State.Done,
				)
				appendToolResult(call, result.content, result.isError, outcome.messageId)

				// Recorded whether it succeeded or not.
				//
				// A failed write still means the turn *tried* to change the
				// workspace. Marking only the successes would leave a reply that
				// attempted a delete and was refused looking like plain text.
				// This is a durable record about replay-with-tools; the final
				// retry keeps the definitions but suppresses execution.
				if (tool.mutates) {
					// Marking the message is the whole of the bookkeeping. There
					// is no second `currentTurnMutated` flag to keep in step:
					// that gate was removed.
					_state.update { s ->
						s.copy(
							messages = s.messages.map {
								if (it.id == outcome.messageId) it.copy(mutated = true) else it
							},
						)
					}
					// Written separately, and this is not optional: the reply was
					// persisted when the model stopped streaming, and its tools run
					// afterwards. Updating only the in-memory copy loses the record
					// on the next launch, and a future replay-with-tools feature
					// would then treat a real write as replayable.
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
		captureCheckpointAfterGroup()
		return true
	}

	/**
	 * Save the workspace after a complete tool group, so regeneration can
	 * resume from this point rather than only from the end of the turn.
	 *
	 * A turn that never touched the workspace has no start checkpoint and needs
	 * no checkpoint here: its workspace is unchanged.
	 */
	private suspend fun captureCheckpointAfterGroup() {
		if (!turnSnapshotReady) return
		val entity = conversation ?: return
		val anchor = turnAnchorId ?: return
		val lastResult = _state.value.messages.lastOrNull { it.isToolResult } ?: return
		val saved = runCatching {
			repository.captureCheckpoint(entity, anchor, lastResult.id)
		}.getOrElse { failure ->
			Diag.w("rollback", "tool checkpoint capture threw", failure)
			false
		}
		if (saved) Diag.i("rollback", "checkpoint after tool group")
	}

	// --- approval plumbing ---------------------------------------------------

	private var approvalContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
	private var sshConsentContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

	private suspend fun awaitSshConsent(): Boolean =
		kotlinx.coroutines.suspendCancellableCoroutine { cont ->
			sshConsentContinuation = cont
		}

	fun allowSshConsent() {
		val pending = _state.value.pendingSshConsent ?: return
		Diag.i("ssh", "consent allow requested host=${pending.host}")
		_state.update { it.copy(pendingSshConsent = null) }
		sshConsentContinuation?.let { cont ->
			sshConsentContinuation = null
			if (cont.isActive) cont.resumeWith(Result.success(true))
		}
	}

	fun denySshConsent() {
		val pending = _state.value.pendingSshConsent ?: return
		Diag.i("ssh", "consent denied host=${pending.host}")
		_state.update { it.copy(pendingSshConsent = null) }
		sshConsentContinuation?.let { cont ->
			sshConsentContinuation = null
			if (cont.isActive) cont.resumeWith(Result.success(false))
		}
	}

	/** Revoke this conversation's SSH consent; the next call asks again. */
	fun revokeSshAccess() {
		if (!sshAllowed) return
		sshAllowed = false
		_state.update { it.copy(sshAllowed = false) }
		viewModelScope.launch { repository.setSshAllowed(conversationId, false) }
		Diag.i("ssh", "consent revoked")
	}

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
	private fun buildTools(policies: PolicySet = PolicySet.None): List<Tool> {
		val entity = conversation

		// The conversation's own copy wins, falling back to the assistant's.
		// Copied at creation so that editing an assistant cannot change what a
		// conversation already held is able to do.
		val selected = entity?.tools ?: agent?.tools

		if (entity == null) {
			return listOf(TimeTool()).filter { ToolCatalog.permits(selected, it.name) }
		}

		val workspace = runCatching { repository.workspace(entity) }.getOrElse { failure ->
			Diag.e("tools", "workspace could not be opened; file tools unavailable", failure)
			_state.update {
				it.copy(
					error = "The workspace for this conversation could not be opened, " +
						"so file tools are unavailable: ${failure.message}",
				)
			}
			null
		}

		val fileTools = workspace?.let { ws ->
			runCatching {
				FileTools.all(
					workspace = ws,
					// `Blind` and nothing else. `Deny` also stops the agent reaching
					// a path, but it does so by refusing the call, which leaves the
					// name visible -- and the name is the part that leaks.
					hidden = { path ->
						policies.effective(tool = "list_files", relativePath = path) == ToolPolicy.Blind
					},
				)
			}
				.getOrElse { failure ->
					Diag.e("tools", "file tools could not be built", failure)
					emptyList()
				}
		}.orEmpty()

		return buildList {
			addAll(fileTools)
			add(TimeTool())
			// The web tools are registered only when something can answer them.
			// A tool that is present and always fails teaches the model that the
			// tool is broken and it keeps retrying; one that is absent teaches it
			// that it cannot use it, and it says so.
			searchProvider()?.let { add(WebSearchTool(it)) }
			fetchProvider()?.let { add(WebFetchTool(it)) }
			// SSH is opt-in twice: the global setting and the assistant's
			// explicit tool selection. `selected == null` means "all tools" for
			// historical assistants, so it must NOT be read as consent to SSH.
			if (sshTransport != null && sshToolEnabled() && selected?.contains("ssh_exec") == true) {
				add(
					SshExecTool(sshTransport) { path ->
						workspace?.let { ws ->
							when (val resolved = ws.resolve(path)) {
								is SessionWorkspace.Resolution.Ok -> resolved.file
									.takeIf { it.isFile && it.length() <= MAX_SSH_IDENTITY_BYTES }
									?.readBytes()
								is SessionWorkspace.Resolution.Rejected -> null
							}
						}
					},
				)
			}
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
		// Both spend money at the configured web backend. This sentence is shown
		// only when a rule makes them ask -- which is exactly the moment the cost
		// is worth repeating.
		"web_search" -> "Search the web for \"${
			stringArg(args, "query")?.take(80) ?: "a query"
		}\"? (paid search API)"

		"web_fetch" -> "Fetch ${
			stringArg(args, "url")?.take(100) ?: "a URL"
		}? (paid fetch API)"

		"ssh_exec" -> "Run on ${stringArg(args, "host") ?: "a remote host"}: ${
			stringArg(args, "command")?.take(160) ?: "a command"
		}?"

		else -> "Run ${call.displayName}?"
	}

	/** Approval details for SSH, with credentials left out deliberately. */
	private fun sshApprovalDetails(args: JsonObject): String = buildString {
		append("host: ").append(stringArg(args, "host") ?: "?")
		append("\nport: ").append(
			(args["port"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 22,
		)
		append("\nuser: ").append(stringArg(args, "username") ?: "?")
		append("\nauth: ").append(
			when {
				stringArg(args, "identityFile") != null -> "identity file"
				stringArg(args, "privateKey") != null -> "private key"
				else -> "password"
			},
		)
		append("\ncommand:\n").append(stringArg(args, "command") ?: "?")
		stringArg(args, "hostKeyFingerprint")?.takeIf { it.isNotBlank() }?.let {
			append("\npinned host key: ").append(it)
		}
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
	}.getOrElse { failure ->
		Diag.w("policy", "load failed; continuing with no rules", failure)
		PolicySet.None
	}

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
			Diag.w("tool", "unparsable arguments name=${call.displayName}: ${e.message}")
			appendToolResult(
				call,
				"could not parse arguments: ${e.message}",
				isError = true,
				messageId = messageId,
			)
			null
		}

	/**
	 * Close an assistant tool-call tail that has no matching tool rows.
	 *
	 * The case is a process death while the approval or SSH-consent dialog is
	 * open. The assistant row is already durable; the next request would carry
	 * `tool_calls` with no following `tool` messages and DeepSeek rejects the
	 * whole conversation with HTTP 400. A synthetic result is the honest
	 * record: the call was interrupted and did not run.
	 */
	private suspend fun repairDanglingToolCalls(entities: List<MessageEntity>): Boolean {
		val callMessage = entities.lastOrNull {
			it.role == "assistant" && it.toolCallsJson?.isNotBlank() == true
		} ?: return false
		val calls = decodeCalls(callMessage.toolCallsJson.orEmpty())
		if (calls.isEmpty()) return false

		val callIndex = entities.indexOfFirst { it.id == callMessage.id }
		// Only repair a tail. If a later user or assistant turn exists, appending
		// results at the end would place them after text that belongs later.
		if (entities.drop(callIndex + 1).any { it.role != "tool" }) return false

		val answered = entities.asSequence()
			.filter { it.role == "tool" }
			.mapNotNull { it.toolCallId }
			.toSet()
		val missing = calls.filter { call ->
			val id = call.id
			id == null || id !in answered
		}
		if (missing.isEmpty()) return false

		var parent = entities.last().id
		val now = System.currentTimeMillis()
		for ((offset, call) in missing.withIndex()) {
			val id = call.id ?: "interrupted-${call.index}"
			val content = "$INTERRUPTED_MARKER The app stopped before this call finished; it was not run."
			val entity = MessageEntity(
				id = UUID.randomUUID().toString(),
				conversationId = conversationId,
				parentId = parent,
				role = "tool",
				content = content,
				error = null,
				createdAt = now + offset,
				toolCallId = id,
				toolName = call.displayName,
			)
			repository.appendMessage(entity, parent)
			parent = entity.id
			Diag.w("tool", "repaired dangling call name=${call.displayName} index=${call.index}")
		}
		return true
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
	private fun buildWireHistory(includeCurrent: Boolean = false): List<ChatMessage> {
		val visible = if (includeCurrent) {
			_state.value.messages
		} else {
			_state.value.messages.dropLast(1)
		}
		val out = mutableListOf<ChatMessage>()

		// A derived summary replaces an old prefix on the wire only. The rows
		// remain in the transcript, and the boundary is checked against the
		// active path on every build: a branch switch or a rewind makes the
		// summary inert rather than letting it describe messages that are no
		// longer in this conversation. The cut is always at a turn boundary, so
		// a tool call and its results are never separated.
		val summary = _state.value.compaction
		val boundary = summary?.let { s ->
			visible.indexOfFirst { it.id == s.throughMessageId }
		} ?: -1
		val firstSent = if (boundary in 0 until visible.lastIndex) boundary + 1 else 0
		val activeSummary = summary.takeIf { firstSent > 0 }

		val system = buildString {
			agent?.systemPrompt?.takeIf { it.isNotBlank() }?.let { append(it) }
			if (activeSummary != null) {
				if (isNotEmpty()) append("\n\n")
				append("Earlier conversation summary (the messages it covers are omitted from this request):\n")
				append(activeSummary.summary.trim())
			}
		}
		if (system.isNotBlank()) out += ChatMessage(Role.System, system)

		for (m in visible.drop(firstSent)) {
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
		if (includeCurrent) {
			val draft = _state.value.input.trim()
			val attachments = _state.value.pendingAttachments
			if (draft.isNotEmpty() || attachments.isNotEmpty()) {
				out += ChatMessage(
					role = Role.User,
					content = draft,
					images = loadImages(attachments),
				)
			}
		}
		return closeDanglingCalls(out)
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

	private fun encodeChangedFiles(changes: List<WorkspaceChange>): String =
		changes.joinToString(prefix = "[", postfix = "]") { change ->
			buildJsonObject {
				put("path", change.path)
				put("kind", change.kind.name)
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
				put(
					"arguments",
					if (c.name == "ssh_exec") redactSshArguments(c.argumentsJson) else c.argumentsJson,
				)
			}.toString()
		}
	}

	private fun updateParts(
		id: String,
		reasoning: String,
		answer: String,
		calls: List<ToolCallUi>,
		answerAfterCalls: Boolean,
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
				answerAfterCalls = answerAfterCalls,
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
		answerAfterCalls: Boolean,
	) {
		updateParts(id, reasoning.toString(), answer.toString(), calls.values.toList(), answerAfterCalls)

		// Hand the *new* answer text to the incremental parser. Only the
		// answer: reasoning is rendered as plain dimmed text in its own
		// collapsed section, so a reasoning delta appended here would be parsed
		// as part of the answer. The cursor guarantees each character is
		// delivered exactly once -- re-sending a prefix duplicates the reply and
		// skipping a slice loses text, and neither is visible after the stream
		// has ended.
		// The channel carries accumulated snapshots, not slices; see
		// replyDeltas. Sending the full answer is what makes a late first
		// collector and an already-queued value safe to reconcile.
		cursor.next(answer.toString())?.let { _ ->
			replyDeltas[id]?.trySend(answer.toString())
		}
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

	override fun onCleared() {
		// A cleared view model cannot deliver a later "false"; clear its stop
		// handler and publish one final idle status for the notification layer.
		TurnNotifications.clearStop(conversationId)
		onTurnStatus(notificationStatus().copy(streaming = false, phase = "Finished"))
		super.onCleared()
	}

	companion object {
		const val INTERRUPTED_MARKER = "[interrupted]"

		/** A key file larger than this is not a key; do not read it into memory. */
		private const val MAX_SSH_IDENTITY_BYTES = 512 * 1024

		/**
		 * Instructions for the derived-summary call.
		 *
		 * It is not asked to answer the conversation; inventing an answer in a
		 * summary would put words in the model's mouth on every later turn.
		 */
		val COMPACTION_SYSTEM_PROMPT = """
			You compact an earlier part of a conversation. Write a concise summary
			that will be prepended to the recent turns later. Preserve:
			- the user's goals, constraints and preferences;
			- decisions and conclusions;
			- file paths created or changed and their state;
			- unresolved questions and next steps;
			- tool results that still matter.
			Do not invent facts, do not answer the questions, and do not include
			secrets, credentials or API keys. Prefer specific names and paths over
			vague phrasing. Use Markdown headings and short bullets.
		""".trimIndent()

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
		 *
		 * 8 was hardcoded and is too low once an SSH call and a couple of file
		 * reads share a turn, so the default is now a setting. The bounds stay:
		 * this is a budget, not an "unlimited" mode.
		 */
		const val DEFAULT_TOOL_ROUNDS = 8
		const val MIN_TOOL_ROUNDS = 4
		const val MAX_TOOL_ROUNDS = 64

		/**
		 * Appended only to a final-answer retry, not stored.
		 *
		 * It comes after the cached tool-bearing prefix so the definitions and
		 * history can still hit the context cache, while the instruction tells
		 * the model to answer in text. Any call that ignores it is refused
		 * before execution.
		 */
		const val NO_TOOL_RETRY_INSTRUCTION =
			"Answer the last user message in prose only. Do not call any tool in this reply."

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

private fun mib(bytes: Long): String =
	String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))

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
		changedFiles = decodeChangedFiles(changedFilesJson),
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

/** The changed-file summary stored on the final assistant row. */
private fun decodeChangedFiles(raw: String?): List<WorkspaceChange> {
	if (raw.isNullOrBlank()) return emptyList()
	return runCatching {
		(Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray)
			.orEmpty()
			.mapNotNull { item ->
				val obj = item as? JsonObject ?: return@mapNotNull null
				val path = obj["path"]?.toString()?.trim('"') ?: return@mapNotNull null
				val kind = obj["kind"]?.toString()?.trim('"')?.let { raw ->
					WorkspaceChange.Kind.entries.firstOrNull {
						it.name.equals(raw, ignoreCase = true)
					}
				} ?: return@mapNotNull null
				WorkspaceChange(path, kind)
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
