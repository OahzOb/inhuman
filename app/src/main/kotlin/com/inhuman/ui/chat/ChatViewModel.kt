package com.inhuman.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.ai.provider.demo.DemoProvider
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
 * Holds the transcript in memory for now; Room persistence is the next phase.
 * The structure is already the one that survives persistence: the transcript is
 * a list of completed messages and the streaming reply is a separate in-flight
 * entry, so writing to a database later means flushing one entry rather than
 * rewriting the loop.
 */
class ChatViewModel(
	private val secure: SecureStore,
) : ViewModel() {

	private val _state = MutableStateFlow(ChatUiState())
	val state: StateFlow<ChatUiState> = _state.asStateFlow()

	/**
	 * Refresh the provider configuration from storage.
	 *
	 * Called by the chat screen every time it becomes visible, and that is not
	 * belt-and-braces: this view model is scoped to the activity so its
	 * transcript survives a trip to settings, which means `init` runs exactly
	 * once and a key added on the settings screen would otherwise not be
	 * noticed until the app was restarted. Re-reading on resume is the price of
	 * keeping the transcript alive across navigation.
	 */
	fun refreshConfiguration() {
		val demo = demoEnabled()
		val hasKey = secure.hasUsable(SecureStore.KEY_DEEPSEEK_API_KEY)
		_state.update {
			it.copy(demoMode = demo, configured = demo || hasKey)
		}
	}

	/**
	 * The model to use.
	 *
	 * DeepSeek's current model names. These are defaults only -- the settings
	 * screen replaces them with whatever `GET /models` reports, so a rename
	 * upstream is a stale default rather than a broken app.
	 */
	private var model: String = DEFAULT_MODEL

	/**
	 * Per-conversation user id.
	 *
	 * DeepSeek uses this to isolate its KV cache, scheduling and content-safety
	 * state. A stable value per conversation means one conversation's cached
	 * prefix is not shared with another, which is both a privacy and a cost
	 * property.
	 */
	private val userId: String = UUID.randomUUID().toString()

	private var streamJob: Job? = null

	init {
		refreshConfiguration()
	}

	fun onInputChange(text: String) {
		_state.update { it.copy(input = text) }
	}

	fun saveApiKey(key: String) {
		val trimmed = key.trim()
		if (trimmed.isEmpty()) {
			secure.remove(SecureStore.KEY_DEEPSEEK_API_KEY)
			_state.update { it.copy(configured = false) }
			return
		}
		secure.put(SecureStore.KEY_DEEPSEEK_API_KEY, trimmed)
		_state.update { it.copy(configured = true, error = null) }
	}

	fun currentApiKey(): String? = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY)

	/**
	 * Whether the demo source is selected instead of DeepSeek.
	 *
	 * Persisted alongside the key so the choice survives a restart: a user who
	 * switches to the demo to look at the UI should not find themselves back on
	 * a paid endpoint the next time they open the app.
	 */
	fun setDemoMode(enabled: Boolean) {
		secure.put(SecureStore.KEY_DEMO_MODE, if (enabled) "1" else "0")
		refreshConfiguration()
	}

	private fun demoEnabled(): Boolean = secure.get(SecureStore.KEY_DEMO_MODE) == "1"

	fun clearError() = _state.update { it.copy(error = null) }

	/** Abandon an in-flight reply, keeping whatever text arrived. */
	fun stop() {
		streamJob?.cancel()
		streamJob = null
		_state.update { s ->
			s.copy(
				isStreaming = false,
				messages = s.messages.map { m ->
					if (m.isStreaming) m.copy(isStreaming = false) else m
				},
			)
		}
	}

	fun send() {
		val text = _state.current().input.trim()
		if (text.isEmpty() || _state.value.isStreaming) return

		val demo = demoEnabled()
		val apiKey = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY)
		if (!demo && apiKey.isNullOrBlank()) {
			_state.update { it.copy(error = "Add a DeepSeek API key first, or switch on demo mode.") }
			return
		}

		val userMessage = ChatMessageUi(
			id = UUID.randomUUID().toString(),
			fromUser = true,
			parts = listOf(AssistantPart("t", AssistantPart.Kind.Text, text)),
			plainText = text,
		)
		val replyId = UUID.randomUUID().toString()
		val reply = ChatMessageUi(
			id = replyId,
			fromUser = false,
			isStreaming = true,
		)

		_state.update {
			it.copy(
				messages = it.messages + userMessage + reply,
				input = "",
				isStreaming = true,
				error = null,
			)
		}

		// Rebuild the wire transcript from completed messages only: the reply
		// being streamed is not sent back to the model.
		val history = _state.value.messages
			.dropLast(1)
			.filter { it.plainText.isNotBlank() }
			.map {
				ChatMessage(
					role = if (it.fromUser) Role.User else Role.Assistant,
					content = it.plainText,
				)
			}

		val provider = if (demo) DemoProvider() else DeepSeekProvider(apiKey = apiKey!!)

		streamJob = viewModelScope.launch {
			var reasoning = StringBuilder()
			var answer = StringBuilder()

			try {
				provider.stream(
					ChatRequest(
						model = model,
						messages = history,
						// Off by default for a chat app: "hi" should not cost a
						// paragraph of thinking.
						thinking = false,
						userId = userId,
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

						is StreamChunk.Usage -> updateMessage(replyId) {
							it.copy(
								usage = UsageUi(
									promptTokens = chunk.promptTokens,
									completionTokens = chunk.completionTokens,
									cachedPromptTokens = chunk.cachedPromptTokens,
									reasoningTokens = chunk.reasoningTokens,
								),
							)
						}

						is StreamChunk.Finish -> updateMessage(replyId) {
							it.copy(
								isStreaming = false,
								finishReason = chunk.reason.describe(),
							)
						}

						is StreamChunk.Failure -> {
							updateMessage(replyId) {
								it.copy(isStreaming = false, error = chunk.message)
							}
							_state.update { it.copy(error = chunk.message) }
						}

						// Region boundaries and tool calls are not rendered yet.
						// Ignoring them explicitly rather than with an else keeps
						// the compiler able to warn when a new chunk type is
						// added and left unhandled.
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
				updateMessage(replyId) { it.copy(isStreaming = false) }
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
			m.copy(parts = parts, plainText = answer)
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

	private fun MutableStateFlow<ChatUiState>.current(): ChatUiState = value

	companion object {
		/**
		 * A default, not the truth.
		 *
		 * The settings screen lists what `GET /models` actually reports; this is
		 * only what to use before that has happened.
		 */
		const val DEFAULT_MODEL = "deepseek-flash"
	}
}
