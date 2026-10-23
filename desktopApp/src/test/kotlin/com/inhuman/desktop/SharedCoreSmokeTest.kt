package com.inhuman.desktop

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.files.SessionWorkspace
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The desktop spike's two non-UI proofs.
 *
 * The first is that the existing provider code streams on a plain JVM with no
 * Android runtime. The second is that the workspace boundary still resolves on
 * the desktop filesystem, including the rejection of a parent traversal.
 */
class SharedCoreSmokeTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `demo provider streams on the JVM`() = runBlocking {
		val chunks = DemoProvider(chunkDelayMillis = 0).stream(
			ChatRequest(
				model = "demo-model",
				messages = listOf(ChatMessage(role = Role.User, content = "hello")),
				thinking = false,
			),
		).toList()

		assertTrue(chunks.any { it is StreamChunk.TextDelta })
		assertTrue(chunks.any { it is StreamChunk.Finish })
	}

	@Test
	fun `workspace resolves and refuses traversal on the desktop`() {
		val root = tmp.newFolder("workspace")
		val workspace = SessionWorkspace.open(root)
		val resolved = workspace.resolve("notes/todo.txt")
		assertTrue(resolved is SessionWorkspace.Resolution.Ok)
		val file = (resolved as SessionWorkspace.Resolution.Ok).file
		file.parentFile.mkdirs()
		file.writeText("desktop")
		assertTrue(file.readText() == "desktop")
		assertTrue(workspace.resolve("../outside.txt") is SessionWorkspace.Resolution.Rejected)
	}

	@Test
	fun `workspace root is a real directory`() {
		val root = tmp.newFolder("workspace2")
		assertTrue(SessionWorkspace.open(root).resolve("").let {
			it is SessionWorkspace.Resolution.Ok
		})
		assertTrue(File(root, ".").isDirectory)
	}
}
