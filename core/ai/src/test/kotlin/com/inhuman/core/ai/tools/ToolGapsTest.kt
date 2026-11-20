package com.inhuman.core.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gap report, and the line it may not cross.
 *
 * The universe is what the app offers *now*; a capability the user switched off
 * is absent from that universe and must not appear here. A tool whose purpose
 * is to describe gaps would otherwise become the greyed row that
 * `docs/08-port-philosophy.md` §1.2.1 refuses -- and the model would learn the
 * name of a capability the user turned off, which is exactly what a prompt
 * injection would like to know.
 */
class ToolGapsTest {

	private fun offered(vararg names: String) =
		names.map { ToolCatalog.all.first { entry -> entry.name == it } }

	@Test
	fun `a tool the selection excludes is reported as not selected`() {
		val gaps = ToolGaps.compute(
			available = setOf("read_time"),
			selected = listOf("read_time"),
			offered = offered("read_time", "read_file"),
		)

		assertEquals(listOf("read_file"), gaps.map { it.name })
		assertEquals(ToolGaps.Reason.NotSelected, gaps.single().reason)
	}

	@Test
	fun `a selected tool the host could not build needs configuration`() {
		val gaps = ToolGaps.compute(
			available = emptySet(),
			selected = listOf("web_search"),
			offered = offered("web_search"),
		)

		assertEquals(ToolGaps.Reason.NeedsConfiguration, gaps.single().reason)
	}

	@Test
	fun `a null selection still leaves opt-in tools out`() {
		// "Everything" predates the SSH tools; it must not read as consent.
		val gaps = ToolGaps.compute(
			available = emptySet(),
			selected = null,
			offered = offered("ssh_exec"),
		)

		assertEquals(ToolGaps.Reason.NotSelected, gaps.single().reason)
	}

	@Test
	fun `nothing is reported when everything offered is available`() {
		val gaps = ToolGaps.compute(
			available = setOf("read_time", "read_file"),
			selected = null,
			offered = offered("read_time", "read_file"),
		)

		assertTrue(gaps.isEmpty())
	}

	@Test
	fun `a capability absent from the offered set is never reported`() {
		// This is the rule: the host passes `visible(includeSsh = false)` while
		// the switch is off, so `ssh_exec` is not in the universe even though it
		// exists in the catalogue.
		val visibleWithoutSsh = ToolCatalog.visible(includeSsh = false)

		val gaps = ToolGaps.compute(
			available = emptySet(),
			selected = listOf("ssh_exec"),
			offered = visibleWithoutSsh,
		)

		assertTrue("no SSH name may appear", gaps.none { it.name.startsWith("ssh_") })
	}
}
