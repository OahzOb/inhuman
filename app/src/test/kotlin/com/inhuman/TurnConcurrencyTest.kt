package com.inhuman

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide turn ceiling.
 *
 * The counter is a singleton, so every test drains what it took; a failure
 * before `release` must not make the next test start with a full ceiling.
 */
class TurnConcurrencyTest {

	@After
	fun drain() {
		while (TurnConcurrency.current > 0) TurnConcurrency.release()
	}

	@Test
	fun `two turns are admitted and the third is refused`() {
		assertTrue(TurnConcurrency.tryAcquire())
		assertTrue(TurnConcurrency.tryAcquire())
		assertFalse(TurnConcurrency.tryAcquire())
		assertEquals(TurnConcurrency.MAX_ACTIVE_TURNS, TurnConcurrency.current)
	}

	@Test
	fun `a released slot can be reused`() {
		assertTrue(TurnConcurrency.tryAcquire())
		assertTrue(TurnConcurrency.tryAcquire())
		TurnConcurrency.release()
		assertTrue(TurnConcurrency.tryAcquire())
		assertFalse(TurnConcurrency.tryAcquire())
	}

	@Test
	fun `releasing an unused slot does not raise the ceiling`() {
		TurnConcurrency.release()
		assertEquals(0, TurnConcurrency.current)

		assertTrue(TurnConcurrency.tryAcquire())
		assertTrue(TurnConcurrency.tryAcquire())
		assertFalse(TurnConcurrency.tryAcquire())
	}
}