package com.inhuman.desktop

import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.data.db.ToolCallsColumn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop tool loop, end to end, on the demo provider.
 *
 * This is the test the window cannot give: it checks the rows written between
 * rounds -- the assistant `tool_calls`, the tool result, the closing answer --
 * and that the next request would have both halves available. A UI screenshot
 * only shows the final sentence.
 */
class DesktopChatTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `demo tool loop persists calls results and the final answer`() = runBlocking {
		val store = DesktopStore.open(tmp.newFolder("chat"))
		try {
			val conversation = store.create("Tool loop")
			val assistant = store.defaultAssistant()
			assertNotNull("the seed callback must create an assistant", assistant)

			val events = mutableListOf<DesktopChat.Event>()
			val failure = DesktopChat(store, conversation.id, assistant).send(
				userText = "make a file and remove it",
				provider = DemoProvider(chunkDelayMillis = 0),
				model = "demo-model",
			) { events += it }

			assertNull(failure)
			val messages = store.messages(conversation.id)
			assertTrue("the user message is persisted first", messages.any { it.role == "user" })

			val withCalls = messages.filter { it.role == "assistant" && !it.toolCallsJson.isNullOrBlank() }
			assertTrue("the assistant call row is persisted", withCalls.isNotEmpty())
			assertTrue(
				"the call row decodes to the demo's write_file",
				ToolCallsColumn.decode(withCalls.first().toolCallsJson).any { it.name == "write_file" },
			)

			assertTrue(
				"the write result is a separate tool row",
				messages.any { it.role == "tool" && it.toolName == "write_file" },
			)
			assertTrue(
				"the delete result is a separate tool row",
				messages.any { it.role == "tool" && it.toolName == "delete_file" },
			)

			val finished = events.filterIsInstance<DesktopChat.Event.AssistantFinished>()
			assertTrue("at least the tool round and the closing round finished", finished.size >= 2)
			assertTrue("the closing answer is non-empty", finished.last().line.text.isNotBlank())

			// The next turn has to be able to replay the assistant tool_calls and
			// their results. If the history skipped either half, a real provider
			// would answer 400 even though the demo accepts anything.
			val followUp = DesktopChat(store, conversation.id, assistant).send(
				userText = "plain",
				provider = DemoProvider(chunkDelayMillis = 0),
				model = "demo-model",
			) {}
			assertNull("a follow-up after a tool loop must send", followUp)
			assertEquals(2, store.messages(conversation.id).count { it.role == "user" })
		} finally {
			store.close()
		}
	}
}
