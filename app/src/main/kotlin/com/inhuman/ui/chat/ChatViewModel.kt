package com.inhuman.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.R
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
import com.inhuman.core.engine.ChatEngine
import com.inhuman.core.engine.EngineDecision
import com.inhuman.core.engine.EngineEvent
import com.inhuman.core.engine.EngineFailure
import com.inhuman.core.engine.EngineToolCall
import com.inhuman.core.engine.EngineTurn
import com.inhuman.core.engine.EngineUsage
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
import com.inhuman.core.data.db.ToolsColumn
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
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.ai.ssh.SshTransport
import com.inhuman.core.ai.tools.SshArguments
import com.inhuman.core.ai.tools.SshDownloadTool
import com.inhuman.core.ai.tools.SshListTool
import com.inhuman.core.ai.tools.SshProbeTool
import com.inhuman.core.ai.tools.SshReadFileTool
import com.inhuman.core.ai.tools.SshUploadTool
import com.inhuman.core.ai.tools.SshWriteFileTool
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
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.UUID

/**
 * Drives one conversation, and hosts the shared turn loop.
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
 * The loop itself is `:core:engine`'s `ChatEngine`: stream, assemble calls, ask
 * before each one, execute, repeat. It lives in a platform-free module because
 * the desktop host runs the same loop and a second copy would answer "what
 * happened when the user declined" differently. What stays here is the Android
 * half: a row per round, the consent and approval dialogues, the workspace
 * checkpoints and the delta channel. *Which* tools exist and *whether* a call is
 * allowed are still app policy, injected through the engine's approval hook
 * rather than baked into the loop.
 *
 * The loop is bounded by the user's tool-round setting (default
 * [DEFAULT_TOOL_ROUNDS]). A model that keeps calling tools would otherwise
 * generate forever at the user's expense, and every iteration re-sends the
 * whole transcript, so an unbounded loop grows quadratically.
 */
class ChatViewModel(
	private val context: Context,
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
	/** SFTP for the same hosts; null when the build has no file transport. */
	private val sshFileTransport: SshFileTransport? = null,
	/**
	 * Whether the global SSH switch is on.
	 *
	 * Read per turn rather than copied: the user can change it in Settings
	 * while this view model stays alive, and a tool that is still registered
	 * after being switched off is a gate that opens the wrong way.
	 */
	private val sshToolEnabled: () -> Boolean = { false },
	/** User ceilings; the model may only ask for smaller values. */
	private val sshLimits: () -> SshLimits = { SshLimits() },
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
			current.pendingApproval != null -> context.getString(R.string.chat_phase_approval)
			current.pendingSshConsent != null -> context.getString(R.string.chat_phase_ssh)
			current.isCompacting -> context.getString(R.string.chat_phase_compacting)
			call != null -> context.getString(R.string.chat_phase_using, call.displayName)
			streaming && lastAssistant?.plainText.isNullOrBlank() ->
				context.getString(R.string.chat_phase_thinking)
			streaming -> context.getString(R.string.chat_phase_writing)
			else -> context.getString(R.string.chat_phase_finished)
		}
		val status = TurnStatus(
			conversationId = conversationId,
			title = current.title,
			phase = phase,
			startedAt = if (turnStartedAt == 0L) System.currentTimeMillis() else turnStartedAt,
			streaming = streaming,
			preview = if (!streaming) lastAssistant?.plainText?.take(240) else null,
			failed = !streaming && current.error != null,
			awaitingApproval = current.pendingApproval != null || current.pendingSshConsent != null,
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
	 * all suspend: the engine bridge finishes a reply inside a `finally` and has
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

	/**
	 * Replace the conversation's tool selection.
	 *
	 * The UI is the only caller, and it only offers tools the assistant already
	 * has or the conversation already had (`conversationToolPlan`). There is no
	 * tool-driven path to this method: a prompt injection cannot ask for it.
	 *
	 * The state is updated before the write so the menu count and the next turn
	 * see the new set immediately. A failed write reloads the row rather than
	 * leaving the screen claiming a selection that is not on disk.
	 */
	fun setConversationTools(tools: List<String>) {
		if (_state.value.isStreaming) return
		val entity = conversation ?: return
		val clean = tools.filter { it in ToolCatalog.names }.distinct()
		// Adding a command or upload capability to a conversation that was
		// granted only for reading must not inherit that grant. Removing tools
		// never resets it.
		val resetConsent = sshAllowed && widensDangerousSsh(entity.tools, clean)
		conversation = entity.copy(toolsJson = ToolsColumn.encode(clean))
		_state.update {
			it.copy(
				conversationTools = clean,
				toolsKnown = true,
				sshAllowed = if (resetConsent) false else it.sshAllowed,
			)
		}
		if (resetConsent) {
			sshAllowed = false
			Diag.i("ssh", "selection widened; consent reset")
		}
		Diag.i("tools", "conversation selection count=${clean.size}")
		viewModelScope.launch {
			runCatching {
				repository.setTools(conversationId, clean)
				if (resetConsent) repository.setSshAllowed(conversationId, false)
			}
				.onFailure { failure ->
					Diag.w("tools", "could not persist conversation tools", failure)
					reload()
				}
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
						error = context.getString(R.string.chat_error_no_vision),
					)
				}
				return@launch
			}

			val entity = conversation
			if (entity == null) {
				_state.update {
					it.copy(error = context.getString(R.string.chat_error_not_ready))
				}
				return@launch
			}

			val workspace = runCatching { repository.workspace(entity) }.getOrNull()
			if (workspace == null) {
				Diag.w("attach", "refused: workspace did not open conv=${Diag.shortId(entity.id)}")
				_state.update {
					it.copy(error = context.getString(R.string.chat_error_no_workspace))
				}
				return@launch
			}

			// The image is re-encoded before it is staged: decode, fix EXIF
			// orientation, fit the long edge, and compress under the same
			// maxReadBytes the send path enforces. This keeps a 12 MP phone
			// photo from becoming a 48 MB bitmap plus a base64 copy in the
			// request, and makes the stored copy the one that will be sent.
			val prepared = withContext(Dispatchers.IO) {
				runCatching {
					ImagePreprocessor.prepare(
						open = open,
						sourceMime = mimeType,
						maxBytes = workspace.limits.maxReadBytes,
					)
				}
			}.getOrElse { failure ->
				Diag.w("attach", "prepare failed mime=$mimeType: ${failure.message}")
				_state.update {
					it.copy(error = context.getString(R.string.chat_error_image_unreadable))
				}
				return@launch
			}

			// Into an attachments folder, so a picture the user chose is not
			// confused with a file the assistant wrote. They appear in the same
			// browser, and the distinction matters when deciding what to delete.
			val transfer = WorkspaceTransfer(workspace)
			val importName = prepared.fileName(displayName)
			val outcome = withContext(Dispatchers.IO) {
				transfer.import("attachments/" + transfer.uniqueName(importName)) {
					ByteArrayInputStream(prepared.bytes)
				}
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
								error = context.getString(
									R.string.chat_error_image_too_large,
									mib(imageLimit),
									mib(outcome.bytes),
								),
							)
						}
						return@launch
					}
					Diag.d(
						"attach",
						"prepared sourceMime=$mimeType outMime=${prepared.mimeType} " +
							"${prepared.width}x${prepared.height} bytes=${prepared.bytes.size} " +
							"quality=${prepared.quality}",
					)
					Diag.d(
						"attach",
						"imported path=${outcome.relativePath} bytes=${outcome.bytes} " +
							"mime=${prepared.mimeType}",
					)
					_state.update {
						it.copy(
							pendingAttachments = it.pendingAttachments + PendingAttachment(
								relativePath = outcome.relativePath,
								mimeType = prepared.mimeType,
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
				sshToolEnabled = sshToolEnabled(),
				conversationTools = loaded?.entity?.tools,
				agentTools = agent?.tools,
				toolsKnown = loaded?.entity != null,
				pendingSshConsent = null,
				rollbackAvailable = rollbackAvailable,
				rollbackBlockedReason = rollbackBlockedReason,
				snapshotStats = snapshotStats,
				snapshotTurns = snapshotTurns,
				snapshotRetention = snapshotRetention(),
				toolRoundLimit = toolRoundLimit(),
				toolCallLimit = toolCallLimit(),
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
				it.copy(error = context.getString(R.string.chat_error_not_enough_turns))
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
						error = context.getString(R.string.chat_error_changed_while_summarizing),
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
						error = context.getString(R.string.chat_error_summary_not_stored),
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
			_state.update {
				it.copy(error = context.getString(R.string.chat_error_nothing_to_retry))
			}
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
					error = context.getString(R.string.chat_error_add_key_demo, providerName),
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
					error = context.getString(R.string.chat_error_no_model_to_summarize),
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
				it.copy(
				isCompacting = false,
				error = context.getString(R.string.chat_error_summarizer_timeout),
			)
			}
			return null
		}
		if (failure != null) {
			_state.update {
				it.copy(
					isCompacting = false,
					error = context.getString(R.string.chat_error_summarizer_failed, failure),
				)
			}
			return null
		}
		val summary = output.toString().trim()
		if (summary.isBlank()) {
			_state.update {
				it.copy(
					isCompacting = false,
					error = context.getString(R.string.chat_error_summarizer_empty),
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
				it.copy(error = context.getString(R.string.chat_error_add_key_demo, providerName))
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
			_state.update {
				it.copy(error = context.getString(R.string.chat_error_point_gone))
			}
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
				it.copy(error = context.getString(R.string.chat_error_later_ssh))
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
				it.copy(error = context.getString(R.string.chat_error_checkpoint_missing))
			}
			return
		}

		val demo = demoEnabled()
		if (!demo && !agents.hasKey(providerId)) {
			_state.update {
				it.copy(error = context.getString(R.string.chat_error_add_key_demo, providerName))
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
							error = context.getString(R.string.chat_error_restore_failed),
						)
					}
					return@launch
				}
			}
			runCatching {
				repository.deleteDescendants(boundary.id, conversationId)
			}.onFailure { failure ->
				_state.update {
				it.copy(
					error = context.getString(R.string.chat_error_clear_later_failed, failure.message.orEmpty()),
				)
			}
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
				toolCallLimit = toolCallLimit(),
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
			_state.update {
				it.copy(error = context.getString(R.string.chat_error_stop_before_rewind))
			}
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
					error = context.getString(R.string.chat_error_rollback_ssh),
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
							error = context.getString(R.string.chat_error_snapshot_restore_failed),
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
				_state.update {
					it.copy(
						error = context.getString(
							R.string.chat_error_rewind_failed,
							failure.message.orEmpty(),
						),
					)
				}
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

	private fun toolCallLimit(): Int =
		secure.get(SecureStore.KEY_TOOL_CALLS)?.toIntOrNull()
			?.coerceIn(MIN_TOOL_CALLS, MAX_TOOL_CALLS)
			?: DEFAULT_TOOL_CALLS

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
					_state.update {
				it.copy(error = context.getString(R.string.chat_error_rename_failed))
			}
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
						_state.update {
				it.copy(error = context.getString(R.string.chat_error_workspace_not_found))
			}
					}
				}
				.onFailure { failure ->
					Diag.w("workspace", "promote failed", failure)
					_state.update {
				it.copy(error = context.getString(R.string.chat_error_save_workspace_failed))
			}
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
				toolCallLimit = toolCallLimit(),
				sshToolEnabled = sshToolEnabled(),
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
				it.copy(error = context.getString(R.string.chat_error_add_key_demo, providerName))
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
					it.copy(error = context.getString(R.string.chat_error_demo_no_request))
				}
				return@launch
			}
			val provider = agents.provider(providerId)?.let { agents.providerFromRow(it) }
			if (provider == null) {
				_state.update {
					it.copy(
				error = context.getString(R.string.chat_error_add_key_preview, providerName),
			)
				}
				return@launch
			}
			val turnModel = model ?: agent?.model
				?: agents.models(providerId).getOrNull()?.firstOrNull()?.id
			if (turnModel == null) {
				_state.update { it.copy(error = context.getString(R.string.chat_error_choose_model, providerName)) }
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
				context.getString(R.string.chat_rewind_blocked_ssh)
			lastTurn.any { it.hasLocalMutation() } && !startAvailable ->
				context.getString(R.string.chat_rewind_blocked_start_missing)
			else -> null
		}
		val points = mutableListOf(
			RegenerationPoint(
				checkpointId = SnapshotStore.START,
				label = context.getString(R.string.chat_rewind_from_message),
				afterMessageId = lastUser.id,
				checkpointAvailable = startAvailable,
				blockedReason = startBlocked,
				isUserMessage = true,
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
					context.getString(R.string.chat_rewind_blocked_ssh)
				startAvailable && !effectiveAvailable ->
					context.getString(R.string.chat_rewind_blocked_group_missing)
				!startAvailable && prefixLocal ->
					context.getString(R.string.chat_rewind_blocked_no_checkpoint)
				else -> null
			}
			val names = calls.mapNotNull { it.displayName }.distinct()
			group++
			points += RegenerationPoint(
				checkpointId = effectiveCheckpointId,
				label = if (names.isEmpty()) {
					context.getString(R.string.chat_rewind_after_group, group)
				} else {
					context.getString(
						R.string.chat_rewind_after_group_named,
						group,
						names.joinToString(", "),
					)
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
	 * Resolve the provider and model, then hand the whole turn to the shared
	 * engine.
	 *
	 * Every provider turn goes through `ChatEngine` now, SSH included. The
	 * legacy loop that owned the consent plumbing is gone rather than kept
	 * beside it: two loops meant two answers to "what happened when the user
	 * declined" -- one marking the call failed, one marking it declined -- and
	 * the desktop host would have had to reproduce whichever it happened to
	 * reach.
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
					error = context.getString(R.string.chat_error_no_key, providerName),
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
					error = context.getString(R.string.chat_error_choose_model, providerName),
				)
			}
			return
		}
		runTurnWithEngine(provider, turnModel, demo, includeTools, suppressToolCalls)
	}

	/**
	 * Bridge `ChatEngine` into the existing transcript/UI state.
	 *
	 * The engine owns the loop; this function owns everything that is Android
	 * state: persisting each round, the consent and approval dialogues, the
	 * workspace checkpoints, and the delta channel the transcript renders from.
	 */
	private suspend fun runTurnWithEngine(
		provider: ChatProvider,
		turnModel: String,
		demo: Boolean,
		includeTools: Boolean,
		suppressToolCalls: Boolean,
	) {
		val policies = loadPolicies()
		// A text-only retry keeps the tool definitions on a real provider so
		// the request prefix still matches the context cache; a transient
		// instruction and the engine's guard stop the call from running. Demo
		// is the exception: its script answers `request.tools`, not
		// instructions, so offering tools there would manufacture the exact call
		// the retry forbids.
		val tools = if (includeTools && !(suppressToolCalls && demo)) {
			buildTools(policies)
		} else {
			emptyList()
		}
		val maxRounds = toolRoundLimit()
		val maxCalls = toolCallLimit()
		// No assistant placeholder exists yet on this path: the latest state
		// message is the user's just-committed turn. Dropping it -- which the
		// legacy path does because it creates the placeholder first -- made the
		// model answer the previous turn. The phone transcript showed exactly
		// that: "帮我搜搜今天的新闻" received the answer to "你好".
		val history = buildWireHistory(includeCurrent = true).let { base ->
			if (suppressToolCalls) {
				base + ChatMessage(Role.System, NO_TOOL_RETRY_INSTRUCTION)
			} else {
				base
			}
		}

		var currentId: String? = null
		var currentParent: String? = null
		var currentText = StringBuilder()
		var currentReasoning = StringBuilder()
		var currentCalls = linkedMapOf<Int, ToolCallUi>()
		var currentUsage: EngineUsage? = null
		var currentAssistant: ChatMessage? = null
		var currentPersisted = false
		var cursor = DeltaCursor()
		var answerAfterCalls = false
		var publishedAt = 0L
		var firstTokenAt: Long? = null
		var startedAt = System.currentTimeMillis()
		var failure: String? = null
		var lastFinish: FinishReason? = null

		// A tool group is complete when the engine asks for another round: every
		// call in the previous round ran, and the next request is about to be
		// sent. That is the point to snapshot the workspace, so a Rewind point
		// can resume from the group rather than only from the end of the turn.
		// The end-of-turn call covers a group that was followed by a budget
		// stop instead of another round; the captured id makes it a no-op when
		// the group was already captured at a round boundary.
		var stoppedMidGroup = false
		var lastCapturedGroup: String? = null

		suspend fun captureGroupCheckpoint() {
			if (!turnSnapshotReady) return
			val lastResult = _state.value.messages.lastOrNull { it.isToolResult }?.id ?: return
			if (lastResult == lastCapturedGroup) return
			if (captureCheckpointAfterGroup()) lastCapturedGroup = lastResult
		}

		fun publish(force: Boolean = false) {
			val id = currentId ?: return
			val now = System.currentTimeMillis()
			if (!force && now - publishedAt < STREAM_PUBLISH_INTERVAL_MS) return
			publishedAt = now
			if (firstTokenAt == null && currentText.isNotEmpty()) firstTokenAt = now
			publishParts(id, currentReasoning, currentText, currentCalls, cursor, answerAfterCalls)
		}

		val engine = ChatEngine(
			provider = provider,
			approval = { tool, call ->
				gateEngineCall(tool, call, policies, currentId)
			},
			toolDispatcher = Dispatchers.IO,
		)

		try {
			engine.run(
				EngineTurn(
					model = turnModel,
					messages = history,
					tools = tools,
					maxRounds = maxRounds,
					maxCalls = maxCalls,
					thinking = thinking,
					reasoningEffort = reasoningEffort,
					userId = conversationId,
					suppressToolCalls = suppressToolCalls,
				),
			).collect { event ->
				when (event) {
					is EngineEvent.RoundStarted -> {
						// Round 2 means round 1's calls all ran: snapshot the
						// workspace now, while the transcript still ends at the
						// tool group the next request is building on.
						if (event.round > 1) captureGroupCheckpoint()
						currentId?.let { previous ->
							replyDeltas.remove(previous)?.close()
						}
						val parent = _state.value.messages.lastOrNull()?.id
						val replyId = UUID.randomUUID().toString()
						val channel = Channel<String>(Channel.CONFLATED)
						replyDeltas[replyId] = channel
						currentId = replyId
						currentParent = parent
						currentText = StringBuilder()
						currentReasoning = StringBuilder()
						currentCalls = linkedMapOf()
						currentUsage = null
						currentAssistant = null
						currentPersisted = false
						cursor = DeltaCursor()
						answerAfterCalls = false
						publishedAt = 0L
						startedAt = System.currentTimeMillis()
						firstTokenAt = null
						_state.update {
							it.copy(
								messages = it.messages + ChatMessageUi(
									id = replyId,
									parentId = parent,
									fromUser = false,
									isStreaming = true,
									deltas = channel,
								),
							)
						}
						provider.previewRequest(event.request)?.let { body ->
							_wire.value = WireSnapshot(
								requestJson = Json { prettyPrint = true }
									.encodeToString(JsonObject.serializer(), body),
								model = turnModel,
								capturedAt = System.currentTimeMillis(),
							)
						}
						Diag.i(
							"engine",
							"round=${event.round} provider=${provider.id} " +
								"history=${event.request.messages.size} tools=${tools.size}",
						)
					}

					is EngineEvent.ReasoningDelta -> {
						currentReasoning.append(event.text)
						publish()
					}

					is EngineEvent.TextDelta -> {
						if (currentCalls.isNotEmpty()) answerAfterCalls = true
						currentText.append(event.text)
						publish()
					}

					is EngineEvent.ToolCallReady -> {
						val id = currentId ?: return@collect
						val call = event.call.toUi()
						currentCalls[call.index] = call
						updateMessage(id) { it.copy(toolCalls = currentCalls.values.toList()) }
					}

					is EngineEvent.AssistantMessage -> {
						currentAssistant = event.message
						// The engine has already assembled the calls; render them
						// as Ready now rather than as empty cards until the first
						// ToolCallReady arrives.
						event.message.toolCalls.forEachIndexed { index, call ->
							currentCalls[index] = ToolCallUi(
								index = index,
								id = call.id,
								name = call.name,
								argumentsJson = call.argumentsJson,
								state = ToolCallUi.State.Ready,
							)
						}
						publish(force = true)
					}

					is EngineEvent.Usage -> currentUsage = event.usage

					is EngineEvent.RoundFinished -> {
						val id = currentId ?: return@collect
						lastFinish = event.reason
						val message = currentAssistant
						val calls = currentCalls.values.toList()
						val usage = event.usage?.toUi(firstTokenAt, startedAt)
						// A round that produced nothing is not a row. The legacy
						// loop removed the empty placeholder, and persisting it
						// here would put a blank assistant message on the wire
						// for every failed provider request.
						val hasPayload = message?.content?.isNotEmpty() == true ||
							message?.reasoning?.isNotBlank() == true ||
							calls.isNotEmpty()
						if (hasPayload) {
							withContext(NonCancellable) {
								repository.appendMessage(
									MessageEntity(
										id = id,
										conversationId = conversationId,
										parentId = currentParent,
										role = "assistant",
										content = message?.content.orEmpty(),
										reasoning = message?.reasoning?.takeIf { it.isNotBlank() },
										model = model,
										finishReason = event.reason.describe(),
										createdAt = System.currentTimeMillis(),
										promptTokens = usage?.promptTokens,
										completionTokens = usage?.completionTokens,
										cachedPromptTokens = usage?.cachedPromptTokens,
										reasoningTokens = usage?.reasoningTokens,
										timeToFirstTokenMs = usage?.timeToFirstTokenMs,
										generationMs = usage?.generationMs,
										toolCallsJson = calls.takeIf { it.isNotEmpty() }?.let(::encodeCalls),
									),
									currentParent,
								)
							}
							currentPersisted = true
						}
						replyDeltas.remove(id)?.close()
						updateMessage(id) {
							it.copy(
								isStreaming = false,
								finishReason = event.reason.describe(),
								usage = usage,
							)
						}
						refreshContext()
					}

					is EngineEvent.ToolCallResult -> {
						val id = currentId ?: return@collect
						val call = event.call.toUi()
						appendToolResult(call, event.result.content, event.result.isError, id)
						markCall(
							id,
							call.index,
							if (event.result.isError) ToolCallUi.State.Failed else ToolCallUi.State.Done,
						)
						if (tools.firstOrNull { it.name == event.call.name }?.mutates == true) {
							updateMessage(id) { it.copy(mutated = true) }
							repository.markMutated(id)
						}
					}

					is EngineEvent.ToolCallDeclined -> {
						val id = currentId ?: return@collect
						val call = event.call.toUi()
						stoppedMidGroup = true
						markCall(id, call.index, ToolCallUi.State.Declined)
						appendToolResult(call, event.message, isError = true, id)
					}

					is EngineEvent.ToolCallSkipped -> {
						val id = currentId ?: return@collect
						val call = event.call.toUi()
						stoppedMidGroup = true
						// The marker is what distinguishes "did not run" from
						// "ran and broke"; the engine's reason is prose the model
						// also reads.
						appendToolResult(
							call,
							"$SKIPPED_MARKER ${event.reason}",
							isError = true,
							id,
						)
						markCall(id, call.index, ToolCallUi.State.Failed)
					}

					is EngineEvent.Finished -> {
						lastFinish = event.reason
						_state.update { it.copy(isStreaming = false) }
					}

					is EngineEvent.Failed -> {
						// The cause is translated here rather than shown raw: the
						// engine is platform-free and cannot resolve a string
						// resource, and routing a provider turn through it used
						// to replace the localised limit messages with English.
						val text = when (event.cause) {
							EngineFailure.RoundLimit ->
								context.getString(R.string.chat_error_round_limit, maxRounds)

							EngineFailure.CallLimit ->
								context.getString(R.string.chat_error_call_limit, maxCalls)

							EngineFailure.ToolCallsSuppressed ->
								context.getString(R.string.chat_error_text_only_tool_call)

							EngineFailure.Provider -> event.message
						}
						failure = text
						currentId?.let { id -> updateMessage(id) { it.copy(error = text) } }
						_state.update {
							it.copy(
								isStreaming = false,
								error = text,
							)
						}
					}
				}
			}
			// A turn that stopped mid-group has no complete group to save; the
			// start checkpoint is what a rewind uses there.
			if (!stoppedMidGroup) captureGroupCheckpoint()
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} finally {
			val id = currentId
			if (id != null) {
				publish(force = true)
				var wroteRow = false
				if (!currentPersisted) {
					val text = currentText.toString()
					val reasoning = currentReasoning.toString()
					val calls = currentCalls.values.toList()
					val usage = currentUsage?.toUi(firstTokenAt, startedAt)
					val hasPayload = text.isNotEmpty() || reasoning.isNotEmpty() || calls.isNotEmpty()
					withContext(NonCancellable) {
						when {
							hasPayload -> {
								repository.appendMessage(
									MessageEntity(
										id = id,
										conversationId = conversationId,
										parentId = currentParent,
										role = "assistant",
										content = text,
										reasoning = reasoning.takeIf { it.isNotBlank() },
										model = model,
										finishReason = lastFinish?.describe(),
										// A stopped turn says so rather than
										// looking like an answer that just ended.
										error = failure ?: STOPPED_MESSAGE,
										createdAt = System.currentTimeMillis(),
										promptTokens = usage?.promptTokens,
										completionTokens = usage?.completionTokens,
										cachedPromptTokens = usage?.cachedPromptTokens,
										reasoningTokens = usage?.reasoningTokens,
										timeToFirstTokenMs = usage?.timeToFirstTokenMs,
										generationMs = usage?.generationMs,
										toolCallsJson = calls.takeIf { it.isNotEmpty() }
											?.let(::encodeCalls),
									),
									currentParent,
								)
								wroteRow = true
							}

							failure != null -> {
								// A first request that failed before producing a
								// token still gets a row. Otherwise the question
								// stays with no answer and no record that one was
								// attempted.
								repository.appendMessage(
									MessageEntity(
										id = id,
										conversationId = conversationId,
										parentId = currentParent,
										role = "assistant",
										content = "",
										model = model,
										error = failure,
										createdAt = System.currentTimeMillis(),
									),
									currentParent,
								)
								wroteRow = true
							}

							else -> {
								// Nothing arrived and nothing failed: there is no
								// row to keep, only the placeholder to remove.
								_state.update { s ->
									s.copy(messages = s.messages.filterNot { it.id == id })
								}
							}
						}
					}
					currentPersisted = true
				}
				replyDeltas.remove(id)?.close()
				updateMessage(id) { it.copy(isStreaming = false, error = it.error ?: failure) }
				if (wroteRow) refreshContext()
			}
			_state.update { it.copy(isStreaming = false) }
		}
	}

	/**
	 * The one gate every tool call passes before the engine runs it.
	 *
	 * Order is behaviour, not style. A malformed remote call must be refused
	 * before the strongest warning in the app is shown; the global switch must
	 * be read after the dialogue because it can be turned off while the user is
	 * deciding; and the capability grant is checked before ordinary policy
	 * because it is a grant of a capability, not a per-call question.
	 */
	private suspend fun gateEngineCall(
		tool: Tool,
		call: EngineToolCall,
		policies: PolicySet,
		messageId: String?,
	): EngineDecision {
		val id = messageId ?: return EngineDecision.Deny("engine turn has no message", stopTurn = true)
		val raw = try {
			Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject
		} catch (e: Exception) {
			return EngineDecision.Deny("invalid tool arguments: ${e.message}", stopTurn = false)
		}
		val parsed = runCatching { tool.normalize(raw) }.getOrElse { failure ->
			Diag.w("tool", "argument normalisation failed name=${tool.name}", failure)
			return EngineDecision.Deny(
				"invalid tool arguments: ${failure.message}",
				stopTurn = false,
			)
		}

		val remote = ToolCatalog.all.firstOrNull { it.name == tool.name }?.remote == true
		if (remote) {
			// Never show a consent dialogue for a call the tool would reject as
			// malformed: the dialogue describes a call that can actually happen.
			SshArguments.missingRequired(tool.name, parsed)?.let { missing ->
				Diag.w("ssh", "malformed name=${tool.name} missing=$missing")
				return EngineDecision.Deny(
					"${tool.name}: $missing is required",
					stopTurn = false,
				)
			}
			// The registry was built before the turn started, and Settings can
			// change while it runs. Re-read "off" here so it means off.
			if (!sshToolEnabled()) {
				Diag.i("ssh", "refused: global switch off")
				return EngineDecision.Deny(
					"${REFUSED_MARKER} SSH is disabled in Settings.",
					stopTurn = false,
				)
			}
			// Turning the global switch off revokes every conversation's
			// acknowledgement. The in-memory flag is stale after off-then-on, so
			// the persisted answer decides whether the user has to be asked
			// again.
			if (sshAllowed) {
				val persisted = runCatching { repository.sshAllowed(conversationId) }
					.getOrNull()
				if (persisted == false) {
					sshAllowed = false
					_state.update { it.copy(sshAllowed = false) }
					Diag.i("ssh", "persisted consent gone; asking again")
				}
			}
		}

		val policyPath = resolvedPolicyPath(parsed)
		val policy = policies.effective(tool.name, policyPath)
		if (policy == ToolPolicy.Deny || policy == ToolPolicy.Blind) {
			Diag.i("policy", "refused tool=${tool.name} path=${policyPath ?: "-"} policy=$policy")
			return EngineDecision.Deny(
				// Deliberately the same sentence for both. `Blind` is a stronger
				// implementation of the same decision; telling the model "you
				// cannot even see this" invites it to probe for the difference.
				"${REFUSED_MARKER} This path or tool is not available in this conversation.",
				stopTurn = false,
			)
		}

		// A capability grant, not a per-call approval: the first SSH call needs
		// an explicit acknowledgement that later calls inherit.
		if (remote && !sshAllowed) {
			val host = stringArg(parsed, "host").orEmpty()
			val username = stringArg(parsed, "username").orEmpty()
			val operation = remoteOperation(tool.name, parsed)
			val selection = conversation?.tools ?: agent?.tools
			val canExec = selection?.contains("ssh_exec") == true
			val canUpload = selection?.contains("ssh_upload") == true
			val canWriteFile = selection?.contains("ssh_write_file") == true
			Diag.i(
				"ssh",
				"consent ask host=$host user=$username tool=${tool.name} " +
					"exec=$canExec upload=$canUpload write=$canWriteFile " +
					"operationChars=${operation.length}",
			)
			_state.update {
				it.copy(
					pendingSshConsent = SshConsent(
						messageId = id,
						call = call.toUi(),
						host = host,
						username = username,
						operation = operation,
						canExec = canExec,
						canUpload = canUpload,
						canWriteFile = canWriteFile,
						hostKeyFingerprint = stringArg(parsed, "hostKeyFingerprint")
							?.trim()
							?.takeIf { it.isNotEmpty() },
					),
				)
			}
			val allowed = awaitSshConsent()
			if (!allowed) {
				Diag.i("ssh", "consent denied host=$host")
				markCall(id, call.index, ToolCallUi.State.Declined)
				return EngineDecision.Deny(
					DECLINED_MARKER + " The user declined SSH access for this conversation.",
					stopTurn = true,
					declined = true,
				)
			}
			sshAllowed = true
			runCatching { repository.setSshAllowed(conversationId, true) }
				.onFailure { Diag.w("ssh", "could not persist consent", it) }
			_state.update { it.copy(sshAllowed = true) }
			Diag.i("ssh", "consent allow host=$host")
			if (!sshToolEnabled()) {
				Diag.i("ssh", "global switch turned off during consent")
				return EngineDecision.Deny(
					"${REFUSED_MARKER} SSH was disabled in Settings before the call ran.",
					stopTurn = false,
				)
			}
		}

		if (policy == ToolPolicy.Ask || tool.needsApproval(parsed)) {
			val ui = call.toUi()
			val summary = describeCall(ui, parsed)
			Diag.i(
				"approval",
				"ask tool=${tool.name} path=${policyPath ?: "-"} " +
					"rule=${if (policy == ToolPolicy.Ask) "policy" else "tool"} " +
					"args=${parsed.toString().length}B",
			)
			_state.update {
				it.copy(
					pendingApproval = PendingApproval(
						messageId = id,
						call = ui,
						summary = summary,
						// The ordinary dialogue must never show a credential,
						// even when a rule makes an SSH call ask.
						details = if (remote) sshApprovalDetails(tool.name, parsed) else null,
					),
				)
			}
			val approved = awaitApproval()
			if (!approved) {
				markCall(id, call.index, ToolCallUi.State.Declined)
				return EngineDecision.Deny(
					DECLINED_MARKER + " The user declined this call.",
					stopTurn = true,
					declined = true,
				)
			}
		}

		// Secure the pre-turn workspace before the first local mutation. A
		// rollback that cannot restore the files must not be offered; refusing
		// the mutation is safer than deleting the turn later and leaving the
		// changes behind. This runs after the user decides, so a declined call
		// neither captures a checkpoint nor marks the turn irreversible.
		if (tool.mutates) {
			if (!tool.reversible) {
				turnIrreversible = true
			} else if (!turnSnapshotReady) {
				val anchor = turnAnchorId ?: _state.value.messages.lastOrNull { it.fromUser }?.id
				val entity = conversation
				val captured = anchor != null && entity != null &&
					runCatching { repository.captureStartCheckpoint(entity, anchor) }
						.getOrElse { failure ->
							Diag.w("rollback", "snapshot capture threw", failure)
							false
						}
				if (!captured) {
					Diag.w("rollback", "refused mutation: no snapshot")
					return EngineDecision.Deny(
						"Could not create a rollback snapshot, so this workspace change was refused.",
						stopTurn = false,
					)
				}
				turnSnapshotReady = true
				Diag.i("rollback", "snapshot captured for turn")
			}
		}

		markCall(id, call.index, ToolCallUi.State.Running)
		return EngineDecision.Allow
	}

	private fun EngineToolCall.toUi() = ToolCallUi(
		index = index,
		id = id,
		name = name,
		argumentsJson = argumentsJson,
		state = ToolCallUi.State.Ready,
	)

	private fun EngineUsage.toUi(firstTokenAt: Long?, startedAt: Long) = UsageUi(
		promptTokens = promptTokens,
		completionTokens = completionTokens,
		cachedPromptTokens = cachedPromptTokens,
		reasoningTokens = reasoningTokens,
		timeToFirstTokenMs = firstTokenAt?.let { it - startedAt },
		generationMs = firstTokenAt?.let { System.currentTimeMillis() - it },
	)

	/**
	 * Save the workspace after a complete tool group, so regeneration can
	 * resume from this point rather than only from the end of the turn.
	 *
	 * A turn that never touched the workspace has no start checkpoint and needs
	 * no checkpoint here: its workspace is unchanged.
	 */
	private suspend fun captureCheckpointAfterGroup(): Boolean {
		if (!turnSnapshotReady) return false
		val entity = conversation ?: return false
		val anchor = turnAnchorId ?: return false
		val lastResult = _state.value.messages.lastOrNull { it.isToolResult } ?: return false
		val saved = runCatching {
			repository.captureCheckpoint(entity, anchor, lastResult.id)
		}.getOrElse { failure ->
			Diag.w("rollback", "tool checkpoint capture threw", failure)
			false
		}
		if (saved) Diag.i("rollback", "checkpoint after tool group")
		return saved
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
					error = context.getString(
						R.string.chat_error_workspace_open_failed,
						failure.message.orEmpty(),
					),
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
			val ws = workspace
			if (ws != null && sshToolEnabled()) {
				val limits = sshLimits()
				val readIdentity: (String) -> ByteArray? = { path ->
					when (val resolved = ws.resolve(path)) {
						is SessionWorkspace.Resolution.Ok -> resolved.file
							.takeIf { it.isFile && it.length() <= MAX_SSH_IDENTITY_BYTES }
							?.readBytes()
						is SessionWorkspace.Resolution.Rejected -> null
					}
				}
				// Uploads may be larger than a key file: read up to a bounded
				// multiple of the tool's own 1 MiB limit so the tool can return
				// "over the limit" instead of a misleading "not found".
				val readUpload: (String) -> ByteArray? = { path ->
					when (val resolved = ws.resolve(path)) {
						is SessionWorkspace.Resolution.Ok -> resolved.file
							.takeIf { it.isFile && it.length() <= MAX_SSH_UPLOAD_READ_BYTES }
							?.readBytes()
						is SessionWorkspace.Resolution.Rejected -> null
					}
				}
				if (sshTransport != null && selected?.contains("ssh_exec") == true) {
					add(SshExecTool(sshTransport, limits, readIdentity))
				}
				if (sshTransport != null && selected?.contains("ssh_probe") == true) {
					add(SshProbeTool(sshTransport, limits, readIdentity))
				}
				if (sshFileTransport != null && selected?.contains("ssh_list") == true) {
					add(
						SshListTool(
							transport = sshFileTransport,
							readWorkspaceFile = readIdentity,
							maxEntries = limits.listCeilingEntries,
						),
					)
				}
				if (sshFileTransport != null && selected?.contains("ssh_read_file") == true) {
					add(
						SshReadFileTool(
							transport = sshFileTransport,
							readWorkspaceFile = readIdentity,
							maxBytes = limits.readCeilingBytes,
						),
					)
				}
				if (sshFileTransport != null && selected?.contains("ssh_upload") == true) {
					add(
						SshUploadTool(
							transport = sshFileTransport,
							readWorkspaceFile = readUpload,
							maxBytes = ws.limits.maxReadBytes,
						),
					)
				}
				if (sshFileTransport != null && selected?.contains("ssh_write_file") == true) {
					add(
						SshWriteFileTool(
							transport = sshFileTransport,
							readWorkspaceFile = readIdentity,
						),
					)
				}
				if (sshFileTransport != null && selected?.contains("ssh_download") == true) {
					add(
						SshDownloadTool(
							transport = sshFileTransport,
							readWorkspaceFile = readIdentity,
							saveWorkspaceFile = { path, bytes, overwrite ->
								saveDownload(ws, path, bytes, overwrite)
							},
							maxBytes = ws.limits.maxWriteBytes,
						),
					)
				}
			}
		}.filter { ToolCatalog.permits(selected, it.name) }
	}

	/**
	 * Put a downloaded file into the workspace.
	 *
	 * The overwrite check lives here rather than in the transport: the remote
	 * side cannot see the workspace, and an existing local file is the user's,
	 * not something the model may replace by accident. `resolve` confines both
	 * the file and the parent folder that is created for it.
	 */
	private fun saveDownload(
		workspace: SessionWorkspace,
		path: String,
		bytes: ByteArray,
		overwrite: Boolean,
	): String {
		val target = when (val resolved = workspace.resolve(path)) {
			is SessionWorkspace.Resolution.Ok -> resolved.file
			is SessionWorkspace.Resolution.Rejected ->
				throw IllegalArgumentException(resolved.reason)
		}
		if (target.isDirectory) throw IllegalArgumentException("$path is a directory")
		if (target.exists() && !overwrite) {
			throw IllegalArgumentException("$path already exists; pass overwrite=true")
		}
		target.parentFile?.mkdirs()
		return when (
			val outcome = WorkspaceTransfer(workspace).import(
				destinationPath = path,
				maxBytes = workspace.limits.maxWriteBytes,
				open = { ByteArrayInputStream(bytes) },
			)
		) {
			is WorkspaceTransfer.Outcome.Ok -> outcome.relativePath
			is WorkspaceTransfer.Outcome.Failed -> throw IllegalArgumentException(outcome.reason)
		}
	}

	private fun spec(tool: Tool) = ToolSpec(
		name = tool.name,
		description = tool.description,
		parameters = tool.parameters,
	)

	/**
	 * What the first-call consent dialogue shows.
	 *
	 * `ssh_exec` shows the command; the transfers show the two paths with the
	 * direction spelled out, because "which end is being overwritten" is the
	 * question that matters there.
	 */
	private fun remoteOperation(toolName: String, args: JsonObject): String = when (toolName) {
		"ssh_upload" -> context.getString(
			R.string.ssh_operation_upload,
			stringArg(args, "localPath").orEmpty(),
			stringArg(args, "remotePath").orEmpty(),
		)
		"ssh_download" -> context.getString(
			R.string.ssh_operation_download,
			stringArg(args, "remotePath").orEmpty(),
			stringArg(args, "localPath").orEmpty(),
		)
		// The read-only profile used to fall through to `command`, which it
		// does not have: the first-call dialogue showed the host and then an
		// empty "First operation" line. The tools' own blurbs are reused rather
		// than adding a second set of strings that could drift from them.
		"ssh_probe" -> context.getString(R.string.tool_blurb_ssh_probe)
		"ssh_list" -> "${context.getString(R.string.tool_blurb_ssh_list)}: ${
			stringArg(args, "remotePath").orEmpty()
		}"
		"ssh_read_file" -> "${context.getString(R.string.tool_blurb_ssh_read_file)}: ${
			stringArg(args, "remotePath").orEmpty()
		}"
		"ssh_write_file" -> "${context.getString(R.string.tool_blurb_ssh_write_file)}: ${
			stringArg(args, "remotePath").orEmpty()
		}"
		else -> stringArg(args, "command").orEmpty()
	}

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

		"ssh_upload" -> "Upload ${stringArg(args, "localPath") ?: "a file"} to " +
			"${stringArg(args, "host") ?: "a remote host"}:${
				stringArg(args, "remotePath") ?: "a path"
			}?"

		"ssh_download" -> "Download ${
			stringArg(args, "host") ?: "a remote host"
		}:${stringArg(args, "remotePath") ?: "a file"} to " +
			"${stringArg(args, "localPath") ?: "the workspace"}?"

		else -> "Run ${call.displayName}?"
	}

	/** Approval details for SSH, with credentials left out deliberately. */
	private fun sshApprovalDetails(toolName: String, args: JsonObject): String = buildString {
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
		append("\noperation:\n").append(remoteOperation(toolName, args))
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
					if (ToolCatalog.all.firstOrNull { it.name == c.name }?.remote == true) {
						redactSshArguments(c.argumentsJson)
					} else {
						c.argumentsJson
					},
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
		onTurnStatus(
			notificationStatus().copy(
				streaming = false,
				phase = context.getString(R.string.chat_phase_finished),
			),
		)
		super.onCleared()
	}

	companion object {
		const val INTERRUPTED_MARKER = "[interrupted]"

		/** A key file larger than this is not a key; do not read it into memory. */
		private const val MAX_SSH_IDENTITY_BYTES = 512 * 1024
	private const val MAX_SSH_UPLOAD_READ_BYTES = 16L * 1024 * 1024

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
		 * Total tool calls across all rounds in one turn.
		 *
		 * The round limit bounds model round trips; a single round can contain
		 * several calls. This second ceiling is what stops one round from
		 * executing thirty writes after the model was given a small budget.
		 */
		const val DEFAULT_TOOL_CALLS = 32
		const val MIN_TOOL_CALLS = 1
		const val MAX_TOOL_CALLS = 256

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
