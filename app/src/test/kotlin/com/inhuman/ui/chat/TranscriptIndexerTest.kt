package com.inhuman.ui.chat

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The incremental transcript index must equal a full pass, on every edit shape.
 *
 * B11 rejected a cheap memo because it cannot see an older row being rewritten;
 * this indexer avoids that by comparing rows by reference and falling back to a
 * full pass for every shape it does not positively recognise. The tests below
 * are the proof obligation that design creates: a deterministic case per shape,
 * then randomised edit sequences compared against `turnSummariesOf` /
 * `userTurnsOf` / `isInterruptedTurn` after **every** step -- including the
 * per-publish tail update, an older row rewritten in place, a rebuilt list and a
 * shrinking transcript.
 */
class TranscriptIndexerTest {

	private var counter = 0

	private fun user(id: String? = null) = ChatMessageUi(
		id = id ?: "u${counter++}",
		fromUser = true,
		plainText = "question",
		finishReason = null,
	)

	private fun injected() = ChatMessageUi(
		id = "i${counter++}",
		fromUser = true,
		injected = true,
		plainText = "inserted",
		finishReason = null,
	)

	private fun assistant(
		id: String? = null,
		streaming: Boolean = false,
		usage: UsageUi? = null,
		error: String? = null,
		calls: List<ToolCallUi> = emptyList(),
	) = ChatMessageUi(
		id = id ?: "a${counter++}",
		fromUser = false,
		isStreaming = streaming,
		usage = usage,
		error = error,
		toolCalls = calls,
		plainText = "answer",
		// A settled answer carries a finish reason; a placeholder that never
		// finished is what `isInterruptedTurn` reports.
		finishReason = if (streaming || error != null || calls.isNotEmpty()) null else "stop",
	)

	private fun toolResult(name: String = "read_file") = ChatMessageUi(
		id = "t${counter++}",
		fromUser = false,
		isToolResult = true,
		toolName = name,
		plainText = "ok",
	)

	private fun full(messages: List<ChatMessageUi>, streaming: Boolean) = TranscriptIndex(
		turns = turnSummariesOf(messages),
		userTurns = userTurnsOf(messages),
		interrupted = isInterruptedTurn(messages, streaming),
	)

	private fun assertMatches(
		indexer: TranscriptIndexer,
		messages: List<ChatMessageUi>,
		streaming: Boolean,
		label: String,
	) {
		val expected = full(messages, streaming)
		val actual = indexer.update(messages, streaming)
		assertEquals("$label: turns", expected.turns, actual.turns)
		// Map equality ignores order; the footer placement depends on iteration
		// order, so assert it separately.
		assertEquals("$label: turn order", expected.turns.keys.toList(), actual.turns.keys.toList())
		assertEquals("$label: user turns", expected.userTurns, actual.userTurns)
		assertEquals("$label: interrupted", expected.interrupted, actual.interrupted)
	}

	@Test
	fun `a streaming tail update patches only the open turn`() {
		val indexer = TranscriptIndexer()
		var messages = listOf(user(), assistant("a1", streaming = true))
		assertMatches(indexer, messages, true, "start")
		// Every publish replaces the last row with a new instance.
		repeat(20) { tick ->
			messages = messages.dropLast(1) + messages.last().copy(
				plainText = "answer ${"x".repeat(tick)}",
				usage = UsageUi(completionTokens = tick, promptTokens = 10),
			)
			assertMatches(indexer, messages, true, "tick $tick")
		}
	}

	@Test
	fun `appending an assistant row extends the open turn`() {
		val indexer = TranscriptIndexer()
		var messages = listOf(user())
		assertMatches(indexer, messages, false, "user only")
		messages = messages + assistant("a1", streaming = true)
		assertMatches(indexer, messages, true, "placeholder")
		messages = messages + toolResult()
		assertMatches(indexer, messages, true, "tool result")
		messages = messages + assistant("a2")
		assertMatches(indexer, messages, false, "second round")
	}

	@Test
	fun `a new turn root closes the previous turn and joins the jump list`() {
		val indexer = TranscriptIndexer()
		var messages = listOf(user(), assistant("a1"), toolResult())
		assertMatches(indexer, messages, false, "first turn")
		messages = messages + user("u2")
		assertMatches(indexer, messages, false, "second question")
		messages = messages + assistant("a2")
		assertMatches(indexer, messages, false, "second answer")
	}

	@Test
	fun `an inserted user row is not a turn root`() {
		val indexer = TranscriptIndexer()
		var messages = listOf(user(), assistant("a1", streaming = true))
		assertMatches(indexer, messages, true, "streaming")
		messages = messages + injected()
		assertMatches(indexer, messages, true, "insertion")
		messages = messages + assistant("a2")
		assertMatches(indexer, messages, false, "after insertion")
	}

	@Test
	fun `rewriting an older row falls back to a full pass`() {
		val indexer = TranscriptIndexer()
		val first = user("u1")
		val oldAnswer = assistant("a1", usage = UsageUi(completionTokens = 1))
		var messages = listOf(first, oldAnswer, user("u2"), assistant("a2"))
		assertMatches(indexer, messages, false, "before")
		// A rewind/reload rewrites the historical row's usage in place.
		messages = messages.toMutableList().also { it[1] = it[1].copy(usage = UsageUi(completionTokens = 999)) }
		assertMatches(indexer, messages, false, "after rewrite")
	}

	@Test
	fun `a rebuilt list of equal rows is still correct`() {
		val indexer = TranscriptIndexer()
		var messages = listOf(user("u1"), assistant("a1"))
		assertMatches(indexer, messages, false, "first")
		messages = messages.map { it.copy() }
		assertMatches(indexer, messages, false, "rebuilt")
	}

	@Test
	fun `a shrinking transcript falls back instead of keeping stale turns`() {
		val indexer = TranscriptIndexer()
		var messages = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
		assertMatches(indexer, messages, false, "full")
		messages = messages.dropLast(2)
		assertMatches(indexer, messages, false, "rewound")
		messages = messages.dropLast(1)
		assertMatches(indexer, messages, false, "user tail")
	}

	@Test
	fun `the streaming flag alone changes only the interrupted answer`() {
		val indexer = TranscriptIndexer()
		val messages = listOf(user("u1"), assistant("a1"))
		assertMatches(indexer, messages, false, "settled")
		assertMatches(indexer, messages, true, "streaming")
		assertMatches(indexer, messages, false, "settled again")
	}

	@Test
	fun `random edit sequences always equal a full pass`() {
		val indexer = TranscriptIndexer()
		val random = Random(20261126)
		var messages = emptyList<ChatMessageUi>()
		var streaming = false

		repeat(500) { step ->
			when (random.nextInt(12)) {
				0 -> { messages = messages + user(); streaming = false }
				1 -> { messages = messages + injected() }
				2 -> { messages = messages + assistant(streaming = true); streaming = true }
				3 -> { messages = messages + assistant(); streaming = false }
				4 -> { messages = messages + toolResult() }
				5 -> if (messages.isNotEmpty()) {
					val last = messages.last()
					messages = messages.dropLast(1) + last.copy(
						plainText = last.plainText + "·",
						usage = UsageUi(completionTokens = step),
					)
				}
				6 -> if (messages.isNotEmpty()) {
					val at = random.nextInt(messages.size)
					messages = messages.toMutableList().also { it[at] = it[at].copy(plainText = "edited") }
				}
				7 -> if (messages.isNotEmpty()) messages = messages.dropLast(1)
				8 -> messages = messages.map { it.copy() }
				9 -> if (messages.isNotEmpty()) {
					// A call that never settles keeps the footer off the turn.
					messages = messages + assistant(
						streaming = false,
						calls = listOf(ToolCallUi(index = 0, id = "c$step", name = "read_file")),
					)
				}
				10 -> streaming = !streaming
				else -> Unit
			}
			assertMatches(indexer, messages, streaming, "step $step")
		}
	}
}
