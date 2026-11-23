package com.inhuman.ui.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallToolTest {

	private val branch = listOf(
		message("u1", fromUser = true, "我们决定把发布推迟到十一月。"),
		message("a1", fromUser = false, "好的，那我把日期改成十一月。"),
		message("t1", fromUser = false, "file written", toolResult = true),
		message("u2", fromUser = true, "继续说说发布计划。"),
		message("a2", fromUser = false, "发布计划需要先过审。"),
	)

	private fun tool(list: List<ChatMessageUi> = branch) = RecallTool { list }

	@Test
	fun `search finds messages and reports the turn number`() = runBlocking {
		val result = tool().execute(buildJsonObject { put("query", "发布计划") })
		assertFalse(result.isError)
		assertTrue(result.content.contains("id=a2"))
		assertTrue(result.content.contains("turn=2"))
		assertTrue(result.content.contains("id=u2"))
		assertTrue("most recent first", result.content.indexOf("a2") < result.content.indexOf("u2"))
		assertTrue(result.persistedContent!!.contains("2 match"))
		assertTrue("the full text is not persisted", result.persistedContent!!.length < result.content.length)
	}

	@Test
	fun `tool results are not part of the searchable record`() = runBlocking {
		val result = tool().execute(buildJsonObject { put("query", "file written") })
		assertTrue(result.content.contains("no message"))
	}

	@Test
	fun `message_id reads one message in full`() = runBlocking {
		val result = tool().execute(buildJsonObject { put("message_id", "a1") })
		assertFalse(result.isError)
		assertTrue(result.content.contains("Message a1 (turn 1, assistant)"))
		assertTrue(result.content.contains("十一月"))
		assertTrue(result.persistedContent!!.contains("a1"))
	}

	@Test
	fun `unknown id is an error that points back at search`() = runBlocking {
		val result = tool().execute(buildJsonObject { put("message_id", "nope") })
		assertTrue(result.isError)
		assertTrue(result.content.contains("Search first"))
	}

	@Test
	fun `limit is clamped and defaults are bounded`() = runBlocking {
		val many = (1..20).map { message("u$it", fromUser = true, "alpha $it") }
		val result = tool(many).execute(buildJsonObject { put("query", "alpha"); put("limit", 99) })
		assertEquals(5, Regex("id=").findAll(result.content).count())
	}

	@Test
	fun `an empty query asks for a query or an id`() = runBlocking {
		val result = tool().execute(buildJsonObject {})
		assertTrue(result.isError)
		assertTrue(result.content.contains("query"))
		assertTrue(result.content.contains("message_id"))
	}

	@Test
	fun `the tool is read-only`() {
		assertFalse(tool().mutates)
	}

	private fun message(
		id: String,
		fromUser: Boolean,
		text: String,
		toolResult: Boolean = false,
	) = ChatMessageUi(
		id = id,
		fromUser = fromUser,
		plainText = text,
		isToolResult = toolResult,
		createdAt = 0L,
	)
}
