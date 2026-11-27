package com.inhuman.core.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-turn budget the local web tools share.
 *
 * The cap itself is the easy half; the tests that matter are "a refusal costs
 * nothing" (a refused call must not eat a request the model could have used)
 * and "spacing is a wait, not a failure" (the model cannot see a clock, so an
 * early request must be delayed, not failed).
 */
class LocalWebBudgetTest {

	private fun waitOf(budget: LocalWebBudget): Long =
		(budget.tryAcquire() as LocalWebBudget.Permit.Granted).waitMillis

	@Test
	fun `grants up to the cap and then refuses with a readable reason`() {
		val budget = LocalWebBudget(maxRequests = 2, spacingMillis = 0)

		assertTrue(budget.tryAcquire() is LocalWebBudget.Permit.Granted)
		assertTrue(budget.tryAcquire() is LocalWebBudget.Permit.Granted)

		val refused = budget.tryAcquire() as LocalWebBudget.Permit.Refused
		assertTrue(refused.message, "used up" in refused.message)
		assertTrue(refused.message, "2 of 2" in refused.message)
		assertEquals(2, budget.used)
		assertEquals(0, budget.remaining)
	}

	@Test
	fun `a refusal does not spend a request`() {
		val budget = LocalWebBudget(maxRequests = 1, spacingMillis = 0)

		budget.tryAcquire()
		repeat(3) { assertTrue(budget.tryAcquire() is LocalWebBudget.Permit.Refused) }

		assertEquals(1, budget.used)
	}

	@Test
	fun `spacing is returned as a wait and does not refuse`() {
		var clock = 1_000L
		val budget = LocalWebBudget(maxRequests = 10, spacingMillis = 700, now = { clock })

		assertEquals(0L, waitOf(budget))
		// Same instant: the second request must wait one spacing.
		assertEquals(700L, waitOf(budget))
		// At the moment the second request actually runs, the third still has
		// to wait one spacing from then, not from when it was asked for.
		clock = 1_700L
		assertEquals(700L, waitOf(budget))
		// The third runs at 2_400; a call that arrives after that run plus the
		// spacing needs no wait at all.
		clock = 3_200L
		assertEquals(0L, waitOf(budget))
	}

	@Test
	fun `rendered fetches have their own smaller cap`() {
		val budget = LocalWebBudget(maxRequests = 10, maxRendered = 1, spacingMillis = 0)

		assertTrue(budget.tryAcquire(rendered = true) is LocalWebBudget.Permit.Granted)

		val refused = budget.tryAcquire(rendered = true) as LocalWebBudget.Permit.Refused
		assertTrue(refused.message, "rendered" in refused.message)
		assertEquals(1, budget.rendered)
		assertEquals(1, budget.used)

		// The static path is unaffected by the render cap.
		assertTrue(budget.tryAcquire(rendered = false) is LocalWebBudget.Permit.Granted)
		assertEquals(2, budget.used)
	}

	@Test
	fun `the render refusal does not spend a request`() {
		val budget = LocalWebBudget(maxRequests = 2, maxRendered = 1, spacingMillis = 0)

		budget.tryAcquire(rendered = true)
		budget.tryAcquire(rendered = true)

		assertEquals(1, budget.used)
	}
}
