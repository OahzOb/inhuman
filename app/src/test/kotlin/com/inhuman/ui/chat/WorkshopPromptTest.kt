package com.inhuman.ui.chat

import com.inhuman.core.data.db.WorkspaceKinds
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a workshop conversation is told about itself.
 *
 * The helpers are attached structurally, but the model has no other way to know
 * that it is in the workshop with no scene yet, that a scene proposal creates
 * nothing, or that a configuration draft is not a change. The block is content,
 * so these tests also pin that it states the boundary instead of implying a
 * capability.
 */
class WorkshopPromptTest {

	@Test
	fun `an ordinary workspace gets no workshop context`() {
		assertNull(workshopPrompt(WorkspaceKinds.NONE, roleplayActive = false))
		assertNull(workshopPrompt(null, roleplayActive = false))
		// A kind added by a newer release degrades to "no block", not to a guess.
		assertNull(workshopPrompt("something-new", roleplayActive = false))
	}

	@Test
	fun `the workshop says it is the workshop, not a scene`() {
		val text = workshopPrompt(WorkspaceKinds.WORKSHOP, roleplayActive = false)
			?: error("the workshop must get a block")

		assertTrue(text.startsWith(WORKSHOP_PROMPT_MARKER))
		assertTrue(text, text.contains("runs in the user's workshop"))
		assertTrue(text, text.contains("not a scene"))
		assertTrue(text, text.contains("no character is in play"))
		assertTrue(text, text.contains("cannot enter"))
		assertTrue(text, text.contains("Never announce a scene"))
	}

	@Test
	fun `the workshop explains both jobs and both write-on-user-action rules`() {
		val text = workshopPrompt(WorkspaceKinds.WORKSHOP, roleplayActive = false)
			?: error("the workshop must get a block")

		// Role-play authoring.
		assertTrue(text, text.contains("rp_format"))
		assertTrue(text, text.contains("rp_create_scene"))
		// App configuration.
		assertTrue(text, text.contains("app_list_*"))
		assertTrue(text, text.contains("AGENTS.md"))
		assertTrue(text, text.contains("app_propose_*"))
		// Nothing takes effect by itself.
		assertTrue(text, text.contains("creates nothing"))
		assertTrue(text, text.contains("written only"))
		assertTrue(text, text.contains("user saves there"))
		assertTrue(text, text.contains("Save to prompt library"))
	}

	@Test
	fun `the workshop states the capability boundary`() {
		val text = workshopPrompt(WorkspaceKinds.WORKSHOP, roleplayActive = false)
			?: error("the workshop must get a block")

		// A boundary that is not stated is a boundary the model will cross.
		assertTrue(text, text.contains("cannot change tools"))
		assertTrue(text, text.contains("delete an assistant"))
		assertTrue(text, text.contains("saved-prompt library"))
	}

	@Test
	fun `a scene gets no workshop context`() {
		// A scene has its own role-play prompt; the workshop is not a mode the
		// story can switch back into.
		assertNull(workshopPrompt(WorkspaceKinds.WORKSHOP, roleplayActive = true))
	}
}
