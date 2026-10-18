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
import com.inhuman.core.common.diag.Diag
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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
	 * Search and fetch are separate seams because they are separate DeepSeek
	 * APIs with different failure modes, and one can exist without the other.
	 */
	private val searchProvider: SearchProvider? = null,
	private val fetchProvider: FetchProvider? = null,
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
) : ViewModel() {

	private val _state = MutableStateFlow(ChatUiState())
	val state: StateFlow<ChatUiState> = _state.asStateFlow()

	private var conversation: ConversationEntity? = null
	private var streamJob: Job? = null
	private var sshAllowed: Boolean = false

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
		val fetchedResult = runCatching { agents.models() }
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
		agents.models(force = force)

	/** The models already held in memory, without making a request. */
	suspend fun cachedModels(): List<RemoteModel> = agents.cachedModelList().orEmpty()

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
				sshAllowed = sshAllowed,
				pendingSshConsent = null,
				// Read off the rebuilt list: the remaining transcript-derived
				// values have to agree with the messages that are on screen.
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

		// Regeneration is deliberately **tool-less**. The request includes the
		// transcript up to the question, including any earlier tool calls and
		// results, but offers no tools, so a retry cannot run SSH, write a file
		// or spend money again. That is why a pure-text reply after a mutating
		// tool can be regenerated at all: the old blanket "this turn mutated"
		// refusal was protecting against a replayed tool call that this path
		// makes impossible.
		val questionId = lastReply.parentId
		if (questionId == null) {
			Diag.d("turn", "regenerate ignored: reply has no parent")
			return
		}

		val demo = demoEnabled()
		val apiKey = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY)
		if (!demo && apiKey.isNullOrBlank()) {
			Diag.w("turn", "regenerate refused: no API key and demo mode off")
			_state.update { it.copy(error = "Add a DeepSeek API key first, or switch on demo mode.") }
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

		streamJob = viewModelScope.launch {
			runTurn(apiKey = apiKey, demo = demo, includeTools = false)
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
	 * This deletes the user row and everything below it -- the assistant turn,
	 * its tool calls and results, and any later turns. It does **not** undo the
	 * workspace or a remote command: the point is to let the user edit and send
	 * again, not to pretend a tool never ran. The UI warns separately when the
	 * turn contains an irreversible call.
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
		Diag.i(
			"turn",
			"rollback conv=${Diag.shortId(conversationId)} " +
				"message=${Diag.shortId(target.id)}",
		)
		viewModelScope.launch {
			runCatching {
				repository.deleteSubtree(
					rootId = target.id,
					parentId = target.parentId,
					conversationId = conversationId,
				)
			}.onFailure { failure ->
				Diag.w("turn", "rollback failed", failure)
				_state.update { it.copy(error = "Could not rewind the conversation: ${failure.message}") }
				return@launch
			}
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
	 * Re-read the provider configuration.
	 *
	 * The view model is activity-scoped so a transcript survives a trip to
	 * settings; the cost is that `init` runs once, and a key added there would
	 * otherwise go unnoticed until the app restarted.
	 */
	fun refreshConfiguration() {
		val demo = demoEnabled()
		val hasKey = secure.hasUsable(SecureStore.KEY_DEEPSEEK_API_KEY)
		Diag.d("config", "demo=$demo hasKey=$hasKey")
		_state.update { it.copy(demoMode = demo, configured = demo || hasKey) }
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
		if ((text.isEmpty() && attachments.isEmpty()) || _state.value.isStreaming) {
			Diag.d(
				"turn",
				"send ignored chars=${text.length} attachments=${attachments.size} " +
					"streaming=${_state.value.isStreaming}",
			)
			return
		}

		val demo = demoEnabled()
		val apiKey = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY)
		if (!demo && apiKey.isNullOrBlank()) {
			Diag.w("turn", "send refused: no API key and demo mode off")
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
		Diag.i(
			"turn",
			"send conv=${Diag.shortId(conversationId)} chars=${text.length} " +
				"attachments=${attachments.size} demo=$demo " +
				"messages=${_state.value.messages.size}",
		)
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
	private suspend fun runTurn(apiKey: String?, demo: Boolean, includeTools: Boolean = true) {
		val provider: ChatProvider =
			if (demo) DemoProvider() else DeepSeekProvider(apiKey = apiKey!!)

		// Read once, before the tools are built, because the rule set decides
		// what `list_files` is even allowed to show. A path the user marked
		// `Blind` has to be absent from the listing, not merely refused when
		// named -- a filename leaks on its own.
		val policies = loadPolicies()
		// A regenerate request carries the transcript, including tool results,
		// but no tools. It can therefore rewrite the final text without
		// re-running an SSH command or a file write.
		val tools = if (includeTools) buildTools(policies) else emptyList()
		val toolSpecs = tools.map { spec(it) }
		Diag.i(
			"turn",
			"run start provider=${provider.id} " +
				"model=${model ?: agent?.model ?: DEFAULT_MODEL} thinking=$thinking " +
				"effort=${reasoningEffort ?: "-"} tools=${tools.size} " +
				"maxRounds=$MAX_TOOL_ROUNDS demo=$demo",
		)

		var rounds = 0
		while (rounds < MAX_TOOL_ROUNDS) {
			rounds++
			val outcome = streamOneTurn(provider, tools, toolSpecs)

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
				// This is a durable record about replay-with-tools; regeneration
				// is tool-less and does not use it.
				if (tool.mutates) {
					// Marking the message is the whole of the bookkeeping. There
					// is no second `currentTurnMutated` flag to keep in step:
					// that gate was removed when regeneration became tool-less.
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
		return true
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
			searchProvider?.let { add(WebSearchTool(it)) }
			fetchProvider?.let { add(WebFetchTool(it)) }
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
		// Both are DeepSeek's paid APIs, and this sentence is shown only when a
		// rule makes them ask -- which is exactly the moment the cost is worth
		// repeating.
		"web_search" -> "Search the web for \"${
			stringArg(args, "query")?.take(80) ?: "a query"
		}\"? (DeepSeek's paid Search API)"

		"web_fetch" -> "Fetch ${
			stringArg(args, "url")?.take(100) ?: "a URL"
		}? (DeepSeek's paid Fetch API)"

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

		const val INTERRUPTED_MARKER = "[interrupted]"

		/** A key file larger than this is not a key; do not read it into memory. */
		private const val MAX_SSH_IDENTITY_BYTES = 512 * 1024

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
