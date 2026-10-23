package com.inhuman.core.engine

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.tools.FileTools
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatEngineTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `demo turn executes two file tools and answers`() = runBlocking {
		val workspace = SessionWorkspace.open(tmp.newFolder("ws"))
		val tools = FileTools.all(workspace)
		val engine = ChatEngine(DemoProvider(chunkDelayMillis = 0))
		val events = engine.run(
			EngineTurn(
				model = "demo-model",
				messages = listOf(ChatMessage(Role.User, "create and then delete a file")),
				tools = tools,
				thinking = false,
			),
		).toList()

		val results = events.filterIsInstance<EngineEvent.ToolCallResult>()
		assertEquals(2, results.size)
		assertTrue(results.none { it.result.isError })
		assertTrue(results.any { it.call.name == "write_file" })
		assertTrue(results.any { it.call.name == "delete_file" })
		assertTrue(events.any { it is EngineEvent.Finished })
		assertTrue(events.none { it is EngineEvent.Failed })
	}

	@Test
	fun `declined call is recorded and stops the turn`() = runBlocking {
		val workspace = SessionWorkspace.open(tmp.newFolder("declined"))
		val engine = ChatEngine(
			DemoProvider(chunkDelayMillis = 0),
			approval = EngineApproval { _, _ -> false },
		)
		val events = engine.run(
			EngineTurn(
				model = "demo-model",
				messages = listOf(ChatMessage(Role.User, "create a file")),
				tools = FileTools.all(workspace),
				thinking = false,
			),
		).toList()

		val result = events.filterIsInstance<EngineEvent.ToolCallResult>().single()
		assertTrue(result.result.isError)
		assertTrue(result.result.content.contains("declined"))
		assertTrue(events.any { it is EngineEvent.Finished })
		assertFalse(File(workspace.rootPath(), "demo/hello.txt").exists())
	}

	@Test
	fun `plain demo answer streams without tools`() = runBlocking {
		val engine = ChatEngine(DemoProvider(chunkDelayMillis = 0))
		val events = engine.run(
			EngineTurn(
				model = "demo-model",
				messages = listOf(ChatMessage(Role.User, "plain")),
				tools = FileTools.all(SessionWorkspace.open(tmp.newFolder("plain"))),
				thinking = false,
			),
		).toList()

		assertTrue(events.any { it is EngineEvent.TextDelta })
		assertTrue(events.any { it is EngineEvent.AssistantMessage })
		assertTrue(events.any { it is EngineEvent.Finished })
		assertTrue(events.none { it is EngineEvent.ToolCallResult })
	}
}
