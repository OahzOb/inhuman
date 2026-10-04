package com.inhuman.core.ai.provider.demo

import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.ReasoningType
import com.inhuman.core.ai.StreamChunk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A provider that answers from a script.
 *
 * ## Why this ships rather than living in a test
 *
 * The streaming path -- provider chunks into view-model state into Compose into
 * a rendered bubble -- is the part of this app most likely to break, and it is
 * also the part an API key gate keeps out of reach during development. A
 * fixture-driven provider makes the whole path exercisable without a key, a
 * network, or a bill.
 *
 * It is also the honest way to see the UI work: a screenshot of a real reply is
 * evidence about the network, while a screenshot of this is evidence about the
 * app. Both matter, and only one of them is available on demand.
 *
 * The script deliberately includes a reasoning region, a mid-answer pause, and
 * a tool-call-shaped finish, because those are the branches a plain text reply
 * would never exercise.
 */
class DemoProvider(
	private val chunkDelayMillis: Long = 22,
) : ChatProvider {

	override val id: String = "demo"

	override suspend fun listModels(): List<RemoteModel> = listOf(
		RemoteModel(
			id = "demo-model",
			displayName = "Demo model (no network)",
			contextWindow = 64_000,
			maxOutputTokens = 4_096,
			inputModalities = listOf("text", "image"),
			supportedEffortLevels = listOf("low", "high"),
			defaultEffortLevel = "high",
		),
	)

	override fun stream(request: ChatRequest): Flow<StreamChunk> = flow {
		val isToolTurn = request.tools.isNotEmpty()

		// Reasoning first. Real reasoning models emit this before any visible
		// text, and the UI has to place it above the answer rather than
		// interleaved with it.
		emit(StreamChunk.ReasoningStart(type = ReasoningType.Summary))
		for (piece in REASONING.split(" ")) {
			emit(StreamChunk.ReasoningDelta("$piece "))
			delay(chunkDelayMillis)
		}
		emit(StreamChunk.ReasoningEnd())

		// A pause where a real model would be thinking before its first token.
		delay(180)

		emit(StreamChunk.TextStart())
		val answer = if (isToolTurn) TOOL_ANSWER else TEXT_ANSWER
		for (piece in answer.split(" ")) {
			emit(StreamChunk.TextDelta("$piece "))
			delay(chunkDelayMillis)
		}
		emit(StreamChunk.TextEnd())

		emit(
			StreamChunk.Usage(
				promptTokens = 128,
				completionTokens = answer.length / 4,
				totalTokens = 128 + answer.length / 4,
				cachedPromptTokens = 96,
				reasoningTokens = REASONING.length / 4,
			),
		)

		emit(
			StreamChunk.Finish(
				reason = com.inhuman.core.ai.FinishReason.Stop,
				responseId = "demo-${System.currentTimeMillis()}",
				model = "demo-model",
			),
		)
	}

	private companion object {
		const val REASONING =
			"This is a demo reply, so there is no model behind it. " +
				"The text below is emitted one word at a time through the same " +
				"streaming path a real provider uses."

		const val TEXT_ANSWER =
			"Hello. You are reading a simulated stream: the words above arrived " +
				"as individual chunks, were accumulated by the view model, and were " +
				"rendered as they came. The dimmed block at the top is a reasoning " +
				"region, kept separate from the answer rather than merged into it. " +
				"Add a DeepSeek key in settings to talk to a real model."

		const val TOOL_ANSWER =
			"Given a tool list, a real model would call one here. " +
				"This demo does not execute tools yet."
	}
}
