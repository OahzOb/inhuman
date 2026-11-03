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

	@Test
	fun suggestionToolIsAbsentUntilItsGlobalSwitchIsOn() {
		// Same rule as SSH: while the switch is off the row is absent, not
		// greyed. A visible disabled row still advertises the capability.
		val off = ToolCatalog.visible(includeSsh = false).map { it.name }
		assertFalse("suggest_replies" in off)

		val on = ToolCatalog.visible(includeSsh = false, includeSuggestions = true).map { it.name }
		assertTrue("suggest_replies" in on)
	}

	@Test
	fun workspaceToolsAreAbsentWhenTheFenceCheckFailed() {
		val visible = ToolCatalog.visible(
			includeSsh = true,
			includeWorkspaceBound = false,
		).map { it.name }

		ToolCatalog.all.filter { it.workspaceBound }.forEach { entry ->
			assertFalse("${entry.name} was visible after a failed fence check", entry.name in visible)
		}
		// The tools that do not touch the workspace must survive: losing the
		// fence is not a reason to take away the clock or the web.
		assertTrue("read_time" in visible)
		assertTrue("web_search" in visible)
		assertTrue("decide" in visible)
	}

	@Test
	fun recoveringTheFenceOffersTheWorkspaceToolsAgain() {
		val visible = ToolCatalog.visible(
			includeSsh = true,
			includeWorkspaceBound = true,
		).map { it.name }

		assertTrue("read_file" in visible)
		assertTrue("generate_image" in visible)
		assertTrue("ssh_download" in visible)
	}

	@Test
	fun everyWorkspaceDependentToolIsMarked() {
		val marked = ToolCatalog.all.filter { it.workspaceBound }.map { it.name }.toSet()

		listOf(
			"list_files",
			"read_file",
			"write_file",
			"transcribe",
			"generate_image",
			"generate_video",
			"video_status",
			"speak",
			"ssh_upload",
			"ssh_download",
		).forEach { assertTrue("$it should be workspace-bound", it in marked) }

		assertFalse("read_time is workspace-free", "read_time" in marked)
		assertFalse("web_fetch is workspace-free", "web_fetch" in marked)
	}
}
