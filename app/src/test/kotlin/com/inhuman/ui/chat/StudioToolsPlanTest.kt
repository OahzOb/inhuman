package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the configuration helpers are attached.
 *
 * The rule is a presentation decision, not a permission one, but it is still
 * load-bearing: a helper in an ordinary workspace would put "manage the app's
 * assistants" into every request, and one inside a role-play scene would offer a
 * second role to a story. This pins both gates, and the fact that there is no
 * partially-on state to reason about.
 */
class StudioToolsPlanTest {

	@Test
	fun `a configuration-workshop conversation gets every helper`() {
		val plan = planStudioHelpers(studio = true, roleplayActive = false)

		assertEquals(StudioHelpersPlan.ALL, plan)
		assertTrue(plan.listAssistants)
		assertTrue(plan.readAssistant)
		assertTrue(plan.proposeAssistant)
		assertTrue(plan.listInstructions)
		assertTrue(plan.readInstruction)
		assertTrue(plan.proposeInstruction)
	}

	@Test
	fun `an ordinary workspace gets none`() {
		val plan = planStudioHelpers(studio = false, roleplayActive = false)

		assertEquals(StudioHelpersPlan.NONE, plan)
		assertFalse(plan.listAssistants)
		assertFalse(plan.proposeInstruction)
	}

	@Test
	fun `a role-play scene in the workshop gets none`() {
		// A scene must not offer "draft an assistant" inside the story; the same
		// suppression the role-play helpers use applies here.
		val plan = planStudioHelpers(studio = true, roleplayActive = true)

		assertEquals(StudioHelpersPlan.NONE, plan)
	}
}
