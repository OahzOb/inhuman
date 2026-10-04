package com.inhuman.core.ai.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the SSE envelope parser.
 *
 * These matter more than usual because the failure mode is silent: a parser
 * that mishandles a comment line does not crash, it drops a token, and the
 * answer comes out subtly wrong. Several of these cases are documented
 * behaviours of DeepSeek rather than hypotheticals.
 */
class SseParserTest {

	private fun collect(vararg lines: String): List<SseFrame> {
		val parser = SseParser()
		val out = mutableListOf<SseFrame>()
		lines.forEach { parser.accept(it)?.let(out::add) }
		parser.endOfStream()?.let(out::add)
		return out
	}

	@Test
	fun `plain data frame followed by a blank line dispatches once`() {
		val frames = collect("data: {\"a\":1}", "")
		assertEquals(1, frames.size)
		assertEquals("{\"a\":1}", frames[0].data)
		assertNull(frames[0].event)
	}

	@Test
	fun `a frame is not dispatched until the blank line`() {
		val parser = SseParser()
		assertNull(parser.accept("data: {\"a\":1}"))
		assertEquals("{\"a\":1}", parser.accept("")?.data)
	}

	@Test
	fun `comment lines are ignored entirely`() {
		// DeepSeek documents that a streaming request receives ': keep-alive'
		// comments while inference has not begun, and warns that a client
		// parsing the body itself must handle them. A parser that treats every
		// line as JSON dies here.
		val frames = collect(
			": keep-alive",
			": another comment",
			"data: {\"a\":1}",
			"",
		)
		assertEquals(1, frames.size)
		assertEquals("{\"a\":1}", frames[0].data)
	}

	@Test
	fun `a comment does not terminate the frame it precedes`() {
		// The comment arrives *between* data lines here. If a comment reset
		// parser state, the two halves would be dispatched separately.
		val frames = collect(
			"data: first",
			": keep-alive",
			"data: second",
			"",
		)
		assertEquals(1, frames.size)
		assertEquals("first\nsecond", frames[0].data)
	}

	@Test
	fun `multiple data lines are joined with a newline`() {
		val frames = collect("data: line one", "data: line two", "")
		assertEquals(1, frames.size)
		assertEquals("line one\nline two", frames[0].data)
	}

	@Test
	fun `named events are preserved`() {
		// Required for Anthropic and the OpenAI Responses API, which put all
		// their meaning in the event name.
		val frames = collect("event: content_block_delta", "data: {\"x\":1}", "")
		assertEquals("content_block_delta", frames[0].event)
	}

	@Test
	fun `a trailing CR is stripped from CRLF input`() {
		// SSE is CRLF-delimited. A reader that split on LF leaves the CR, which
		// would otherwise be appended to every token of the answer.
		val frames = collect("data: hello\r", "\r")
		assertEquals(1, frames.size)
		assertEquals("hello", frames[0].data)
	}

	@Test
	fun `exactly one leading space after the colon is removed`() {
		val frames = collect("data:  two spaces", "")
		// One space consumed, one preserved -- per spec.
		assertEquals(" two spaces", frames[0].data)
	}

	@Test
	fun `a field with no space after the colon works`() {
		val frames = collect("data:{\"a\":1}", "")
		assertEquals("{\"a\":1}", frames[0].data)
	}

	@Test
	fun `unknown fields are ignored rather than breaking the frame`() {
		val frames = collect("data: x", "somevendor: field", "")
		assertEquals(1, frames.size)
		assertEquals("x", frames[0].data)
	}

	@Test
	fun `id and retry fields are captured`() {
		val frames = collect("id: 42", "retry: 3000", "data: x", "")
		assertEquals("42", frames[0].id)
		assertEquals(3000L, frames[0].retryMillis)
	}

	@Test
	fun `an unterminated final frame is flushed at end of stream`() {
		// Common in practice: the server closes right after its last data line,
		// and that line often carries finish_reason. Losing it leaves the UI
		// streaming forever.
		val frames = collect("data: {\"last\":true}")
		assertEquals(1, frames.size)
		assertEquals("{\"last\":true}", frames[0].data)
	}

	@Test
	fun `a blank line with no accumulated fields dispatches nothing`() {
		val frames = collect("", "", "")
		assertEquals(0, frames.size)
	}

	@Test
	fun `consecutive frames each dispatch separately`() {
		val frames = collect("data: one", "", "data: two", "")
		assertEquals(2, frames.size)
		assertEquals("one", frames[0].data)
		assertEquals("two", frames[1].data)
	}

	@Test
	fun `the last event id persists across frames`() {
		// The spec says the id is sticky, which is what makes a reconnection
		// resumable. Not used yet, but silently breaking it forecloses that.
		val frames = collect("id: abc", "data: one", "", "data: two", "")
		assertEquals("abc", frames[0].id)
		assertEquals("abc", frames[1].id)
	}
}
