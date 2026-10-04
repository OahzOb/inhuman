package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.stream.SseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the DeepSeek stream decoder.
 *
 * Fixtures are written to look like real captured traffic, including the
 * `: keep-alive` comments and the `[DONE]` sentinel, because those are the
 * parts that break naive implementations.
 */
class DeepSeekStreamDecoderTest {

	/** Feed raw SSE lines through the real parser, as the transport would. */
	private fun decodeAll(vararg lines: String): List<StreamChunk> {
		val parser = com.inhuman.core.ai.stream.SseParser()
		val decoder = DeepSeekStreamDecoder()
		val out = mutableListOf<StreamChunk>()
		lines.forEach { line ->
			parser.accept(line)?.let { out += decoder.decode(it) }
		}
		parser.endOfStream()?.let { out += decoder.decode(it) }
		out += decoder.endOfStream()
		return out
	}

	private fun textOf(chunks: List<StreamChunk>): String =
		chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }

	private fun reasoningOf(chunks: List<StreamChunk>): String =
		chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text }

	private fun frame(data: String) = SseFrame(event = null, data = data)

	// --- plain text ----------------------------------------------------------

	@Test
	fun `text deltas accumulate into the full answer`() {
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{"content":", world"},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
			"",
			"data: [DONE]",
			"",
		)
		assertEquals("Hello, world", textOf(chunks))
		assertEquals(1, chunks.filterIsInstance<StreamChunk.TextStart>().size)
		assertEquals(1, chunks.filterIsInstance<StreamChunk.TextEnd>().size)
	}

	@Test
	fun `keep-alive comments do not break decoding`() {
		// Documented DeepSeek behaviour while inference has not started.
		val chunks = decodeAll(
			": keep-alive",
			": keep-alive",
			"""data: {"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}""",
			"",
			"data: [DONE]",
			"",
		)
		assertEquals("hi", textOf(chunks))
	}

	@Test
	fun `the done sentinel produces a finish chunk`() {
		val chunks = decodeAll("data: [DONE]", "")
		assertTrue(chunks.any { it is StreamChunk.Finish })
	}

	// --- reasoning -----------------------------------------------------------

	@Test
	fun `reasoning and text become separate regions`() {
		// DeepSeek puts reasoning in a sibling field of content. If the decoder
		// read only content, the reasoning would vanish; if it merged them, the
		// UI could not collapse the thinking away.
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"reasoning_content":"Let me think"},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{"reasoning_content":" about it."},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{"content":"The answer"},"finish_reason":null}]}""",
			"",
			"data: [DONE]",
			"",
		)
		assertEquals("Let me think about it.", reasoningOf(chunks))
		assertEquals("The answer", textOf(chunks))

		val startReasoning = chunks.indexOfFirst { it is StreamChunk.ReasoningStart }
		val endReasoning = chunks.indexOfFirst { it is StreamChunk.ReasoningEnd }
		val startText = chunks.indexOfFirst { it is StreamChunk.TextStart }
		assertTrue("reasoning must close before text opens", endReasoning in 1..<startText)
		assertTrue(startReasoning < endReasoning)
	}

	@Test
	fun `a frame carrying both reasoning and text emits reasoning first`() {
		// Ordering matters for rendering: the answer must not appear above the
		// thinking that produced it.
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"reasoning_content":"r","content":"t"},"finish_reason":null}]}""",
			"",
			"data: [DONE]",
			"",
		)
		val iReasoning = chunks.indexOfFirst { it is StreamChunk.ReasoningDelta }
		val iText = chunks.indexOfFirst { it is StreamChunk.TextDelta }
		assertTrue(iReasoning < iText)
	}

	@Test
	fun `reasoning regions are not reopened per frame`() {
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"reasoning_content":"a"},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{"reasoning_content":"b"},"finish_reason":null}]}""",
			"",
			"data: [DONE]",
			"",
		)
		assertEquals(1, chunks.filterIsInstance<StreamChunk.ReasoningStart>().size)
	}

	// --- tool calls ----------------------------------------------------------

	@Test
	fun `tool call arguments are accumulated as fragments, not parsed per frame`() {
		// This is the case that breaks naive implementations: each fragment is
		// invalid JSON on its own.
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":""}}]},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"pa"}}]},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.txt\"}"}}]},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
			"",
			"data: [DONE]",
			"",
		)

		val start = chunks.filterIsInstance<StreamChunk.ToolCallStart>().single()
		assertEquals(0, start.index)
		assertEquals("call_1", start.id)
		assertEquals("read_file", start.name)

		val assembled = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
			.joinToString("") { it.inputDelta ?: "" }
		assertEquals("""{"path":"a.txt"}""", assembled)
		// And it parses only now, at the end.
		assertTrue(assembled.isNotBlank())
		DeepSeekJson.parseToJsonElement(assembled)

		assertEquals(1, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().size)
		assertTrue(chunks.any { it is StreamChunk.Finish && it.reason == FinishReason.ToolCalls })
	}

	@Test
	fun `parallel tool calls are tracked by provider index, not arrival order`() {
		// Interleaved on the wire. Keying on arrival order would attach the
		// second call's arguments to the first call.
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"read_file","arguments":"{\"p\":"}}]},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"list_files","arguments":"{}"}}]},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"x\"}"}}]},"finish_reason":null}]}""",
			"",
			"data: [DONE]",
			"",
		)

		val starts = chunks.filterIsInstance<StreamChunk.ToolCallStart>()
		assertEquals(2, starts.size)
		assertEquals("read_file", starts.first { it.index == 0 }.name)
		assertEquals("list_files", starts.first { it.index == 1 }.name)

		val call0 = chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
			.filter { it.index == 0 }.joinToString("") { it.inputDelta ?: "" }
		assertEquals("""{"p":"x"}""", call0)
	}

	@Test
	fun `open tool calls are closed when the stream ends without done`() {
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"x","arguments":"{}"}}]},"finish_reason":null}]}""",
			"",
		)
		assertEquals(1, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().size)
	}

	// --- usage ---------------------------------------------------------------

	@Test
	fun `usage is read, including cache hits and reasoning tokens`() {
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"content":"x"},"finish_reason":null}]}""",
			"",
			"""data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_cache_hit_tokens":64,"completion_tokens_details":{"reasoning_tokens":7}}}""",
			"",
			"data: [DONE]",
			"",
		)
		val usage = chunks.filterIsInstance<StreamChunk.Usage>().singleOrNull()
		// A usage-only frame has an empty choices array, which must not be
		// mistaken for a broken frame.
		assertTrue("usage frame was dropped", usage != null)
		usage!!
		assertEquals(100, usage.promptTokens)
		assertEquals(20, usage.completionTokens)
		assertEquals(64, usage.cachedPromptTokens)
		assertEquals(7, usage.reasoningTokens)
	}

	// --- robustness ----------------------------------------------------------

	@Test
	fun `a malformed frame becomes a failure chunk, not an exception`() {
		// One bad frame must not tear down a response that is otherwise fine.
		val chunks = decodeAll("data: {not json at all", "")
		assertTrue(chunks.any { it is StreamChunk.Failure })
	}

	@Test
	fun `an empty data payload is ignored`() {
		val chunks = decodeAll("data: ", "")
		assertTrue(chunks.isEmpty() || chunks.all { it is StreamChunk.Finish })
	}

	@Test
	fun `a truncated stream still closes its regions and reports unknown finish`() {
		// The connection dropped mid-answer: no [DONE], no finish_reason. The
		// UI needs to stop showing a streaming cursor, and the caller needs to
		// know this was not a clean stop so it can decide to retry.
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"content":"partial ans"},"finish_reason":null}]}""",
			"",
		)
		assertEquals("partial ans", textOf(chunks))
		assertEquals(1, chunks.filterIsInstance<StreamChunk.TextEnd>().size)
		val finish = chunks.filterIsInstance<StreamChunk.Finish>().single()
		assertEquals(FinishReason.Unknown, finish.reason)
	}

	@Test
	fun `end of stream after a clean finish emits nothing extra`() {
		val chunks = decodeAll(
			"""data: {"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}""",
			"",
			"data: [DONE]",
			"",
		)
		assertEquals(1, chunks.filterIsInstance<StreamChunk.Finish>().size)
	}

	@Test
	fun `finish reasons are mapped`() {
		fun reasonFor(raw: String): FinishReason =
			decodeAll("""data: {"choices":[{"delta":{},"finish_reason":"$raw"}]}""", "", "data: [DONE]", "")
				.filterIsInstance<StreamChunk.Finish>().first().reason

		assertEquals(FinishReason.Stop, reasonFor("stop"))
		assertEquals(FinishReason.ToolCalls, reasonFor("tool_calls"))
		assertEquals(FinishReason.Length, reasonFor("length"))
		assertEquals(FinishReason.ContentFilter, reasonFor("content_filter"))
	}

	@Test
	fun `response id and model are carried onto the finish chunk`() {
		val chunks = decodeAll(
			"""data: {"id":"abc123","model":"deepseek-flash","choices":[{"delta":{},"finish_reason":"stop"}]}""",
			"",
			"data: [DONE]",
			"",
		)
		val finish = chunks.filterIsInstance<StreamChunk.Finish>().first()
		assertEquals("abc123", finish.responseId)
		assertEquals("deepseek-flash", finish.model)
	}
}
