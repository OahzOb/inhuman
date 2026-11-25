package com.inhuman.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MaintenanceRunEntity
import com.inhuman.core.data.db.MaintenanceRunKind
import com.inhuman.core.data.db.MaintenanceRunOutcome
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.SessionMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The repositories on a plain JVM.
 *
 * These used to be Android-only: they read `context.filesDir` and opened the
 * database from a `Context`, so nothing could exercise them without a device.
 * The roots are injected now, and this is the evidence that the same code opens
 * a real Room database and the same directory layout without Android.
 */
class ConversationRepositoryTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var db: InhumanDatabase
	private lateinit var paths: DataPaths
	private lateinit var repository: ConversationRepository

	@Before
	fun setUp() {
		db = Room.inMemoryDatabaseBuilder<InhumanDatabase>()
			.setDriver(BundledSQLiteDriver())
			.build()
		paths = DataPaths(tmp.newFolder("files"))
		repository = ConversationRepository(db, paths)
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun `a conversation owns a temporary workspace directory`() = runBlocking {
		val conversation = repository.create(title = "JVM conversation")

		assertTrue(paths.workspaceDir(conversation.workspaceId).isDirectory)
		assertTrue(File(paths.workspacesRoot, conversation.workspaceId).isDirectory)

		repository.delete(conversation)

		assertFalse(paths.workspaceDir(conversation.workspaceId).exists())
		assertNull(db.conversations().byId(conversation.id))
	}

	@Test
	fun `a permanent workspace survives its conversation`() = runBlocking {
		val workspace = repository.createWorkspace("Shared")
		val directory = paths.workspaceDir(workspace.id)
		val conversation = repository.create(title = "One", workspaceId = workspace.id)

		repository.delete(conversation)

		assertTrue(directory.isDirectory)
		assertEquals(workspace.id, db.workspaces().byId(workspace.id)?.id)
	}

	@Test
	fun `messages append in transcript order`() = runBlocking {
		val conversation = repository.create(title = "Order")
		val user = message("m-user", conversation.id, null, "user", "hello", 1L)
		val assistant = message("m-assistant", conversation.id, user.id, "assistant", "hi", 2L)

		repository.appendMessage(user, null)
		repository.appendMessage(assistant, user.id)

		val loaded = repository.conversation(conversation.id)
		assertEquals(listOf("m-user", "m-assistant"), loaded?.messages?.map { it.id })
	}

	@Test
	fun `a scene starts with no tools a scene value and the greeting stored as its root`() = runBlocking {
		val sceneJson = """{"world":"The sea does not recede."}"""
		val conversation = repository.create(
			title = "Aster",
			tools = emptyList(),
			sceneJson = sceneJson,
			greeting = "  Aster looks up. \"You are late.\"  ",
		)

		val loaded = repository.conversation(conversation.id)!!
		// "[]" is an explicit empty selection; "" would mean every tool, which
		// is the opposite of the scene default.
		assertEquals("[]", loaded.entity.toolsJson)
		assertEquals(sceneJson, loaded.entity.sceneJson)
		// The greeting is the character actually speaking: one assistant root,
		// no fabricated user row, trimmed. The user's first message becomes its
		// child when the chat screen sends it.
		val greeting = loaded.messages.single()
		assertEquals("assistant", greeting.role)
		assertNull(greeting.parentId)
		assertEquals("Aster looks up. \"You are late.\"", greeting.content)
		// A greeting is a finished turn: a null finish reason reads as an
		// interrupted one and puts a Continue banner over a fresh scene.
		assertEquals("stop", greeting.finishReason)
	}

	@Test
	fun `an ordinary conversation has no scene and no greeting row`() = runBlocking {
		val conversation = repository.create(title = "Plain")

		val loaded = repository.conversation(conversation.id)!!
		assertEquals("", loaded.entity.sceneJson)
		assertTrue(loaded.messages.isEmpty())
	}

	@Test
	fun `a blank greeting is not written as an empty assistant row`() = runBlocking {
		val conversation = repository.create(title = "Blank", greeting = "   ")

		assertTrue(repository.conversation(conversation.id)!!.messages.isEmpty())
	}

	@Test
	fun `deleting a subtree is one transaction on the JVM`() = runBlocking {
		val conversation = repository.create(title = "Rewind")
		val user = message("t-user", conversation.id, null, "user", "hello", 1L)
		val reply = message("t-reply", conversation.id, user.id, "assistant", "hi", 2L)
		repository.appendMessage(user, null)
		repository.appendMessage(reply, user.id)

		repository.deleteSubtree(user.id, null, conversation.id)

		assertTrue(repository.conversation(conversation.id)?.messages.orEmpty().isEmpty())
	}

	@Test
	fun `switching provider clears the model and reasoning choices`() = runBlocking {
		val conversation = repository.create(title = "Switch")
		repository.setModel(conversation.id, "old-provider-model")
		repository.setReasoning(conversation.id, "high", thinking = true)

		repository.setProvider(conversation.id, "another-provider")

		val row = db.conversations().byId(conversation.id)
		assertEquals("another-provider", row?.providerId)
		assertNull(row?.model)
		assertNull(row?.reasoningEffort)
		assertFalse(row?.thinking ?: true)
	}

	@Test
	fun `usage rows keep the counting fields and the conversation title`() = runBlocking {
		val conversation = repository.create(title = "Usage test")
		repository.appendMessage(
			MessageEntity(
				id = "usage-assistant",
				conversationId = conversation.id,
				parentId = null,
				role = "assistant",
				content = "answer",
				createdAt = 10L,
				promptTokens = 100,
				completionTokens = 20,
				cachedPromptTokens = 40,
				reasoningTokens = 5,
			),
			null,
		)
		repository.appendMessage(
			MessageEntity(
				id = "usage-tool",
				conversationId = conversation.id,
				parentId = "usage-assistant",
				role = "tool",
				content = "Search results",
				createdAt = 11L,
				toolCallId = "call-1",
				toolName = "web_search",
			),
			"usage-assistant",
		)
		repository.appendMessage(
			MessageEntity(
				id = "usage-user",
				conversationId = conversation.id,
				parentId = "usage-tool",
				role = "user",
				content = "hello",
				createdAt = 12L,
			),
			"usage-tool",
		)

		val rows = repository.usageRows()

		assertEquals(2, rows.size)
		val assistant = rows.single { it.promptTokens != null }
		assertEquals("Usage test", assistant.conversationTitle)
		assertEquals(100, assistant.promptTokens)
		assertEquals(20, assistant.completionTokens)
		assertEquals(40, assistant.cachedPromptTokens)
		assertEquals(5, assistant.reasoningTokens)
		val tool = rows.single { it.toolName != null }
		assertEquals("web_search", tool.toolName)
		assertEquals("Search results", tool.resultPrefix)
	}

	/**
	 * The three states the column has to keep apart: not pinned (null), pinned
	 * with no file (`""`), and pinned with a block. Flattening the first two
	 * would either pin a file added later or re-read the workspace every turn.
	 */
	@Test
	fun `workspace instructions pin, replace and clear`() = runBlocking {
		val conversation = repository.create(title = "Pinned instructions")

		assertNull(db.conversations().byId(conversation.id)?.workspaceInstructions)

		repository.setWorkspaceInstructions(conversation.id, "--- BEGIN AGENTS.md ---\nuse tabs\n--- END AGENTS.md ---")
		assertEquals(
			"--- BEGIN AGENTS.md ---\nuse tabs\n--- END AGENTS.md ---",
			db.conversations().byId(conversation.id)?.workspaceInstructions,
		)

		repository.setWorkspaceInstructions(conversation.id, "")
		assertEquals("", db.conversations().byId(conversation.id)?.workspaceInstructions)

		repository.setWorkspaceInstructions(conversation.id, null)
		assertNull(db.conversations().byId(conversation.id)?.workspaceInstructions)
	}

	@Test
	fun `search follows the branch the transcript shows`() = runBlocking {
		val conversation = repository.create(title = "Branches")
		val user = message("b-user", conversation.id, null, "user", "question", 1L)
		repository.appendMessage(user, null)

		// Two alternatives at the same point. The second is selected, so the
		// first is not on screen.
		val first = message("b-first", conversation.id, user.id, "assistant", "needle in this answer", 2L)
		repository.appendMessage(first, user.id)
		val second = message("b-second", conversation.id, user.id, "assistant", "hay in this answer", 3L)
		repository.appendMessage(second, user.id)

		// A hit on the branch that is not displayed must not be offered: opening
		// it would show a transcript where the jumped-to message does not exist.
		assertTrue(repository.search("needle").isEmpty())
		assertEquals(listOf("b-second"), repository.search("hay").map { it.messageId })

		repository.selectBranch(user.id, first.id)
		assertEquals(listOf("b-first"), repository.search("needle").map { it.messageId })

		// The structural projection and the full transcript walk agree.
		assertEquals(
			listOf("b-user", "b-first"),
			repository.turnRows(conversation.id).map { it.id },
		)
	}

	@Test
	fun `a new conversation is classic unless the caller says otherwise`() = runBlocking {
		val classic = repository.create(title = "Classic")
		assertEquals(SessionMode.CLASSIC, db.conversations().byId(classic.id)?.sessionMode)

		val continuous = repository.create(
			title = "Continuous",
			sessionMode = SessionMode.CONTINUOUS,
		)
		assertEquals(SessionMode.CONTINUOUS, db.conversations().byId(continuous.id)?.sessionMode)

		// A typo must not turn automatic maintenance on: unknown reads classic.
		val typo = repository.create(title = "Typo", sessionMode = "continous")
		assertEquals(SessionMode.CLASSIC, db.conversations().byId(typo.id)?.sessionMode)
	}

	@Test
	fun `changing the session mode is configuration, not activity`() = runBlocking {
		val conversation = repository.create(title = "Mode switch")
		val before = db.conversations().byId(conversation.id)?.updatedAt

		repository.setSessionMode(conversation.id, SessionMode.CONTINUOUS)

		assertEquals(SessionMode.CONTINUOUS, db.conversations().byId(conversation.id)?.sessionMode)
		assertEquals(
			"switching the maintenance mode moved the conversation",
			before,
			db.conversations().byId(conversation.id)?.updatedAt,
		)

		repository.setSessionMode(conversation.id, "play")
		assertEquals(SessionMode.CLASSIC, db.conversations().byId(conversation.id)?.sessionMode)
	}

	@Test
	fun `a continuous brief records its coverage`() = runBlocking {
		val conversation = repository.create(title = "Brief", sessionMode = SessionMode.CONTINUOUS)
		val user = message("b-user", conversation.id, null, "user", "hello", 1L)
		repository.appendMessage(user, null)

		repository.saveContextSummary(
			conversationId = conversation.id,
			throughMessageId = user.id,
			summary = "a brief",
			kind = "auto",
			coveredTurns = 3,
			coveredMessages = 7,
			summaryTokens = 120,
			truncated = true,
		)

		val automatic = repository.contextSummary(conversation.id)
		assertEquals("a brief", automatic?.summary)
		assertEquals("auto", automatic?.kind)
		assertEquals(3, automatic?.coveredTurns)
		assertEquals(7, automatic?.coveredMessages)
		assertEquals(120, automatic?.summaryTokens)
		assertTrue(automatic?.truncated == true)

		// The classic call site keeps working unchanged: manual, no coverage.
		repository.saveContextSummary(conversation.id, user.id, "manual brief")
		val manual = repository.contextSummary(conversation.id)
		assertEquals("manual brief", manual?.summary)
		assertEquals("manual", manual?.kind)
		assertNull(manual?.coveredTurns)
		assertNull(manual?.coveredMessages)
		assertNull(manual?.summaryTokens)
		assertFalse(manual?.truncated ?: true)
	}

	@Test
	fun `maintenance runs survive a rewind and die with the conversation`() = runBlocking {
		val conversation = repository.create(title = "Maintenance")
		val other = repository.create(title = "Other")
		val user = message("run-user", conversation.id, null, "user", "hello", 1L)
		repository.appendMessage(user, null)

		repository.recordMaintenanceRun(run(conversation.id, "run-1", 10L))
		repository.recordMaintenanceRun(run(conversation.id, "run-2", 20L))
		repository.recordMaintenanceRun(run(other.id, "run-3", 30L))

		assertEquals(
			listOf("run-2", "run-1"),
			repository.maintenanceRuns(conversation.id).map { it.id },
		)
		assertEquals(3, repository.allMaintenanceRuns().size)

		// The request was paid for, so rewinding the turn must not erase it.
		repository.deleteSubtree(user.id, null, conversation.id)
		assertEquals(2, repository.maintenanceRuns(conversation.id).size)

		// Deleting the conversation removes its ledger rows, and only its own.
		repository.delete(db.conversations().byId(conversation.id)!!)
		assertTrue(repository.maintenanceRuns(conversation.id).isEmpty())
		assertEquals(listOf("run-3"), repository.allMaintenanceRuns().map { it.id })
	}

	private fun message(
		id: String,
		conversationId: String,
		parentId: String?,
		role: String,
		content: String,
		createdAt: Long,
	) = MessageEntity(
		id = id,
		conversationId = conversationId,
		parentId = parentId,
		role = role,
		content = content,
		createdAt = createdAt,
	)

	private fun run(
		conversationId: String,
		id: String,
		at: Long,
	) = MaintenanceRunEntity(
		id = id,
		conversationId = conversationId,
		at = at,
		kind = MaintenanceRunKind.AUTO,
		model = "demo-model",
		inputTokens = 100,
		outputTokens = 20,
		omittedMessages = 4,
		omittedTurns = 2,
		outcome = MaintenanceRunOutcome.OK,
	)
}
