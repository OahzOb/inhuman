package com.inhuman.core.ai.provider.deepseek

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the failure path actually does.
 *
 * ## Why this is a test and not a reading
 *
 * `callbackFlow` creates a buffered channel, and whether `trySend` still
 * delivers after `close()` is a fact about a library version rather than about
 * this code. The production code depended on that answer, got it wrong, and the
 * consequence was invisible: a dropped failure chunk leaves a flow that ends
 * normally, so the caller cannot tell "the network died" from "the model had
 * nothing to say".
 *
 * These two tests pin the semantics the provider relies on. If a future
 * coroutines release changes them, this fails here rather than in the app.
 */
class CallbackFlowFailureDeliveryTest {

	/** The shape `DeepSeekProvider.stream` uses: emit, then close. */
	private fun emitsThenCloses(): Flow<String> = callbackFlow {
		trySend("failure")
		close()
		awaitClose { }
	}

	/** The shape the bug had: close first, then try to emit. */
	private fun closesThenEmits(): Flow<String> = callbackFlow {
		close()
		val sent = trySend("failure")
		// Recorded so the test can state the mechanism, not just the outcome.
		lastSendFailed = sent.isFailure
		awaitClose { }
	}

	@Test
	fun `a chunk sent before close is delivered`() = runTest {
		val received = emitsThenCloses().toList()

		assertEquals(listOf("failure"), received)
	}

	@Test
	fun `a chunk sent after close is dropped, and the collector still ends normally`() = runTest {
		val received = closesThenEmits().toList()

		assertTrue("trySend after close reports failure", lastSendFailed)
		assertEquals(
			"and the collector sees a clean end, which is the whole problem",
			emptyList<String>(),
			received,
		)
	}

	@Test
	fun `a buffered chunk sent just before close is still delivered`() = runTest {
		// The realistic shape: several chunks of content, then a failure.
		val flow = callbackFlow {
			trySend("hello")
			trySend(" ")
			trySend("world")
			trySend("F")
			close()
			awaitClose { }
		}

		val received = flow.toList()

		assertEquals(listOf("hello", " ", "world", "F"), received)
		assertFalse("nothing was lost", received.isEmpty())
	}

	@Test
	fun `the default buffer drops what does not fit, and unlimited does not`() = runTest {
		// The other half of the same trap. `trySend` cannot wait for room, so a
		// producer that outruns its buffer -- a fast SSE stream, or a collector
		// busy with a tool call -- silently loses chunks. In a reply that shows
		// up as missing words, with nothing in the log to say so.
		//
		// The providers fuse `.buffer(Channel.UNLIMITED)` onto their flows for
		// this reason; these two assertions are what that fix is worth.
		var droppedByDefault = 0
		val finite = callbackFlow {
			repeat(100) { index ->
				if (trySend("chunk-$index").isFailure) droppedByDefault++
			}
			close()
			awaitClose { }
		}
		val finiteReceived = finite.toList()

		assertTrue("the default capacity is finite", droppedByDefault > 0)
		assertEquals(
			"every chunk either arrived or was counted as dropped",
			100,
			finiteReceived.size + droppedByDefault,
		)

		var droppedWhenUnlimited = 0
		val unlimited = callbackFlow {
			repeat(100) { index ->
				if (trySend("chunk-$index").isFailure) droppedWhenUnlimited++
			}
			close()
			awaitClose { }
		}
			.buffer(Channel.UNLIMITED)
		val unlimitedReceived = unlimited.toList()

		assertEquals("nothing is dropped", 0, droppedWhenUnlimited)
		assertEquals(100, unlimitedReceived.size)
		assertEquals("chunk-0", unlimitedReceived.first())
		assertEquals("chunk-99", unlimitedReceived.last())
	}

	private companion object {
		/** Written by the flow builder; read by the test that owns it. */
		@Volatile
		var lastSendFailed: Boolean = false
	}
}
