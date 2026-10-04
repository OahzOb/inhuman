package com.inhuman.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.secret.SecureStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Drives one conversation.
 *
 * ## How streaming meets persistence
 *
 * Text is accumulated in memory while a reply arrives and written **once**, at
 * the end. Writing per token would put a database transaction behind every
 * chunk: at the rate these models emit, that is thousands of writes for one
 * answer, and SQLite's write-ahead log would spend the whole generation
 * flushing. The visible cost is a transcript that is lost if the process dies
 * mid-answer, which is an acceptable trade for a reply that arrives smoothly.
 *
 * Reasoning is accumulated alongside the answer rather than discarded after
 * display, because replaying a thinking turn without it is rejected outright --
 * measured against the live API, not inferred from documentation.
 */
class ChatViewModel(
	private val repository: ConversationRepository,
	private val secure: SecureStore,
	private val conversationId: String,
) : ViewModel() {

	private val _state = MutableStateFlow(ChatUiState())
	val state: StateFlow<ChatUiState> = _state.asStateFlow()

	private var conversation: ConversationEntity? = null
	private var streamJob: Job? = null

	/** The model to use when the conversation has not chosen one. */
	private var model: String = DEFAULT_MODEL

	init {
		viewModelScope.launch {
			conversation = repository.conversation(conversationId)?.entity
			conversation?.model?.let { model = it }
			refreshConfiguration()
			reload()
		}
	}

	/** Reload the transcript from storage. */
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
				messages = s.messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it },
			)
		}
	}

	fun send() {
		val text = _state.value.input.trim()
		if (text.isEmpty() || _state.value.isStreaming) return

		val demo = demoEnabled()
		val apiKey = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY)
		if (!demo && apiKey.isNullOrBlank()) {
			_state.update { it.copy(error = "Add a DeepSeek API key first, or switch on demo mode.") }
			return
		}

		// The turn being answered is whatever is currently last and complete.
		val parent = _state.value.messages.lastOrNull()?.id

		val userMessage = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parent,
			role = Role.User.wire,
			content = text,
			createdAt = System.currentTimeMillis(),
		)
		val replyId = UUID.randomUUID().toString()

		// Shown immediately, not persisted until it has content. A row for an
		// empty reply would survive a crash and read as a message the
		// assistant never sent.
		_state.update {
			it.copy(
				messages = it.messages + userMessage.toUi() + ChatMessageUi(
					id = replyId,
					parentId = userMessage.id,
					fromUser = false,
					isStreaming = true,
				),
				input = "",
				isStreaming = true,
				error = null,
			)
		}

		viewModelScope.launch {
			repository.appendMessage(userMessage, parent)
			// Name the conversation after its first user message. Done before
			// the reply so the list is readable even if generation fails.
			if (_state.value.messages.count { it.fromUser } == 1) {
				repository.autoTitle(conversationId, text)
				// Re-read from the repository rather than deriving the title
				// here, so the truncation rule lives in exactly one place.
				// Reading it *after* autoTitle matters: the earlier version
				// read it before, so the bar kept saying "New conversation"
				// while the list already showed the generated name.
				val stored = repository.conversation(conversationId)?.entity?.title
				if (stored != null) _state.update { it.copy(title = stored) }
			}
		}

		// Build the wire transcript from the active branch, excluding the empty
		// reply. Reasoning is included because a thinking turn replayed without
		// it is rejected.
		val history = _state.value.messages
			.dropLast(1)
			.filter { it.plainText.isNotBlank() }
			.map {
				ChatMessage(
					role = if (it.fromUser) Role.User else Role.Assistant,
					content = it.plainText,
					reasoning = it.reasoningText.takeIf { r -> r.isNotBlank() },
				)
			}

		val provider: ChatProvider =
			if (demo) DemoProvider() else DeepSeekProvider(apiKey = apiKey!!)

		streamJob = viewModelScope.launch {
			val reasoning = StringBuilder()
			val answer = StringBuilder()
			var usage: UsageUi? = null
			var finish: String? = null
			var failure: String? = null

			try {
				provider.stream(
					ChatRequest(
						model = model,
						messages = history,
						thinking = false,
						userId = conversationId,
					),
				).collect { chunk ->
					when (chunk) {
						is StreamChunk.ReasoningDelta -> {
							reasoning.append(chunk.text)
							updateParts(replyId, reasoning.toString(), answer.toString())
						}

						is StreamChunk.TextDelta -> {
							answer.append(chunk.text)
							updateParts(replyId, reasoning.toString(), answer.toString())
						}

						is StreamChunk.Usage -> {
							usage = UsageUi(
								promptTokens = chunk.promptTokens,
								completionTokens = chunk.completionTokens,
								cachedPromptTokens = chunk.cachedPromptTokens,
								reasoningTokens = chunk.reasoningTokens,
							)
							val u = usage
							updateMessage(replyId) { it.copy(usage = u) }
						}

						is StreamChunk.Finish -> finish = chunk.reason.describe()

						is StreamChunk.Failure -> {
							failure = chunk.message
							updateMessage(replyId) { it.copy(error = chunk.message) }
							_state.update { it.copy(error = chunk.message) }
						}

						is StreamChunk.TextStart,
						is StreamChunk.TextEnd,
						is StreamChunk.ReasoningStart,
						is StreamChunk.ReasoningEnd,
						is StreamChunk.ToolCallStart,
						is StreamChunk.ToolCallDelta,
						is StreamChunk.ToolCallEnd,
						is StreamChunk.Raw,
						-> Unit
					}
				}
			} finally {
				_state.update { it.copy(isStreaming = false) }
				updateMessage(replyId) { it.copy(isStreaming = false, finishReason = finish) }

				// Persist once, now that the text has stopped changing. Only if
				// something arrived: an empty reply is not a message.
				if (answer.isNotEmpty() || reasoning.isNotEmpty()) {
					repository.appendMessage(
						MessageEntity(
							id = replyId,
							conversationId = conversationId,
							parentId = userMessage.id,
							role = Role.Assistant.wire,
							content = answer.toString(),
							reasoning = reasoning.toString().takeIf { it.isNotBlank() },
							model = model,
							finishReason = finish,
							error = failure,
							createdAt = System.currentTimeMillis(),
							promptTokens = usage?.promptTokens,
							completionTokens = usage?.completionTokens,
							cachedPromptTokens = usage?.cachedPromptTokens,
							reasoningTokens = usage?.reasoningTokens,
						),
						userMessage.id,
					)
				} else {
					// Nothing to save. Drop the placeholder so a later reload
					// does not resurrect it as an empty bubble.
					_state.update { s ->
						s.copy(messages = s.messages.filterNot { it.id == replyId })
					}
				}
			}
		}
	}

	private fun updateParts(id: String, reasoning: String, answer: String) {
		updateMessage(id) { m ->
			val parts = buildList {
				if (reasoning.isNotEmpty()) {
					add(AssistantPart("r", AssistantPart.Kind.Reasoning, reasoning))
				}
				if (answer.isNotEmpty()) {
					add(AssistantPart("t", AssistantPart.Kind.Text, answer))
				}
			}
			m.copy(parts = parts, plainText = answer, reasoningText = reasoning)
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
		/**
		 * A default, not the truth.
		 *
		 * The settings screen lists what `GET /models` reports; this is only
		 * what to use before that has happened.
		 */
		const val DEFAULT_MODEL = "deepseek-flash"
	}
}

/** Role names as the wire format spells them. */
private val Role.wire: String
	get() = when (this) {
		Role.System -> "system"
		Role.User -> "user"
		Role.Assistant -> "assistant"
		Role.Tool -> "tool"
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
		parts = parts,
		plainText = content,
		reasoningText = reasoning.orEmpty(),
		finishReason = finishReason,
		error = error,
		usage = if (promptTokens != null || completionTokens != null) {
			UsageUi(promptTokens, completionTokens, cachedPromptTokens, reasoningTokens)
		} else {
			null
		},
	)
}
