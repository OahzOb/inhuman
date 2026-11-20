package com.inhuman.core.ai.tools

import org.junit.Assert.assertEquals
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
	fun pdfVisionIsAbsentUntilTheHostOffersIt() {
		// A host without the renderer or vision request path must not show a
		// row it cannot execute; the Android pickers pass the flag.
		val without = ToolCatalog.visible(includeSsh = false).map { it.name }
		assertFalse("read_pdf_vision" in without)

		val with = ToolCatalog.visible(includeSsh = false, includePdfVision = true).map { it.name }
		assertTrue("read_pdf_vision" in with)
	}

	@Test
	fun everyWorkspaceDependentToolIsMarked() {
		val marked = ToolCatalog.all.filter { it.workspaceBound }.map { it.name }.toSet()

		listOf(
			"list_files",
			"read_file",
			"write_file",
			"read_pdf_vision",
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

	@Test
	fun bilibiliReadToolsAreAbsentUntilTheirSwitchIsOn() {
		// Same rule as SSH and follow-ups: a capability the user has not
		// switched on is absent, not greyed, so a model cannot learn the name
		// from a picker and an approval table has no row to configure.
		val off = ToolCatalog.visible(includeSsh = false).map { it.name }
		ToolCatalog.all.filter { it.gatedByBilibili }.forEach { entry ->
			assertFalse("${entry.name} was visible while its switch was off", entry.name in off)
		}

		val on = ToolCatalog.visible(includeSsh = false, includeBilibili = true).map { it.name }
		ToolCatalog.all.filter { it.gatedByBilibili }.forEach { entry ->
			assertTrue("${entry.name} was not offered after enabling", entry.name in on)
		}
	}

	@Test
	fun bilibiliReadToolsAreOptInAndNeitherPaidNorRemote() {
		// A new external read must not be inherited by an assistant whose
		// selection is null ("all tools" from before this name existed), and it
		// is not a remote operation: it reads public content, it does not reach
		// another machine the way SSH does.
		val bilibili = ToolCatalog.all.filter { it.gatedByBilibili }
		assertEquals("four read tools", 4, bilibili.size)
		bilibili.forEach { entry ->
			assertTrue("${entry.name} must be opt-in", entry.optIn)
			assertFalse("${entry.name} must not be paid", entry.paid)
			assertFalse("${entry.name} must not be remote", entry.remote)
			assertFalse("${entry.name} must not mutate", entry.mutates)
			assertFalse("${entry.name} is a read, not a workspace write", entry.workspaceBound)
		}

		assertFalse(ToolCatalog.permits(null, "bili_search"))
		assertFalse(ToolCatalog.permits(emptyList(), "bili_search"))
		assertTrue(ToolCatalog.permits(listOf("bili_search"), "bili_search"))
	}
}
