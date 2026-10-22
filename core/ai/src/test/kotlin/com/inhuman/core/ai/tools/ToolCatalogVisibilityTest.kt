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
		ToolCatalog.all.filter { it.remote }.forEach { entry ->
			assertFalse("${entry.name} was visible while disabled", entry.name in visible)
		}
		assertTrue("the ordinary tools disappeared too", "read_file" in visible)
	}

	@Test
	fun sshAppearsOnlyWhenTheGlobalCapabilityIsOn() {
		val visible = ToolCatalog.visible(includeSsh = true).map { it.name }
		ToolCatalog.all.filter { it.remote }.forEach { entry ->
			assertTrue("${entry.name} was not offered after enabling", entry.name in visible)
		}
		assertTrue("the ordinary tools disappeared too", "web_search" in visible)
	}
}
