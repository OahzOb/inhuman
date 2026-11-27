package com.inhuman.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.WorkspaceKinds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one built-in workshop on the JVM.
 *
 * The row is the whole state: the kind must survive a re-read, ordinary
 * workspaces must never acquire it, there is exactly one workshop row, and the
 * ensure call must be idempotent because an entry can be tapped twice before
 * the first creation finishes. The workshop is also app-owned: rename and delete
 * refuse it, because its entry is a fixed feature surface and deleting it would
 * take the scenes and materials that live there with it.
 */
class WorkshopWorkspaceTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var db: InhumanDatabase
	private lateinit var repository: ConversationRepository

	@Before
	fun setUp() {
		db = Room.inMemoryDatabaseBuilder<InhumanDatabase>()
			.setDriver(BundledSQLiteDriver())
			.build()
		repository = ConversationRepository(db, DataPaths(tmp.newFolder("files")))
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun `an ordinary workspace has no kind`() = runBlocking {
		val workspace = repository.createWorkspace("notes")

		assertEquals(WorkspaceKinds.NONE, workspace.kind)
		assertEquals(WorkspaceKinds.NONE, repository.workspaceRecord(workspace.id)?.kind)
	}

	@Test
	fun `ensureWorkshop creates one permanent workshop`() = runBlocking {
		val workshop = repository.ensureWorkshop("工坊")

		assertEquals(WorkspaceKinds.WORKSHOP, workshop.kind)
		assertTrue(workshop.isPermanent)
		assertEquals("工坊", workshop.name)
		assertNotNull(repository.workspaceRecord(workshop.id))
		assertEquals(WorkspaceKinds.WORKSHOP, repository.workshop()?.kind)
	}

	@Test
	fun `ensureWorkshop is idempotent and never replaces the first row`() = runBlocking {
		val first = repository.ensureWorkshop("工坊")
		val second = repository.ensureWorkshop("Another name")

		assertEquals(first.id, second.id)
		assertEquals("工坊", repository.workshop()?.name)
	}

	@Test
	fun `ensureWorkshop adopts a row that already carries the workshop kind`() = runBlocking {
		// The shape v29 leaves behind: the kind is already on the surviving row,
		// and the entry must open it instead of creating a second workshop.
		val existing = repository.createWorkspace("RP 工坊", WorkspaceKinds.WORKSHOP)

		val ensured = repository.ensureWorkshop("工坊")

		assertEquals(existing.id, ensured.id)
		assertEquals(1, repository.workspaces().count { it.kind == WorkspaceKinds.WORKSHOP })
	}

	@Test
	fun `a scene can be created inside the workshop workspace`() = runBlocking {
		val workshop = repository.ensureWorkshop("工坊")

		val scene = repository.create(
			title = "Archivist",
			workspaceId = workshop.id,
			tools = emptyList(),
			sceneJson = """{"world":"a drowned library"}""",
			rpSnapshotJson = """{"systemPrompt":"","characterName":"Archivist","card":"{}"}""",
			greeting = "You are late.",
		)

		assertEquals(workshop.id, scene.workspaceId)
		assertNotEquals("", scene.rpSnapshotJson)
		assertEquals("[]", scene.toolsJson)
	}

	@Test
	fun `the workshop cannot be renamed`() = runBlocking {
		val workshop = repository.ensureWorkshop("工坊")

		repository.renameWorkspace(workshop, "My workshop")

		assertEquals("工坊", repository.workshop()?.name)
	}

	@Test
	fun `the workshop cannot be deleted even when empty`() = runBlocking {
		val workshop = repository.ensureWorkshop("工坊")

		assertFalse(repository.deleteWorkspace(workshop))
		assertNotNull(repository.workspaceRecord(workshop.id))
	}

	@Test
	fun `bulk delete never takes the workshop or its conversations`() = runBlocking {
		val workshop = repository.ensureWorkshop("工坊")
		repository.create(
			title = "A scene",
			workspaceId = workshop.id,
			tools = emptyList(),
			sceneJson = """{"world":"somewhere"}""",
			rpSnapshotJson = """{"systemPrompt":"","characterName":"A","card":"{}"}""",
		)
		val ordinary = repository.createWorkspace("notes")

		val deleted = repository.deleteWorkspaces(listOf(workshop, ordinary))

		assertEquals(1, deleted.workspaces)
		assertEquals(0, deleted.conversations)
		assertNotNull(repository.workspaceRecord(workshop.id))
		assertEquals(null, repository.workspaceRecord(ordinary.id))
	}

	@Test
	fun `an ordinary workspace can still be renamed and deleted when empty`() = runBlocking {
		val workspace = repository.createWorkspace("notes")

		repository.renameWorkspace(workspace, "renamed")
		assertEquals("renamed", repository.workspaceRecord(workspace.id)?.name)
		assertTrue(repository.deleteWorkspace(repository.workspaceRecord(workspace.id)!!))
		assertEquals(null, repository.workspaceRecord(workspace.id))
	}
}
