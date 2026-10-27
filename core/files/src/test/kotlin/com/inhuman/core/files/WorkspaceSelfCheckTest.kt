package com.inhuman.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The device-side fence check, exercised on a JVM filesystem.
 *
 * The checks themselves are the same code that runs at app start; the only
 * difference is where the probe lives. A skipped symlink check is part of the
 * contract, not a hole: a filesystem that refuses to create a link cannot have
 * one planted in a workspace either.
 */
class WorkspaceSelfCheckTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `a healthy fence passes and leaves no probe behind`() {
		val parent = tmp.newFolder("probe")

		val report = WorkspaceSelfCheck.run(parent)

		assertTrue(report.error ?: "the self-check could not run", report.ok)
		assertEquals(6, report.checks.size)
		assertTrue(
			report.checks.first { it.id == WorkspaceSelfCheck.Id.ParentTraversal }.outcome ==
				WorkspaceSelfCheck.Outcome.Passed,
		)
		assertTrue(
			"the probe directory was left behind",
			parent.listFiles().orEmpty().none { it.name.startsWith("fence-") },
		)
	}

	@Test
	fun `a parent that is a file fails closed`() {
		val report = WorkspaceSelfCheck.run(tmp.newFile("not-a-directory"))

		assertFalse(report.ok)
		assertNotNull(report.error)
	}

	@Test
	fun `one failed check fails the report`() {
		val report = WorkspaceSelfCheck.Report(
			listOf(
				WorkspaceSelfCheck.Check(
					WorkspaceSelfCheck.Id.ParentTraversal,
					WorkspaceSelfCheck.Outcome.Failed,
					"'..' was accepted",
				),
			),
		)

		assertFalse(report.ok)
		assertEquals(1, report.failed.size)
	}

	@Test
	fun `a skipped platform check does not fail the report`() {
		val report = WorkspaceSelfCheck.Report(
			listOf(
				WorkspaceSelfCheck.Check(
					WorkspaceSelfCheck.Id.SymlinkEscape,
					WorkspaceSelfCheck.Outcome.Skipped,
					"this filesystem cannot create symlinks",
				),
			),
		)

		assertTrue(report.ok)
	}
}