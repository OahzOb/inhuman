package com.inhuman.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment rule keeps four promises: role-play authoring lives only in
 * the workshop, a scene keeps an empty tool set, the validator never appears
 * where the agent cannot already read a file, and a workshop conversation with
 * no file tools still gets the inert format text and the proposal tool.
 */
class RoleplayToolsPlanTest {

	private val fileTools = listOf(
		"list_files",
		"read_file",
		"write_file",
		"append_file",
		"create_directory",
		"move_file",
		"delete_file",
		"file_info",
		"read_document",
		"write_document",
	)

	@Test
	fun `an ordinary workspace gets no role-play helpers, even with file tools`() {
		val plan = planRoleplayHelpers(
			workshop = false,
			roleplayActive = false,
			fileToolNames = fileTools,
		) { true }

		assertFalse(plan.format)
		assertFalse(plan.validate)
		assertFalse(plan.createScene)
	}

	@Test
	fun `a scene gets no authoring helpers even inside the workshop`() {
		val plan = planRoleplayHelpers(
			workshop = true,
			roleplayActive = true,
			fileToolNames = fileTools,
		) { true }

		assertFalse(plan.format)
		assertFalse(plan.validate)
		assertFalse(plan.createScene)
	}

	@Test
	fun `a workshop conversation with no file tools still gets format and create`() {
		val plan = planRoleplayHelpers(
			workshop = true,
			roleplayActive = false,
			fileToolNames = emptyList(),
		) { true }

		assertTrue(plan.format)
		assertFalse(plan.validate)
		assertTrue(plan.createScene)
	}

	@Test
	fun `write capability alone does not admit the validator`() {
		val plan = planRoleplayHelpers(
			workshop = true,
			roleplayActive = false,
			fileToolNames = fileTools,
		) { it == "write_file" }

		assertTrue(plan.format)
		assertFalse(plan.validate)
		assertTrue(plan.createScene)
	}

	@Test
	fun `read_file admits the validator`() {
		val plan = planRoleplayHelpers(
			workshop = true,
			roleplayActive = false,
			fileToolNames = fileTools,
		) { it == "read_file" }

		assertTrue(plan.validate)
	}

	@Test
	fun `list_files admits the validator, because story mode lists a folder`() {
		val plan = planRoleplayHelpers(
			workshop = true,
			roleplayActive = false,
			fileToolNames = fileTools,
		) { it == "list_files" }

		assertTrue(plan.validate)
	}

	@Test
	fun `document tools alone do not admit the validator`() {
		val plan = planRoleplayHelpers(
			workshop = true,
			roleplayActive = false,
			fileToolNames = fileTools,
		) { it == "read_document" }

		assertFalse(plan.validate)
	}

	@Test
	fun `a scene outside the workshop still gets nothing`() {
		val plan = planRoleplayHelpers(
			workshop = false,
			roleplayActive = true,
			fileToolNames = fileTools,
		) { true }

		assertFalse(plan.format)
		assertFalse(plan.validate)
		assertFalse(plan.createScene)
	}
}
