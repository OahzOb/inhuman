package com.inhuman.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.WorkspaceKinds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The built-in workshops on the JVM.
 *
 * The row is the whole state: the kind must survive a re-read, ordinary
 * workspaces must not acquire it, each kind has one row, and the ensure calls
 * must be idempotent because an entry can be tapped twice before the first
 * creation finishes.
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
	fun `ensureWorkshop creates one permanent role-play workspace`() = runBlocking {
		val workshop = repository.ensureWorkshop("RP 工坊")

		assertEquals(WorkspaceKinds.ROLEPLAY, workshop.kind)
		assertTrue(workshop.isPermanent)
		assertEquals("RP 工坊", workshop.name)
		assertNotNull(repository.workspaceRecord(workshop.id))
		assertEquals(WorkspaceKinds.ROLEPLAY, repository.workshop()?.kind)
	}

	@Test
	fun `ensureWorkshop is idempotent and never replaces the first row`() = runBlocking {
		val first = repository.ensureWorkshop("RP 工坊")
		val second = repository.ensureWorkshop("Another name")

		assertEquals(first.id, second.id)
		assertEquals("RP 工坊", repository.workshop()?.name)
	}

	@Test
	fun `a scene can be created inside the workshop workspace`() = runBlocking {
		val workshop = repository.ensureWorkshop("RP 工坊")

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
	fun `ensureStudio creates one permanent configuration workspace`() = runBlocking {
		val studio = repository.ensureStudio("Config workshop")

		assertEquals(WorkspaceKinds.STUDIO, studio.kind)
		assertTrue(studio.isPermanent)
		assertEquals("Config workshop", studio.name)
		assertEquals(WorkspaceKinds.STUDIO, repository.studio()?.kind)
		assertEquals(null, repository.workshop())
	}

	@Test
	fun `ensureStudio is idempotent and never replaces the first row`() = runBlocking {
		val first = repository.ensureStudio("Config workshop")
		val second = repository.ensureStudio("Another name")

		assertEquals(first.id, second.id)
		assertEquals("Config workshop", repository.studio()?.name)
	}

	@Test
	fun `the two built-in kinds are separate rows`() = runBlocking {
		val workshop = repository.ensureWorkshop("RP 工坊")
		val studio = repository.ensureStudio("配置工坊")

		assertNotEquals(workshop.id, studio.id)
		assertEquals(WorkspaceKinds.ROLEPLAY, repository.workshop()?.kind)
		assertEquals(WorkspaceKinds.STUDIO, repository.studio()?.kind)
		// Each lookup must not mistake the other kind for its own, and an
		// ordinary workspace must never become one of them.
		val notes = repository.createWorkspace("notes")
		assertEquals(3, repository.workspaces().size)
		assertEquals(WorkspaceKinds.NONE, repository.workspaceRecord(notes.id)?.kind)
	}
}
