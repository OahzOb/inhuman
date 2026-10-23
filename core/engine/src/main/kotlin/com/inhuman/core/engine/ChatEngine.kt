package com.inhuman.core.engine

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolCall
import com.inhuman.core.ai.ToolSpec
import com.inhuman.core.ai.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * One turn, independent of Android and of any UI.
 *
 * This is the first extraction of the loop that lives inside `ChatViewModel`:
 * it streams a completion, assembles tool calls, asks an approval hook before
 * executing one, appends the assistant/tool messages, and repeats until the
 * model answers without a call or a budget is hit.
 *
 * What it deliberately does **not** own yet: persistence, snapshots, context
 * compaction, SSH consent policy, and the UI's per-token throttling. Those are
 * the next slices; keeping them out is what lets this module be tested on a
 * plain JVM. In particular, the approval hook is a callback rather than a
 * `needsApproval` check here, because the policy layer is not part of the
 * protocol.
 */
class ChatEngine(
	private val provider: ChatProvider,
	private val approval: EngineApproval = EngineApproval { _, _ -> EngineDecision.Allow },
	private val toolDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

	fun run(turn: EngineTurn): Flow<EngineEvent> = flow {
		val history = turn.messages.toMutableList()
		var callsUsed = 0

		for (round in 1..turn.maxRounds) {
			val text = StringBuilder()
			val reasoning = StringBuilder()
			val builders = mutableMapOf<Int, CallBuilder>()
			var finish: FinishReason? = null
			var failure: String? = null
			var usage: EngineUsage? = null

			val request = ChatRequest(
				model = turn.model,
				messages = history,
				tools = turn.tools.map(::spec),
				thinking = turn.thinking,
				reasoningEffort = turn.reasoningEffort,
				userId = turn.userId,
			)
			emit(EngineEvent.RoundStarted(round, request))

			try {
				provider.stream(request).collect { chunk ->
					when (chunk) {
						is StreamChunk.ReasoningDelta -> {
							reasoning.append(chunk.text)
							emit(EngineEvent.ReasoningDelta(chunk.text))
						}

						is StreamChunk.TextDelta -> {
							text.append(chunk.text)
							emit(EngineEvent.TextDelta(chunk.text))
						}

						is StreamChunk.ToolCallStart -> {
							builders.getOrPut(chunk.index) { CallBuilder(chunk.index) }.apply {
								id = chunk.id ?: id
								if (chunk.name != null) name.append(chunk.name)
							}
						}

						is StreamChunk.ToolCallDelta -> {
							builders.getOrPut(chunk.index) { CallBuilder(chunk.index) }.apply {
								chunk.nameDelta?.let(name::append)
								chunk.inputDelta?.let(arguments::append)
							}
						}

						is StreamChunk.ToolCallEnd -> Unit

						is StreamChunk.Usage -> {
							usage = EngineUsage(
								promptTokens = chunk.promptTokens,
								completionTokens = chunk.completionTokens,
								totalTokens = chunk.totalTokens,
								cachedPromptTokens = chunk.cachedPromptTokens,
								reasoningTokens = chunk.reasoningTokens,
							)
						}

						is StreamChunk.Finish -> finish = chunk.reason

						is StreamChunk.Failure -> {
							failure = chunk.message
						}

						is StreamChunk.TextStart,
						is StreamChunk.TextEnd,
						is StreamChunk.ReasoningStart,
						is StreamChunk.ReasoningEnd,
						is StreamChunk.Raw,
						-> Unit
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// The contract says failures arrive as a chunk, but a transport
				// can still throw before the first chunk. Do not turn that into a
				// torn coroutine with no event for the caller.
				failure = e.message ?: e::class.simpleName ?: "provider stream failed"
			}

			failure?.let {
				emit(EngineEvent.Failed(it))
				return@flow
			}

			val calls = builders.values
				.filter { it.name.isNotBlank() }
				.sortedBy { it.index }
				.map { it.toCall(round) }

			val assistant = ChatMessage(
				role = Role.Assistant,
				content = text.toString(),
				reasoning = reasoning.toString().ifBlank { null },
				toolCalls = calls.map { ToolCall(it.id, it.name, it.argumentsJson) },
			)
			history += assistant
			emit(EngineEvent.AssistantMessage(assistant))
			usage?.let { emit(EngineEvent.Usage(it)) }
			emit(
				EngineEvent.RoundFinished(
					round = round,
					reason = finish ?: FinishReason.Stop,
					usage = usage,
				),
			)

			if (calls.isEmpty()) {
				emit(EngineEvent.Finished(finish ?: FinishReason.Stop))
				return@flow
			}

			if (turn.suppressToolCalls) {
				emit(EngineEvent.Failed("provider called a tool despite the text-only retry instruction"))
				return@flow
			}

			for ((index, call) in calls.withIndex()) {
				if (callsUsed >= turn.maxCalls) {
					for (skip in calls.drop(index)) {
						val result = ToolResult("not executed: tool call limit reached", isError = true)
						history += ChatMessage(Role.Tool, result.content, toolCallId = skip.id)
						emit(EngineEvent.ToolCallSkipped(skip, result.content))
					}
					emit(EngineEvent.Failed("tool call limit reached (${turn.maxCalls})"))
					return@flow
				}
				callsUsed++
				emit(EngineEvent.ToolCallReady(call))

				val tool = turn.tools.firstOrNull { it.name == call.name }
				val executed = execute(tool, call)
				history += ChatMessage(
					role = Role.Tool,
					content = executed.result.content,
					toolCallId = call.id,
				)
				emit(EngineEvent.ToolCallResult(call, executed.result))

				if (executed.stopTurn) {
					// Every assistant tool_call needs a result on the wire. If the
					// turn stops after the first refusal, answer the rest here
					// rather than leaving the next request with unanswered calls.
					for (skip in calls.drop(index + 1)) {
						val result = ToolResult("not executed: turn stopped", isError = true)
						history += ChatMessage(Role.Tool, result.content, toolCallId = skip.id)
						emit(EngineEvent.ToolCallSkipped(skip, result.content))
					}
					emit(EngineEvent.Finished(FinishReason.Stop))
					return@flow
				}
			}
		}

		emit(EngineEvent.Failed("tool round limit reached (${turn.maxRounds})"))
	}

	private suspend fun execute(tool: Tool?, call: EngineToolCall): Execution {
		if (tool == null) {
			return Execution(ToolResult("unknown tool: ${call.name}", isError = true), stopTurn = true)
		}

		val decision = try {
			approval.approve(tool, call)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			return Execution(
				ToolResult("approval failed: ${e.message}", isError = true),
				stopTurn = true,
			)
		}
		if (decision is EngineDecision.Deny) {
			return Execution(
				ToolResult(decision.message, isError = true),
				stopTurn = decision.stopTurn,
			)
		}

		val arguments = try {
			Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject
		} catch (e: Exception) {
			return Execution(
				ToolResult("invalid tool arguments: ${e.message}", isError = true),
				stopTurn = false,
			)
		}
		return try {
			Execution(withContext(toolDispatcher) { tool.execute(arguments) }, stopTurn = false)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Execution(ToolResult("tool failed: ${e.message}", isError = true), stopTurn = false)
		}
	}

	private fun spec(tool: Tool) = ToolSpec(
		name = tool.name,
		description = tool.description,
		parameters = tool.parameters,
	)
}

/** A turn request, with every value needed by the loop. */
data class EngineTurn(
	val model: String,
	val messages: List<ChatMessage>,
	val tools: List<Tool> = emptyList(),
	val maxRounds: Int = 8,
	val maxCalls: Int = 24,
	val thinking: Boolean? = null,
	val reasoningEffort: String? = null,
	val userId: String? = null,
	val suppressToolCalls: Boolean = false,
)

data class EngineToolCall(
	val index: Int,
	val id: String,
	val name: String,
	val argumentsJson: String,
)

data class EngineUsage(
	val promptTokens: Int? = null,
	val completionTokens: Int? = null,
	val totalTokens: Int? = null,
	val cachedPromptTokens: Int? = null,
	val reasoningTokens: Int? = null,
)

sealed interface EngineEvent {
	data class RoundStarted(val round: Int, val request: ChatRequest) : EngineEvent
	data class ReasoningDelta(val text: String) : EngineEvent
	data class TextDelta(val text: String) : EngineEvent
	data class ToolCallReady(val call: EngineToolCall) : EngineEvent
	data class ToolCallResult(val call: EngineToolCall, val result: ToolResult) : EngineEvent
	data class ToolCallSkipped(val call: EngineToolCall, val reason: String) : EngineEvent
	data class AssistantMessage(val message: ChatMessage) : EngineEvent
	data class Usage(val usage: EngineUsage) : EngineEvent
	data class RoundFinished(
		val round: Int,
		val reason: FinishReason,
		val usage: EngineUsage?,
	) : EngineEvent
	data class Finished(val reason: FinishReason) : EngineEvent
	data class Failed(val message: String) : EngineEvent
}

sealed interface EngineDecision {
	data object Allow : EngineDecision
	data class Deny(
		val message: String,
		/** Whether this refusal ends the turn or only this call. */
		val stopTurn: Boolean = true,
	) : EngineDecision
}

fun interface EngineApproval {
	suspend fun approve(tool: Tool, call: EngineToolCall): EngineDecision
}

private data class Execution(
	val result: ToolResult,
	val stopTurn: Boolean,
)

private class CallBuilder(val index: Int) {
	var id: String? = null
	val name = StringBuilder()
	val arguments = StringBuilder()

	fun toCall(round: Int) = EngineToolCall(
		index = index,
		id = id ?: "call-$round-$index",
		name = name.toString(),
		argumentsJson = arguments.toString(),
	)
}
