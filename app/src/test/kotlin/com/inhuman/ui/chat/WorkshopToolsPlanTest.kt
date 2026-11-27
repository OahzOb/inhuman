package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The availability rule for the workshop helpers.
 *
 * The workshop is one place with two jobs: role-play authoring and app
 * configuration. The rule is the interesting part -- helpers exist only in the
 * workshop, never in a scene, and the validator additionally needs a read tool
 * the conversation already had.
 */
class WorkshopToolsPlanTest {

	private val fileTools = listOf("read_file", "write_file", "list_files", "delete_file")

	private fun plan(
		workshop: Boolean = true,
		roleplayActive: Boolean = false,
		tools: List<String> = fileTools,
		permitted: Set<String> = tools.toSet(),
	): WorkshopHelpersPlan = planWorkshopHelpers(
		workshop = workshop,
		roleplayActive = roleplayActive,
		fileToolNames = tools,
	) { it in permitted }

	@Test
	fun `an ordinary workspace gets no workshop helpers`() {
		val plan = plan(workshop = false)

		assertEquals(WorkshopHelpersPlan.NONE, plan)
	}

	@Test
	fun `a scene gets no workshop helpers even in the workshop`() {
		val plan = plan(roleplayActive = true)

		assertEquals(WorkshopHelpersPlan.NONE, plan)
	}

	@Test
	fun `a workshop conversation gets both jobs`() {
		val plan = plan()

		// Role-play authoring.
		assertTrue(plan.format)
		assertTrue(plan.validate)
		assertTrue(plan.createScene)
		// App configuration.
		assertTrue(plan.listAssistants)
		assertTrue(plan.readAssistant)
		assertTrue(plan.proposeAssistant)
		assertTrue(plan.listInstructions)
		assertTrue(plan.readInstruction)
		assertTrue(plan.proposeInstruction)
		assertTrue(plan.listPrompts)
		assertTrue(plan.readPrompt)
		assertTrue(plan.proposePrompt)
	}

	@Test
	fun `the validator needs an existing read tool`() {
		val writeOnly = plan(tools = listOf("write_file", "append_file"))

		assertTrue("rp_format is constant text", writeOnly.format)
		assertTrue("the proposal only previews data", writeOnly.createScene)
		assertFalse("rp_validate would be a new read path", writeOnly.validate)
	}

	@Test
	fun `read_file and list_files both satisfy the validator`() {
		assertTrue(plan(tools = listOf("read_file")).validate)
		assertTrue(plan(tools = listOf("list_files")).validate)
	}

	@Test
	fun `a read tool the conversation did not select does not satisfy the validator`() {
		val plan = plan(
			tools = listOf("read_file", "list_files"),
			permitted = emptySet(),
		)

		assertFalse(plan.validate)
	}

	@Test
	fun `configuration helpers need no file capability`() {
		val plan = plan(tools = emptyList())

		assertFalse(plan.validate)
		assertTrue(plan.listAssistants)
		assertTrue(plan.readAssistant)
		assertTrue(plan.proposeAssistant)
		assertTrue(plan.listInstructions)
		assertTrue(plan.readInstruction)
		assertTrue(plan.proposeInstruction)
		assertTrue(plan.listPrompts)
		assertTrue(plan.readPrompt)
		assertTrue(plan.proposePrompt)
	}
}
