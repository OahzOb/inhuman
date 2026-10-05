package com.inhuman.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a timeout on a tool call can and cannot do.
 *
 * ## Why this is asserted rather than assumed
 *
 * The port design claims the [Tool] contract survives unchanged -- that a
 * runtime needing per-tool timeouts, cancellation and "stop and give me what
 * you have" can be built on top of `suspend fun execute(args)` without
 * touching a single tool. That claim rests on `withTimeout` being enough, and
 * `withTimeout` has a limit worth writing down: **it cancels at a suspension
 * point**. A tool that blocks a thread without suspending is not interrupted,
 * and the difference is invisible until something hangs in production.
 *
 * These tests pin both halves: a suspending tool is cancelled and reports the
 * deadline, and a blocking one keeps running after the caller has given up.
 * The second is not a defect to fix here -- it is the constraint any timeout
 * policy has to be designed around, stated as a test so nobody has to discover
 * it twice.
 */
class ToolTimeoutSemanticsTest {

	/** A tool that suspends, as the real ones do when they read a file. */
	private class SuspendingTool : Tool {
		override val name = "suspending"
		override val description = ""
		override val parameters = emptyMap<String, Any?>()
		override val mutates = false
		override suspend fun execute(arguments: JsonObject): ToolResult {
			delay(10_000)
			return ToolResult.ok("finished")
		}
	}

	/** A tool that blocks its thread and never reaches a suspension point. */
	private class BlockingTool : Tool {
		@Volatile
		var completed = false
		override val name = "blocking"
		override val description = ""
		override val parameters = emptyMap<String, Any?>()
		override val mutates = false
		override suspend fun execute(arguments: JsonObject): ToolResult {
			Thread.sleep(400)
			completed = true
			return ToolResult.ok("finished")
		}
	}

	@Test
	fun `a suspending tool is cancelled at the deadline`() = runBlocking {
		val started = System.currentTimeMillis()
		val result = withTimeoutOrNull(60) { SuspendingTool().execute(JsonObject(emptyMap())) }
		val elapsed = System.currentTimeMillis() - started

		assertNull("the deadline returns null rather than a result", result)
		assertTrue("it gave up at the deadline, not at the tool's own end: ${elapsed}ms", elapsed < 1_000)
	}

	@Test
	fun `a blocking tool ignores the deadline entirely`() = runBlocking {
		// The first version of this test asserted that the deadline returned
		// null and the work carried on in the background. It failed, and the
		// real behaviour is worse than that: `Thread.sleep` never checks for
		// cancellation, so `withTimeoutOrNull(60)` did not fire at all -- the
		// call ran its full 400 ms and returned a **successful result**, with no
		// indication that a 60 ms budget had been set and blown sixty-fold.
		//
		// Which is the whole caveat, in one assertion: a coroutine timeout is
		// only a budget for work that suspends. A tool that blocks its thread
		// silently escapes any timeout policy built on `withTimeout`.
		val tool = BlockingTool()
		val started = System.currentTimeMillis()
		val result = withContext(Dispatchers.IO) {
			withTimeoutOrNull(60) { tool.execute(JsonObject(emptyMap())) }
		}
		val elapsed = System.currentTimeMillis() - started

		assertEquals("the deadline did not fire; the tool completed", "finished", result?.content)
		assertTrue("and it took its own time, not the budget: ${elapsed}ms", elapsed >= 300)
		assertTrue(tool.completed)
	}

	@Test
	fun `the contract needs no timeout parameter to be timed out`() = runBlocking {
		// The point of the whole file: `execute` takes arguments and nothing
		// else, and that is sufficient. Whatever enforces a budget calls it --
		// which is why an event-sourced runtime can be dropped on top of the
		// existing tools rather than through them.
		val tool: Tool = SuspendingTool()
		val result = withTimeoutOrNull(60) { tool.execute(JsonObject(emptyMap())) }

		assertNull(result)
		assertEquals("no signature was harmed", "suspending", tool.name)
	}
}
