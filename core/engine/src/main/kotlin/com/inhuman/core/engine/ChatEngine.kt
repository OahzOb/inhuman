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
 * The loop that used to live inside `ChatViewModel`: it streams a completion,
 * assembles tool calls, asks an approval hook before executing one, appends the
 * assistant/tool messages, and repeats until the model answers without a call or
 * a budget is hit. Both hosts -- the Android app and the desktop window -- run
 * this one, so "what happened when the user declined" has one answer rather than
 * one per platform.
 *
 * What it deliberately does **not** own: persistence, snapshots, context
 * compaction, SSH consent policy, and the UI's per-token throttling. Those are
 * host concerns, and keeping them out is what lets this module be tested on a
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

			var reasoningSignature: String? = null
			val providerStates = mutableListOf<String>()

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
								itemId = chunk.itemId ?: itemId
								if (chunk.name != null) name.append(chunk.name)
							}
						}

						is StreamChunk.ToolCallDelta -> {
							builders.getOrPut(chunk.index) { CallBuilder(chunk.index) }.apply {
								chunk.nameDelta?.let(name::append)
								chunk.inputDelta?.let(arguments::append)
							}
						}

						is StreamChunk.ReasoningSignature -> reasoningSignature = chunk.signature

						is StreamChunk.ToolCallSignature -> {
							builders.getOrPut(chunk.index) { CallBuilder(chunk.index) }
								.signature = chunk.signature
						}

						is StreamChunk.ProviderState -> providerStates += chunk.json

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
				reasoningSignature = reasoningSignature,
				providerState = providerStates.takeIf { it.isNotEmpty() }
					?.joinToString(prefix = "[", separator = ",", postfix = "]"),
				toolCalls = calls.map {
					ToolCall(
						it.id,
						it.name,
						it.argumentsJson,
						it.thoughtSignature,
						it.providerItemId,
					)
				},
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
				emit(
					EngineEvent.Failed(
						"provider called a tool despite the text-only retry instruction",
						EngineFailure.ToolCallsSuppressed,
					),
				)
				return@flow
			}

			for ((index, call) in calls.withIndex()) {
				if (callsUsed >= turn.maxCalls) {
					for (skip in calls.drop(index)) {
						val result = ToolResult("not executed: tool call limit reached", isError = true)
						history += ChatMessage(Role.Tool, result.content, toolCallId = skip.id)
						emit(EngineEvent.ToolCallSkipped(skip, result.content))
					}
					emit(
						EngineEvent.Failed(
							"tool call limit reached (${turn.maxCalls})",
							EngineFailure.CallLimit,
						),
					)
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
				if (executed.declined) {
					// A refusal by the user is not a tool failure. The chip and
					// the transcript already say "declined", and collapsing the
					// two made a call the user stopped look like one that ran and
					// broke -- the state a person is most likely to misread.
					emit(EngineEvent.ToolCallDeclined(call, executed.result.content))
				} else {
					emit(EngineEvent.ToolCallResult(call, executed.result))
				}

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

		emit(EngineEvent.Failed("tool round limit reached (${turn.maxRounds})", EngineFailure.RoundLimit))
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
				declined = decision.declined,
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
			// Normalised by the tool, before it runs. Some providers wrap the
			// whole argument object in a string field; a tool that accepts that
			// shape declares it in `normalize`, and the same value is what the
			// approval gate saw.
			Execution(
				withContext(toolDispatcher) { tool.execute(tool.normalize(arguments)) },
				stopTurn = false,
			)
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
	val thoughtSignature: String? = null,
	val providerItemId: String? = null,
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
	/**
	 * The turn ended for a reason the host has to name.
	 *
	 * The message stays for logs and for a host that has no translation, but
	 * the cause is what lets an Android UI show its own sentence. Before this,
	 * routing a real provider through the engine replaced the localised
	 * "tool call limit reached" with the English string the loop happened to
	 * hold -- a regression that only shows on the user's screen.
	 */
	data class Failed(
		val message: String,
		val cause: EngineFailure = EngineFailure.Provider,
	) : EngineEvent

	/**
	 * The user refused this call.
	 *
	 * Separate from [ToolCallResult] because it is not a failure: the call did
	 * not run, and the transcript, the chip and the model all need to say that
	 * rather than "the tool broke".
	 */
	data class ToolCallDeclined(val call: EngineToolCall, val message: String) : EngineEvent
}

/** Why a turn ended early, in terms its host can translate. */
enum class EngineFailure {
	/** The provider or the transport failed. [EngineEvent.Failed.message] says how. */
	Provider,

	/** A text-only retry produced a tool call despite the instruction not to. */
	ToolCallsSuppressed,

	/** The per-turn tool round budget ran out. */
	RoundLimit,

	/** The per-turn tool call budget ran out. */
	CallLimit,
}

sealed interface EngineDecision {
	data object Allow : EngineDecision
	data class Deny(
		val message: String,
		/** Whether this refusal ends the turn or only this call. */
		val stopTurn: Boolean = true,
		/**
		 * Whether the user said no, rather than policy refusing.
		 *
		 * A user's refusal ends the turn and must read as "declined"; a policy
		 * refusal is a failed call the model may work around. The engine only
		 * reports the difference -- naming it here keeps the host from having to
		 * match on the refusal's text.
		 */
		val declined: Boolean = false,
	) : EngineDecision
}

fun interface EngineApproval {
	suspend fun approve(tool: Tool, call: EngineToolCall): EngineDecision
}

private data class Execution(
	val result: ToolResult,
	val stopTurn: Boolean,
	/** The user declined, as opposed to the call failing. */
	val declined: Boolean = false,
)

private class CallBuilder(val index: Int) {
	var id: String? = null
	val name = StringBuilder()
	val arguments = StringBuilder()
	var signature: String? = null
	var itemId: String? = null

	fun toCall(round: Int) = EngineToolCall(
		index = index,
		id = id ?: "call-$round-$index",
		name = name.toString(),
		argumentsJson = arguments.toString(),
		thoughtSignature = signature,
		providerItemId = itemId,
	)
}
