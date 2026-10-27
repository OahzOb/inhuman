package com.inhuman.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

/**
 * The desktop workspace manager's data path.
 *
 * The UI is a thin shell over these calls; the property worth pinning is that a
 * permanent workspace is a real row and a real directory, can be renamed, and
 * cannot be deleted while a conversation still points at it.
 */
class DesktopWorkspacesTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private var store: DesktopStore? = null

	@After
	fun close() {
		store = null
	}

	@Test
	fun `a permanent workspace is listed, renamed and deleted`() = runBlocking {
		val opened = DesktopStore.open(tmp.newFolder("data"))
		store = opened

		val created = opened.createWorkspace("Reports")
		assertTrue(opened.permanentWorkspaces().any { it.id == created.id })
		assertTrue(opened.workspaceDirectory(created.id).isDirectory)

		opened.renameWorkspace(created, "Quarterly")
		assertEquals("Quarterly", opened.permanentWorkspaces().single { it.id == created.id }.name)

		assertTrue(opened.deleteWorkspace(opened.permanentWorkspaces().single { it.id == created.id }))
		assertTrue(opened.permanentWorkspaces().none { it.id == created.id })
	}

	@Test
	fun `a workspace with a conversation cannot be deleted`() = runBlocking {
		val opened = DesktopStore.open(tmp.newFolder("data"))
		store = opened
		val created = opened.createWorkspace("Shared")

		// A new conversation gets its own temporary workspace; attach it to the
		// permanent one through the same repository the Android host uses.
		opened.create("uses shared", workspaceId = created.id)

		assertEquals(1, opened.workspaceConversationCount(created.id))
		assertTrue(!opened.deleteWorkspace(opened.permanentWorkspaces().single { it.id == created.id }))
	}
}