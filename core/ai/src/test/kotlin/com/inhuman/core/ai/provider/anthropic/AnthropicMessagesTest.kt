package com.inhuman.core.ai.provider.anthropic

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.ToolCall
import com.inhuman.core.ai.ToolSpec
import com.inhuman.core.ai.stream.SseFrame
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protocol shapes here are the ones Anthropic documents and the ones Pi's
 * adapter tests exercise: a named SSE event stream, block indexes, tool_use /
 * tool_result blocks, and usage split across message_start/message_delta.
 */
class AnthropicMessagesTest {

	@Test
	fun `request maps system, tools and tool results`() {
		val body = AnthropicWire.requestBody(
			ChatRequest(
				model = "claude-test",
				messages = listOf(
					ChatMessage(Role.System, "be terse"),
					ChatMessage(Role.User, "list files"),
					ChatMessage(
						Role.Assistant,
						"checking",
						toolCalls = listOf(
							ToolCall("call_1", "list_files", """{"path":"."}"""),
						),
					),
					ChatMessage(Role.Tool, "a.txt\nb.txt", toolCallId = "call_1"),
				),
				tools = listOf(
					ToolSpec(
						name = "list_files",
						description = "List a directory",
						parameters = mapOf(
							"type" to "object",
							"properties" to mapOf(
								"path" to mapOf("type" to "string"),
							),
						),
					),
				),
			),
		)

		assertEquals("claude-test", body["model"]?.toString()?.trim('"'))
		assertEquals("be terse", body["system"]?.toString()?.trim('"'))
		assertEquals("4096", body["max_tokens"]?.toString())
		val messages = body["messages"].toString()
		assertTrue(messages, messages.contains("\"tool_use\""))
		assertTrue(messages, messages.contains("\"tool_result\""))
		assertTrue(messages, messages.contains("call_1"))
		val tools = body["tools"].toString()
		assertTrue(tools, tools.contains("\"input_schema\""))
		assertTrue(tools, tools.contains("list_files"))
	}

	@Test
	fun `text stream becomes start, deltas, end and finish`() {
		val decoder = AnthropicDecoder()
		val chunks = listOf(
			SseFrame(
				"message_start",
				"""{"type":"message_start","message":{"id":"msg_1","model":"claude-test","usage":{"input_tokens":10,"cache_read_input_tokens":2}}}""",
			),
			SseFrame(
				"content_block_start",
				"""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
			),
			SseFrame(
				"content_block_delta",
				"""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
			),
			SseFrame(
				"content_block_stop",
				"""{"type":"content_block_stop","index":0}""",
			),
			SseFrame(
				"message_delta",
				"""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}""",
			),
			SseFrame("message_stop", """{"type":"message_stop"}"""),
		).flatMap { decoder.decode(it) }

		assertTrue(chunks.toString(), chunks.any { it is StreamChunk.TextStart })
		assertTrue(chunks.toString(), chunks.any { it is StreamChunk.TextDelta && it.text == "Hello" })
		assertTrue(chunks.toString(), chunks.any { it is StreamChunk.TextEnd })
		val usage = chunks.filterIsInstance<StreamChunk.Usage>().single()
		assertEquals(10, usage.promptTokens ?: -1)
		assertEquals(3, usage.completionTokens ?: -1)
		assertEquals(2, usage.cachedPromptTokens ?: -1)
		val finish = chunks.filterIsInstance<StreamChunk.Finish>().single()
		assertEquals(FinishReason.Stop, finish.reason)
		assertEquals("msg_1", finish.responseId)
	}

	@Test
	fun `tool use stream keeps the block index`() {
		val decoder = AnthropicDecoder()
		val chunks = listOf(
			SseFrame(
				"content_block_start",
				"""{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool_1","name":"list_files"}}""",
			),
			SseFrame(
				"content_block_delta",
				"""{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""",
			),
			SseFrame(
				"content_block_delta",
				"""{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\".\"}"}}""",
			),
			SseFrame(
				"content_block_stop",
				"""{"type":"content_block_stop","index":2}""",
			),
			SseFrame(
				"message_delta",
				"""{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":8}}""",
			),
			SseFrame("message_stop", """{"type":"message_stop"}"""),
		).flatMap { decoder.decode(it) }

		val start = chunks.filterIsInstance<StreamChunk.ToolCallStart>().single()
		assertEquals(2, start.index)
		assertEquals("tool_1", start.id)
		assertEquals("list_files", start.name)
		assertEquals(
			"""{"path":"."}""",
			chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
				.mapNotNull { it.inputDelta }
				.joinToString(""),
		)
		assertEquals(2, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().single().index)
		assertEquals(
			FinishReason.ToolCalls,
			chunks.filterIsInstance<StreamChunk.Finish>().single().reason,
		)
	}

	@Test
	fun `signed thinking is requested and replayed`() {
		val body = AnthropicWire.requestBody(
			ChatRequest(
				model = "claude-test",
				thinking = true,
				messages = listOf(
					ChatMessage(Role.User, "list files"),
					ChatMessage(
						Role.Assistant,
						"checking",
						reasoning = "I should list the folder.",
						reasoningSignature = "sig-opaque",
						toolCalls = listOf(ToolCall("call_1", "list_files", """{"path":"."}""")),
					),
					ChatMessage(Role.Tool, "a.txt", toolCallId = "call_1"),
				),
			),
		)

		assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.toString()?.trim('"'))
		assertEquals("2048", body["thinking"]?.jsonObject?.get("budget_tokens")?.toString())
		val messages = body["messages"].toString()
		assertTrue(messages, messages.contains("\"type\":\"thinking\""))
		assertTrue(messages, messages.contains("\"signature\":\"sig-opaque\""))
		assertTrue(messages, messages.contains("I should list the folder."))
	}

	@Test
	fun `an unsigned tool turn falls back to no thinking rather than a 400`() {
		val body = AnthropicWire.requestBody(
			ChatRequest(
				model = "claude-test",
				thinking = true,
				messages = listOf(
					ChatMessage(Role.User, "list files"),
					ChatMessage(
						Role.Assistant,
						"checking",
						toolCalls = listOf(ToolCall("call_1", "list_files", """{"path":"."}""")),
					),
					ChatMessage(Role.Tool, "a.txt", toolCallId = "call_1"),
				),
			),
		)

		assertTrue(body["thinking"] == null)
		assertTrue(body["messages"].toString().contains("\"tool_use\""))
		assertTrue(!body["messages"].toString().contains("\"type\":\"thinking\""))
	}

	@Test
	fun `signature delta is kept apart from thinking text`() {
		val decoder = AnthropicDecoder()
		val chunks = listOf(
			SseFrame(
				"content_block_start",
				"""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
			),
			SseFrame(
				"content_block_delta",
				"""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me check."}}""",
			),
			SseFrame(
				"content_block_delta",
				"""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque"}}""",
			),
			SseFrame("content_block_stop", """{"type":"content_block_stop","index":0}"""),
		).flatMap { decoder.decode(it) }

		assertEquals("Let me check.", chunks.filterIsInstance<StreamChunk.ReasoningDelta>().single().text)
		assertEquals("opaque", chunks.filterIsInstance<StreamChunk.ReasoningSignature>().single().signature)
	}

	@Test
	fun `temperature is omitted while thinking and kept otherwise`() {
		val thinking = AnthropicWire.requestBody(
			ChatRequest(
				model = "claude-test",
				messages = listOf(ChatMessage(Role.User, "hi")),
				thinking = true,
				temperature = 0.2,
			),
		)
		assertTrue(thinking["thinking"] != null)
		assertTrue("thinking rejects a temperature", thinking["temperature"] == null)

		val plain = AnthropicWire.requestBody(
			ChatRequest(
				model = "claude-test",
				messages = listOf(ChatMessage(Role.User, "hi")),
				temperature = 0.2,
			),
		)
		assertTrue(plain["thinking"] == null)
		assertEquals("0.2", plain["temperature"]?.toString())
	}

	@Test
	fun `error event becomes a failure`() {
		val decoder = AnthropicDecoder()
		val chunks = decoder.decode(
			SseFrame(
				"error",
				"""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
			),
		)
		assertEquals("Overloaded", (chunks.single() as StreamChunk.Failure).message)
	}
}
