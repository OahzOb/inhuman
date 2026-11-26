package com.inhuman.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment rule keeps two promises: a scene with no tools stays empty,
 * and the validator never appears where the agent cannot already read a file.
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
	fun `a scene with no tools gets neither helper`() {
		val plan = planRoleplayHelpers(fileTools) { false }

		assertFalse(plan.format)
		assertFalse(plan.validate)
	}

	@Test
	fun `write capability alone gets the format text but not the validator`() {
		val plan = planRoleplayHelpers(fileTools) { it == "write_file" }

		assertTrue(plan.format)
		assertFalse(plan.validate)
	}

	@Test
	fun `read_file admits the validator`() {
		val plan = planRoleplayHelpers(fileTools) { it == "read_file" }

		assertTrue(plan.format)
		assertTrue(plan.validate)
	}

	@Test
	fun `list_files admits the validator, because story mode lists a folder`() {
		val plan = planRoleplayHelpers(fileTools) { it == "list_files" }

		assertTrue(plan.format)
		assertTrue(plan.validate)
	}

	@Test
	fun `document tools alone do not admit the validator`() {
		val plan = planRoleplayHelpers(fileTools) { it == "read_document" }

		assertTrue(plan.format)
		assertFalse(plan.validate)
	}

	@Test
	fun `an empty host tool list means no helpers even when everything is permitted`() {
		val plan = planRoleplayHelpers(emptyList()) { true }

		assertFalse(plan.format)
		assertFalse(plan.validate)
	}
}
