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
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.files.tools.FileTools
import kotlinx.coroutines.Job
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
	 */
	private suspend fun publishModelState() {
		val list = agents.models().getOrNull().orEmpty()

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
		val id = model ?: agent?.model ?: list.firstOrNull()?.id ?: DEFAULT_MODEL

		val label = list.firstOrNull { it.id == id }?.displayName?.takeIf { it.isNotBlank() }
			?: if (model == null && agent?.model == null) "${id} (default)" else id

		_state.update {
			it.copy(
				// The choice, so the picker can show what was picked.
				model = model,
				// The resolved id, for looking anything up.
				effectiveModel = id,
				modelLabel = label,
				reasoningEffort = reasoningEffort,
				thinking = thinking,
			)
		}
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

	private suspend fun reload() {
		val loaded = repository.conversation(conversationId)
		_state.update { current ->
			current.copy(
				title = loaded?.entity?.title ?: current.title,
				messages = loaded?.messages.orEmpty().map { it.toUi() },
			)
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
		if (text.isEmpty() || _state.value.isStreaming) return

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
		)

		_state.update {
			it.copy(
				messages = it.messages + userMessage.toUi(),
				input = "",
				isStreaming = true,
				error = null,
			)
		}

		viewModelScope.launch {
			repository.appendMessage(userMessage, parent)
			if (_state.value.messages.count { it.fromUser } == 1) {
				repository.autoTitle(conversationId, text)
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
			val executed = executeCalls(outcome)
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

		_state.update {
			it.copy(
				messages = it.messages + ChatMessageUi(
					id = replyId,
					parentId = parent,
					fromUser = false,
					isStreaming = true,
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

		try {
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
						updateParts(replyId, reasoning.toString(), answer.toString(), calls.values.toList())
					}

					is StreamChunk.TextDelta -> {
						answer.append(chunk.text)
						updateParts(replyId, reasoning.toString(), answer.toString(), calls.values.toList())
					}

					is StreamChunk.ToolCallStart -> {
						calls[chunk.index] = ToolCallUi(
							index = chunk.index,
							id = chunk.id,
							name = chunk.name,
							state = ToolCallUi.State.Streaming,
						)
						updateParts(replyId, reasoning.toString(), answer.toString(), calls.values.toList())
					}

					is StreamChunk.ToolCallDelta -> {
						val existing = calls[chunk.index] ?: ToolCallUi(chunk.index, null, null)
						calls[chunk.index] = existing.copy(
							name = existing.name ?: chunk.nameDelta,
							// Concatenated, never parsed here: a fragment is not
							// valid JSON and parsing early is the classic bug.
							argumentsJson = existing.argumentsJson + (chunk.inputDelta ?: ""),
						)
						updateParts(replyId, reasoning.toString(), answer.toString(), calls.values.toList())
					}

					is StreamChunk.ToolCallEnd -> {
						calls[chunk.index]?.let { c ->
							calls[chunk.index] = c.copy(state = ToolCallUi.State.Ready)
						}
						updateParts(replyId, reasoning.toString(), answer.toString(), calls.values.toList())
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
			finishedCalls = calls.values.toList()
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
			updateMessage(replyId) { it.copy(isStreaming = false, finishReason = finish, usage = usage) }

			val finalUsage = usage
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
						error = failure,
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
	 * Run the calls a turn asked for.
	 *
	 * @return false if the turn should stop -- the user declined, or the run was
	 *   cancelled mid-way.
	 */
	private suspend fun executeCalls(outcome: TurnOutcome): Boolean {
		val tools = buildTools().associateBy { it.name }

		for (call in outcome.toolCalls) {
			val tool = tools[call.displayName]
			if (tool == null) {
				appendToolResult(call, "no such tool: ${call.displayName}", isError = true, outcome.messageId)
				continue
			}

			val parsed = parseArguments(call, outcome.messageId) ?: continue

			if (tool.needsApproval(parsed)) {
				val summary = describeCall(call, parsed)
				_state.update {
					it.copy(pendingApproval = PendingApproval(outcome.messageId, call, summary))
				}
				// Suspend here until the user decides. The continuation is
				// resumed by approvePendingCall/declinePendingCall, which is
				// why the pending state is stored rather than a dialog shown.
				val approved = awaitApproval()
				if (!approved) {
					appendToolResult(call, "The user declined this call.", isError = true, outcome.messageId)
					return false
				}
			}

			markCall(outcome.messageId, call.index, ToolCallUi.State.Running)
			val result = tool.execute(parsed)
			markCall(
				outcome.messageId,
				call.index,
				if (result.isError) ToolCallUi.State.Failed else ToolCallUi.State.Done,
			)
			appendToolResult(call, result.content, result.isError, outcome.messageId)
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

	private fun buildTools(): List<Tool> {
		val entity = conversation ?: return emptyList()
		return runCatching { FileTools.all(repository.workspace(entity)) }.getOrDefault(emptyList())
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

	private fun parseArguments(call: ToolCallUi, messageId: String): JsonObject? =
		try {
			Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }) as? JsonObject
				?: buildJsonObject { }
		} catch (e: Exception) {
			// Arguments that never became valid JSON. Reported rather than
			// retried: the model produced them, and it is the one that can fix
			// them on the next turn.
			viewModelScope.launch {
				appendToolResult(
					call,
					"could not parse arguments: ${e.message}",
					isError = true,
					messageId = messageId,
				)
			}
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

				m.fromUser -> out += ChatMessage(Role.User, m.plainText)

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
		 * Ceiling on model round trips within one user message.
		 *
		 * A model that keeps calling tools would otherwise generate forever at
		 * the user's expense, and because every round re-sends the whole
		 * transcript the cost of a runaway loop grows quadratically.
		 */
		const val MAX_TOOL_ROUNDS = 8
	}
}

private fun roleFromWire(value: String): Role = when (value) {
	"system" -> Role.System
	"assistant" -> Role.Assistant
	"tool" -> Role.Tool
	else -> Role.User
}

private fun MessageEntity.toUi(): ChatMessageUi {
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
		toolCalls = toolCallsJson?.let(::decodeCalls).orEmpty(),
	)
}

/** Stored as a JSON array of small objects; a malformed value is ignored. */
private fun decodeCalls(raw: String): List<ToolCallUi> = runCatching {
	Json.parseToJsonElement(raw).let { el ->
		(el as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { item ->
			val obj = item as? JsonObject ?: return@mapNotNull null
			ToolCallUi(
				index = obj["index"]?.toString()?.toIntOrNull() ?: 0,
				id = obj["id"]?.toString()?.trim('"'),
				name = obj["name"]?.toString()?.trim('"'),
				argumentsJson = obj["arguments"]?.toString()?.trim('"').orEmpty(),
				state = ToolCallUi.State.Done,
			)
		}
	}
}.getOrDefault(emptyList())
