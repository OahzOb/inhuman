package com.inhuman.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape a killed process leaves behind.
 *
 * Every case here is a real active-path tail: the engine persists one assistant
 * row per model round, so "where the transcript stops" is enough to tell a
 * finished turn from an interrupted one.
 */
class InterruptedTurnTest {

	private fun user(id: String = "u") = ChatMessageUi(id = id, fromUser = true)

	private fun toolResult(id: String = "t", name: String? = null) = ChatMessageUi(
		id = id,
		fromUser = false,
		isToolResult = true,
		toolName = name,
	)

	private fun assistant(
		id: String = "a",
		finish: String? = "stop",
		error: String? = null,
		text: String = "an answer",
		calls: List<ToolCallUi> = emptyList(),
	) = ChatMessageUi(
		id = id,
		fromUser = false,
		plainText = text,
		finishReason = finish,
		error = error,
		toolCalls = calls,
	)

	@Test
	fun `an empty transcript has nothing to continue`() {
		assertFalse(isInterruptedTurn(emptyList(), isStreaming = false))
	}

	@Test
	fun `a live turn is not interrupted`() {
		assertFalse(isInterruptedTurn(listOf(user()), isStreaming = true))
	}

	@Test
	fun `a user message with no answer is interrupted`() {
		assertTrue(isInterruptedTurn(listOf(user()), isStreaming = false))
	}

	@Test
	fun `a tool result with no following answer is interrupted`() {
		assertTrue(
			isInterruptedTurn(listOf(user(), assistant(), toolResult()), isStreaming = false),
		)
	}

	@Test
	fun `a terminal suggestion result is a finished turn`() {
		// `suggest_replies` ends its turn by design: the engine stops after the
		// result, so this tail is where a completed turn ends, not half a round.
		// Offering Continue here would ask the model to answer suggestions that
		// were already the answer.
		assertFalse(
			isInterruptedTurn(
				listOf(
					user(),
					assistant(finish = "tool_calls"),
					toolResult(name = SUGGEST_TOOL_NAME),
				),
				isStreaming = false,
			),
		)
	}

	@Test
	fun `a repaired suggestion result is still interrupted`() {
		// The process died between the call and its result; the repair wrote
		// `[interrupted]`. Treating that as the terminal shape would hide
		// Continue for a turn that never finished.
		assertTrue(
			isInterruptedTurn(
				listOf(
					user(),
					assistant(finish = "tool_calls"),
					toolResult(name = SUGGEST_TOOL_NAME).copy(
						plainText = "${ChatViewModel.INTERRUPTED_MARKER} The app stopped before this call finished.",
					),
				),
				isStreaming = false,
			),
		)
	}

	@Test
	fun `a tool result after a stopped answer is not interrupted`() {
		// Stopping after a tool call leaves the assistant error on the row that
		// requested it. The tail is a tool result either way, but the error
		// banner already offers Retry; Continue would be a second retry path.
		assertFalse(
			isInterruptedTurn(
				listOf(
					user(),
					assistant(
						finish = null,
						error = "Stopped before the reply finished.",
					),
					toolResult(),
				),
				isStreaming = false,
			),
		)
	}

	@Test
	fun `a finished answer is not interrupted`() {
		assertFalse(
			isInterruptedTurn(listOf(user(), assistant(finish = "stop")), isStreaming = false),
		)
	}

	@Test
	fun `a failed answer is not interrupted`() {
		// The error banner owns Retry; Continue would be a second retry path
		// with different tool semantics.
		assertFalse(
			isInterruptedTurn(
				listOf(user(), assistant(finish = null, error = "HTTP 500")),
				isStreaming = false,
			),
		)
	}

	@Test
	fun `a stopped answer is not interrupted`() {
		assertFalse(
			isInterruptedTurn(
				listOf(user(), assistant(finish = null, error = "Stopped before the reply finished.")),
				isStreaming = false,
			),
		)
	}

	@Test
	fun `an assistant tail with tool calls is interrupted`() {
		val call = ToolCallUi(
			index = 0,
			id = "call-1",
			name = "read_file",
			argumentsJson = "{}",
			state = ToolCallUi.State.Ready,
		)
		assertTrue(
			isInterruptedTurn(
				listOf(user(), assistant(finish = "tool_calls", calls = listOf(call))),
				isStreaming = false,
			),
		)
	}

	@Test
	fun `an assistant row without a finish reason is interrupted`() {
		assertTrue(
			isInterruptedTurn(
				listOf(user(), assistant(finish = null, text = "half an ans")),
				isStreaming = false,
			),
		)
	}
}