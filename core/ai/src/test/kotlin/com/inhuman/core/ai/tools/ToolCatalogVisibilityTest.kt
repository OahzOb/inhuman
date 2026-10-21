package com.inhuman.core.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker must not merely disable SSH when the global switch is off; it must
 * not show the capability at all. That is a UI rule, but it is enforced by this
 * one catalogue list so every picker and the approval table agree.
 */
class ToolCatalogVisibilityTest {

	@Test
	fun sshIsAbsentFromTheVisibleCatalogueWhenDisabled() {
		val visible = ToolCatalog.visible(includeSsh = false).map { it.name }
		assertFalse("ssh_exec was visible while disabled", "ssh_exec" in visible)
		assertTrue("the ordinary tools disappeared too", "read_file" in visible)
	}

	@Test
	fun sshAppearsOnlyWhenTheGlobalCapabilityIsOn() {
		val visible = ToolCatalog.visible(includeSsh = true).map { it.name }
		assertTrue("ssh_exec was not offered after enabling", "ssh_exec" in visible)
		assertTrue("the ordinary tools disappeared too", "web_search" in visible)
	}
}
