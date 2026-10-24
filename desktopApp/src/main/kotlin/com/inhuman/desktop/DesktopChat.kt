package com.inhuman.desktop

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.ToolCall
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.tools.TimeTool
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.StoredToolCall
import com.inhuman.core.data.db.ToolCallsColumn
import com.inhuman.core.engine.ChatEngine
import com.inhuman.core.engine.EngineDecision
import com.inhuman.core.engine.EngineEvent
import com.inhuman.core.engine.EngineTurn
import com.inhuman.core.engine.EngineUsage
import com.inhuman.core.files.tools.FileTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * One turn of the desktop chat, with tools, against the persisted conversation.
 *
 * The window used to build the request from the lines it was drawing, which was
 * fine while a turn was one text answer and is wrong the moment a tool runs:
 * the assistant `tool_calls` row and its tool results are separate rows in the
 * transcript, and the next request has to carry both in the order the provider
 * expects. This class builds the wire history from the database, lets
 * `ChatEngine` drive the loop, and writes every round and every tool result back
 * before the next round starts.
 *
 * It is deliberately UI-free: the window receives [Event]s and owns rendering,
 * while the test can drive the same class with `DemoProvider` and a temporary
 * data directory.
 */
class DesktopChat(
	private val store: DesktopStore,
	private val conversationId: String,
	private val assistant: AgentEntity?,
) {

	sealed interface Event {
		data class UserMessage(val line: StoredLine) : Event
		data class AssistantStarted(val id: String) : Event
		data class AssistantDelta(val text: String, val reasoning: Boolean) : Event
		data class AssistantFinished(val line: StoredLine) : Event
		data class ToolResult(val line: StoredLine) : Event
		data class Error(val message: String) : Event
	}

	/**
	 * Run one turn and return the error, or null on a clean finish.
	 *
	 * The caller is expected to run this on the UI dispatcher: `onEvent` touches
	 * Compose state, and every database call resumes on the caller's context.
	 */
	suspend fun send(
		userText: String,
		provider: ChatProvider,
		model: String,
		onEvent: (Event) -> Unit,
	): String? {
		val conversation = store.conversation(conversationId) ?: return "Conversation not found."
		val entity = conversation.entity
		val parentId = store.lastMessageId(conversationId)
		val firstMessage = parentId == null

		val userLine = store.appendUser(conversationId, userText, parentId)
		onEvent(Event.UserMessage(userLine))
		if (firstMessage) {
			store.autoTitle(conversationId, userText)
		}

		val workspace = runCatching { store.workspace(entity) }.getOrElse { failure ->
			val message = "Workspace could not be opened: ${failure.message}"
			onEvent(Event.Error(message))
			return message
		}
		val selected = entity.tools ?: assistant?.tools
		val tools = (FileTools.all(workspace) + TimeTool())
			.filter { ToolCatalog.permits(selected, it.name) }
		val policies = store.policies(conversationId, entity.agentId)

		var lastId: String? = userLine.id
		var current: Round? = null
		var error: String? = null
		var cancelled = false

		val engine = ChatEngine(
			provider = provider,
			approval = { tool, call ->
				val parsed = runCatching {
					Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject
				}.getOrNull()
				val policy = policies.effective(tool.name)
				when {
					parsed == null -> EngineDecision.Deny(
						"$REFUSED invalid tool arguments",
						stopTurn = false,
					)

					policy == ToolPolicy.Deny || policy == ToolPolicy.Blind ->
						EngineDecision.Deny(
							"$REFUSED This tool or path is not available in this conversation.",
							stopTurn = false,
						)

					// The desktop settings sheet can configure Ask; it does not
					// yet own an approval dialogue. Refusing is the safe half of
					// that feature: the alternative is running a call the user
					// asked to be consulted about.
					policy == ToolPolicy.Ask || tool.needsApproval(parsed) ->
						EngineDecision.Deny(
							"$REFUSED This call requires approval, which is not available on the desktop yet.",
							stopTurn = false,
						)

					else -> EngineDecision.Allow
				}
			},
		)

		try {
			engine.run(
				EngineTurn(
					model = model,
					messages = history(),
					tools = tools,
					maxRounds = DEFAULT_TOOL_ROUNDS,
					maxCalls = DEFAULT_TOOL_CALLS,
					thinking = assistant?.thinking ?: false,
					reasoningEffort = assistant?.reasoningEffort,
					userId = conversationId,
				),
			).collect { event ->
				when (event) {
					is EngineEvent.RoundStarted -> {
						val round = Round(
							id = UUID.randomUUID().toString(),
							parentId = lastId,
						)
						current = round
						onEvent(Event.AssistantStarted(round.id))
					}

					is EngineEvent.ReasoningDelta -> {
						current?.reasoning?.append(event.text)
						onEvent(Event.AssistantDelta(event.text, reasoning = true))
					}

					is EngineEvent.TextDelta -> {
						current?.text?.append(event.text)
						onEvent(Event.AssistantDelta(event.text, reasoning = false))
					}

					is EngineEvent.AssistantMessage -> {
						val round = current ?: return@collect
						event.message.toolCalls.forEachIndexed { index, call ->
							round.calls[index] = StoredToolCall(
								index = index,
								id = call.id,
								name = call.name,
								argumentsJson = call.argumentsJson,
							)
						}
					}

					is EngineEvent.Usage -> current?.usage = event.usage

					is EngineEvent.ToolCallResult -> {
						val line = store.appendTool(
							conversationId = conversationId,
							toolCallId = event.call.id,
							toolName = event.call.name,
							content = event.result.content,
							isError = event.result.isError,
							parentId = lastId,
						)
						lastId = line.id
						onEvent(Event.ToolResult(line))
					}

					is EngineEvent.ToolCallSkipped -> {
						val line = store.appendTool(
							conversationId = conversationId,
							toolCallId = event.call.id,
							toolName = event.call.name,
							content = "$SKIPPED ${event.reason}",
							isError = true,
							parentId = lastId,
						)
						lastId = line.id
						onEvent(Event.ToolResult(line))
					}

					is EngineEvent.ToolCallDeclined -> {
						val line = store.appendTool(
							conversationId = conversationId,
							toolCallId = event.call.id,
							toolName = event.call.name,
							content = event.message,
							isError = true,
							parentId = lastId,
						)
						lastId = line.id
						onEvent(Event.ToolResult(line))
					}

					is EngineEvent.RoundFinished -> {
						val round = current ?: return@collect
						val line = round.persist(finishReason = event.reason.name)
						if (line != null) {
							lastId = line.id
							onEvent(Event.AssistantFinished(line))
						}
						current = null
					}

					is EngineEvent.Failed -> {
						error = event.message
						onEvent(Event.Error(event.message))
					}

					is EngineEvent.Finished -> Unit
					is EngineEvent.ToolCallReady -> Unit
				}
			}
		} catch (cancelledTurn: CancellationException) {
			cancelled = true
			throw cancelledTurn
		} catch (failure: Exception) {
			error = failure.message ?: failure::class.simpleName
			onEvent(Event.Error(error!!))
		} finally {
			val round = current
			if (round != null && !round.persisted) {
				withContext(NonCancellable) {
					val note = if (cancelled) "Stopped before the reply finished." else error
					val line = round.persist(error = note)
					if (line != null) onEvent(Event.AssistantFinished(line))
				}
			}
		}
		return error
	}

	/**
	 * The transcript as the provider needs it.
	 *
	 * A dangling assistant `tool_calls` row is fatal to every later request in
	 * the conversation: the API rejects it with HTTP 400 and the conversation
	 * cannot continue. A process death while a call was running is enough to
	 * produce one, so missing results are closed here rather than left for the
	 * next turn to discover.
	 */
	private suspend fun history(): List<ChatMessage> {
		val out = mutableListOf<ChatMessage>()
		assistant?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
			out += ChatMessage(Role.System, it)
		}
		val messages = store.messages(conversationId)
		var index = 0
		while (index < messages.size) {
			val message = messages[index]
			when (message.role) {
				"user" -> out += ChatMessage(Role.User, message.content)
				"assistant" -> {
					val calls = ToolCallsColumn.decode(message.toolCallsJson)
						.mapNotNull { call ->
							val id = call.id ?: return@mapNotNull null
							ToolCall(id, call.name.orEmpty(), call.argumentsJson)
						}
					out += ChatMessage(
						role = Role.Assistant,
						content = message.content,
						reasoning = message.reasoning?.takeIf { it.isNotBlank() },
						toolCalls = calls,
					)
					if (calls.isNotEmpty()) {
						val answered = mutableSetOf<String>()
						var next = index + 1
						while (next < messages.size && messages[next].role == "tool") {
							messages[next].toolCallId?.let(answered::add)
							next++
						}
						for (missing in calls.map { it.id } - answered) {
							out += ChatMessage(
								role = Role.Tool,
								content = "[interrupted] The app stopped before this call finished; it was not run.",
								toolCallId = missing,
							)
						}
						index = next
						continue
					}
				}

				"tool" -> out += ChatMessage(
					role = Role.Tool,
					content = message.content,
					toolCallId = message.toolCallId,
				)
			}
			index++
		}
		return out
	}

	private inner class Round(
		val id: String,
		val parentId: String?,
	) {
		val text = StringBuilder()
		val reasoning = StringBuilder()
		val calls = LinkedHashMap<Int, StoredToolCall>()
		var usage: EngineUsage? = null
		var persisted = false

		suspend fun persist(error: String? = null, finishReason: String? = null): StoredLine? {
			if (persisted) return null
			val hasPayload = text.isNotEmpty() || reasoning.isNotEmpty() || calls.isNotEmpty()
			if (!hasPayload && error == null) return null
			persisted = true
			return store.appendAssistant(
				conversationId = conversationId,
				text = text.toString(),
				reasoning = reasoning.toString(),
				parentId = parentId,
				toolCalls = calls.values.toList(),
				error = error,
				finishReason = finishReason,
				promptTokens = usage?.promptTokens,
				completionTokens = usage?.completionTokens,
				cachedPromptTokens = usage?.cachedPromptTokens,
				reasoningTokens = usage?.reasoningTokens,
			)
		}
	}

	private companion object {
		const val DEFAULT_TOOL_ROUNDS = 8
		const val DEFAULT_TOOL_CALLS = 32
		const val REFUSED = "[refused]"
		const val SKIPPED = "[skipped]"
	}
}
