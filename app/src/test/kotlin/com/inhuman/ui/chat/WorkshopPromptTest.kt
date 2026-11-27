package com.inhuman.ui.chat

import com.inhuman.core.data.db.WorkspaceKinds
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a workshop conversation is told about itself.
 *
 * The helpers are attached structurally, but the model has no other way to know
 * that it is in the RP workshop with no scene yet, or in the configuration
 * workshop where a draft is not a change. The block is content, so these tests
 * also pin that it states the boundary instead of implying a capability.
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
	fun `the RP workshop says it is the workshop, not a scene`() {
		val text = workshopPrompt(WorkspaceKinds.ROLEPLAY, roleplayActive = false)
			?: error("the RP workshop must get a block")

		assertTrue(text.startsWith(WORKSHOP_PROMPT_MARKER))
		assertTrue(text, text.contains("RP workshop"))
		assertTrue(text, text.contains("rp_create_scene"))
		assertTrue(text, text.contains("nothing is created until the user taps"))
		assertTrue(text, text.contains("no character"))
		assertTrue(text, text.contains("cannot enter"))
	}

	@Test
	fun `the configuration workshop says a draft is not a change`() {
		val text = workshopPrompt(WorkspaceKinds.STUDIO, roleplayActive = false)
			?: error("the configuration workshop must get a block")

		assertTrue(text.startsWith(WORKSHOP_PROMPT_MARKER))
		assertTrue(text, text.contains("configuration workshop"))
		assertTrue(text, text.contains("AGENTS.md"))
		assertTrue(text, text.contains("never say that a change"))
		// A boundary that is not stated is a boundary the model will cross.
		assertTrue(text, text.contains("cannot change tools"))
	}

	@Test
	fun `a scene gets no workshop context`() {
		// A scene has its own role-play prompt; the workshop is not a mode the
		// story can switch back into.
		assertNull(workshopPrompt(WorkspaceKinds.ROLEPLAY, roleplayActive = true))
		assertNull(workshopPrompt(WorkspaceKinds.STUDIO, roleplayActive = true))
	}
}
