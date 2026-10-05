package com.inhuman.core.ai.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-log model: envelope, watermark, and fold.
 *
 * The fixtures are excerpts of **real** DeepSeek Harness log lines, not shapes
 * invented to fit the parser. The envelope sample is copied verbatim from a
 * session on this machine, and the `seq0` distinction was measured across
 * 113,129 events in 102 logs -- `*-chunks` records carry `seq0`, everything
 * durable carries `seq`. Getting that wrong would fold streaming accelerators
 * into history and duplicate every reply.
 */
class SessionProjectionTest {

	// Verbatim from a real log, including the fields this build does not read.
	private val realDescriptor = """
		{"type":"subagent/descriptor","seq":0,"time":1790348406004,"data":{"version":3,"mode":"continuable","provider":"spawn","label":"Review verification scripts","agentProvider":"deepseek-official","agentModel":"deepseek-flash","agentReasoningEffort":"high"}}
	""".trimIndent()

	// A chunk record: no `seq`, only `seq0`. Real logs have 24,122 of these.
	private val realChunk = """
		{"type":"text-chunks","seq0":12,"time0":1790348406004,"data":{"text":"hello"}}
	""".trimIndent()

	@Test
	fun `the envelope round-trips a real log line`() {
		val event = SessionEvent.parseOrNull(realDescriptor)

		assertNotNull(event)
		assertEquals("subagent/descriptor", event!!.type)
		assertEquals(0L, event.seq)
		assertEquals(1790348406004L, event.time)
		assertEquals("Review verification scripts", event.data.string("label"))
		assertTrue("a record with seq is history", event.isDurable)
	}

	@Test
	fun `a chunk record is not history`() {
		val event = SessionEvent.parseOrNull(realChunk)

		assertNotNull(event)
		assertNull("seq0 must not be read as seq", event!!.seq)
		assertFalse(event.isDurable)
		assertEquals("text-chunks", event.type)
	}

	@Test
	fun `a torn or foreign line is skipped, not thrown`() {
		// The normal result of a process dying mid-append, and the normal
		// result of a log written by a newer release. Refusing to open the
		// conversation would be worse than missing one event.
		assertNull(SessionEvent.parseOrNull("""{"type":"tool/call","seq":4,"da"""))
		assertNull(SessionEvent.parseOrNull(""))
		assertNull(SessionEvent.parseOrNull("not json at all"))
		// A line with no type cannot be folded, so it is not an event.
		assertNull(SessionEvent.parseOrNull("""{"seq":1,"time":2}"""))
	}

	@Test
	fun `a whole log parses, skipping what it cannot read`() {
		val log = listOf(realDescriptor, "", realChunk, "{broken").joinToString("\n")

		val events = SessionEvent.parseAll(log)

		assertEquals(2, events.size)
		assertEquals(listOf("subagent/descriptor", "text-chunks"), events.map { it.type })
	}

	// --- the fold -----------------------------------------------------------

	private fun event(type: String, seq: Long, data: String = "{}") =
		SessionEvent.parseOrNull("""{"type":"$type","seq":$seq,"time":${1000 + seq},"data":$data}""")!!

	@Test
	fun `turn and step boundaries are folded from the log, not stored`() {
		val projection = SessionProjection.of(
			listOf(
				event("turn/start", 1),
				event("step/start", 2),
				event("step/end", 3),
				event("step/start", 4),
				event("step/end", 5),
				event("turn/end", 6),
			),
		)

		assertEquals(1, projection.turns.size)
		assertEquals(2, projection.turns.single().steps)
		assertEquals(1L, projection.turns.single().startSeq)
		assertEquals(6L, projection.turns.single().endSeq)
		assertNull("a closed turn leaves nothing open", projection.openTurnSeq)
		assertNull(projection.openStepSeq)
		assertEquals(6L, projection.watermark)
	}

	@Test
	fun `messages and tool traffic keep their log order`() {
		// The field names inside `data` here are **inferred**, not measured:
		// the vocabulary extraction counted envelopes and `data` keys but did
		// not open the payloads of these four kinds. They are the names the
		// projection reads, so this test is where the assumption is written
		// down -- and where a real log would prove it wrong.
		val projection = SessionProjection.of(
			listOf(
				event("user/message", 1, """{"text":"check the docs"}"""),
				event("tool/call", 2, """{"id":"c1","name":"web_search"}"""),
				event("tool/result", 3, """{"toolCallId":"c1","content":"8 results"}"""),
				event("assistant/message", 4, """{"text":"here is what I found"}"""),
			),
		)

		assertEquals(
			listOf(
				SessionProjection.TranscriptEntry.Kind.Message to "user",
				SessionProjection.TranscriptEntry.Kind.ToolCall to "assistant",
				SessionProjection.TranscriptEntry.Kind.ToolResult to "tool",
				SessionProjection.TranscriptEntry.Kind.Message to "assistant",
			),
			projection.messages.map { it.kind to it.role },
		)
		assertEquals("c1", projection.messages[1].toolCallId)
		// The name belongs to the *call*; the result is joined to it by id.
		// Asserting it on the result would have been asserting a field the
		// reference log may not put there.
		assertEquals("web_search", projection.messages[1].toolName)
		assertEquals("c1", projection.messages[2].toolCallId)
		assertEquals("8 results", projection.messages[2].text)
	}

	@Test
	fun `the watermark makes a fold resumable and idempotent`() {
		val log = listOf(
			event("turn/start", 1),
			event("user/message", 2, """{"text":"hi"}"""),
			event("turn/end", 3),
		)

		val once = SessionProjection.of(log)
		// Reading the same log again must not double it: this is what a restart
		// does, and what a stored watermark is for.
		val twice = log.fold(once) { acc, e -> acc.apply(e) }

		assertEquals(1, twice.turns.size)
		assertEquals(1, twice.messages.size)
		assertEquals(once, twice)
	}

	@Test
	fun `folding forward from a stored watermark equals folding from the start`() {
		val log = listOf(
			event("turn/start", 1),
			event("user/message", 2, """{"text":"hi"}"""),
			event("turn/end", 3),
		)
		val resumePoint = SessionProjection.of(log)

		val more = listOf(
			event("turn/start", 4),
			event("assistant/message", 5, """{"text":"hello"}"""),
			event("turn/end", 6),
		)

		val incremental = more.fold(resumePoint) { acc, e -> acc.apply(e) }
		val fromScratch = SessionProjection.of(log + more)

		assertEquals(fromScratch, incremental)
		assertEquals(2, incremental.turns.size)
	}

	@Test
	fun `an event older than the watermark is dropped`() {
		val projection = SessionProjection.of(listOf(event("user/message", 10, """{"text":"ten"}""")))

		val replayed = projection.apply(event("user/message", 5, """{"text":"five"}"""))

		assertSame("nothing changed, so nothing was allocated", projection, replayed)
		assertEquals(1, replayed.messages.size)
	}

	@Test
	fun `an unknown durable kind advances the watermark and is otherwise ignored`() {
		// A newer release's event. It must not stop the fold -- but it must also
		// not be re-read on the next resume.
		val projection = SessionProjection.of(
			listOf(
				event("user/message", 1, """{"text":"hi"}"""),
				event("something/from-the-future", 2),
			),
		)

		assertEquals(2L, projection.watermark)
		assertEquals(1, projection.messages.size)
	}

	@Test
	fun `a half-open turn is representable`() {
		// The state a crash mid-turn leaves behind, and the state a resumed
		// session has to be able to express rather than repair.
		val projection = SessionProjection.of(
			listOf(
				event("turn/start", 1),
				event("step/start", 2),
				event("assistant/message", 3, """{"text":"partial"}"""),
			),
		)

		assertEquals(1L, projection.openTurnSeq)
		assertEquals(2L, projection.openStepSeq)
		assertEquals(1, projection.turns.size)
		assertNull("the turn never ended", projection.turns.single().endSeq)
	}

	@Test
	fun `ignored kinds are named rather than pattern-matched`() {
		// A prefix rule would swallow a future `tool/*` kind that matters. The
		// set is checked directly so that adding a new conversational kind
		// without removing it here fails loudly instead of silently.
		assertTrue("tool/call" !in SessionProjection.IGNORED)
		assertTrue("assistant/message" !in SessionProjection.IGNORED)
		assertTrue("compaction/summary" in SessionProjection.IGNORED)
	}
}
