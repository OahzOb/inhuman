package com.inhuman.core.ai.provider.gemini

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

class GeminiMessagesTest {

	@Test
	fun `request maps contents, system and function declarations`() {
		val body = GeminiWire.requestBody(
			ChatRequest(
				model = "gemini-test",
				messages = listOf(
					ChatMessage(Role.System, "be terse"),
					ChatMessage(Role.User, "list files"),
					ChatMessage(
						Role.Assistant,
						"",
						toolCalls = listOf(
							ToolCall(
								"gemini_0_list_files",
								"list_files",
								"""{"path":"."}""",
								thoughtSignature = "opaque-gemini",
							),
						),
					),
					ChatMessage(Role.Tool, "a.txt", toolCallId = "gemini_0_list_files"),
				),
				tools = listOf(
					ToolSpec(
						name = "list_files",
						description = "List a directory",
						parameters = mapOf("type" to "object"),
					),
				),
			),
		)

		val text = body.toString()
		assertTrue(text, text.contains("\"systemInstruction\""))
		assertTrue(text, text.contains("\"functionDeclarations\""))
		assertTrue(text, text.contains("\"functionCall\""))
		assertTrue(text, text.contains("\"functionResponse\""))
		assertTrue(text, text.contains("list_files"))
		assertTrue(text, text.contains("\"thoughtSignature\":\"opaque-gemini\""))
	}

	@Test
	fun `a function call keeps its thought signature`() {
		val chunks = GeminiDecoder().decode(
			SseFrame(
				event = null,
				data = """
					{"candidates":[{"content":{"parts":[{
					  "functionCall":{"name":"list_files","args":{"path":"."}},
					  "thoughtSignature":"sig-123"}]},"finishReason":"STOP"}]}
				""".trimIndent(),
			),
		)

		val signature = chunks.filterIsInstance<StreamChunk.ToolCallSignature>().single()
		assertEquals(0, signature.index)
		assertEquals("sig-123", signature.signature)
	}

	@Test
	fun `text stream closes text and usage`() {
		val chunks = GeminiDecoder().decode(
			SseFrame(
				event = null,
				data = """
					{"candidates":[{"content":{"parts":[{"text":"Hello"}]},"finishReason":"STOP"}],
					 "usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":2,"totalTokenCount":9}}
				""".trimIndent(),
			),
		)

		assertTrue(chunks.toString(), chunks.any { it is StreamChunk.TextStart })
		assertTrue(chunks.toString(), chunks.any { it is StreamChunk.TextDelta && it.text == "Hello" })
		assertTrue(chunks.toString(), chunks.any { it is StreamChunk.TextEnd })
		val usage = chunks.filterIsInstance<StreamChunk.Usage>().single()
		assertEquals(7, usage.promptTokens ?: -1)
		assertEquals(2, usage.completionTokens ?: -1)
		assertEquals(9, usage.totalTokens ?: -1)
		assertEquals(FinishReason.Stop, chunks.filterIsInstance<StreamChunk.Finish>().single().reason)
	}

	@Test
	fun `function call becomes a complete tool call`() {
		val chunks = GeminiDecoder().decode(
			SseFrame(
				event = null,
				data = """
					{"candidates":[{"content":{"parts":[{"functionCall":
					{"name":"list_files","args":{"path":"."}}}]},"finishReason":"STOP"}]}
				""".trimIndent(),
			),
		)

		val start = chunks.filterIsInstance<StreamChunk.ToolCallStart>().single()
		assertEquals("gemini_0_list_files", start.id)
		assertEquals("list_files", start.name)
		assertEquals(
			"""{"path":"."}""",
			chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
				.mapNotNull { it.inputDelta }
				.joinToString(""),
		)
		assertTrue(chunks.any { it is StreamChunk.ToolCallEnd })
		assertEquals(
			FinishReason.ToolCalls,
			chunks.filterIsInstance<StreamChunk.Finish>().single().reason,
		)
	}

	@Test
	fun `sampling parameters join generationConfig next to temperature`() {
		val body = GeminiWire.requestBody(
			ChatRequest(
				model = "gemini-test",
				messages = listOf(ChatMessage(Role.User, "hi")),
				temperature = 0.2,
				topP = 0.9,
				maxTokens = 512,
			),
		)

		val generation = body["generationConfig"]!!.jsonObject
		assertEquals("0.2", generation["temperature"]?.toString())
		// Gemini's wire name is camelCase, unlike the OpenAI-compatible
		// adapters' `top_p`; writing the other spelling is silently ignored.
		assertEquals("0.9", generation["topP"]?.toString())
		assertTrue("top_p is not Gemini's spelling", generation["top_p"] == null)
		assertEquals("512", generation["maxOutputTokens"]?.toString())
	}

	@Test
	fun `sampling parameters are omitted when unset`() {
		val body = GeminiWire.requestBody(
			ChatRequest(model = "gemini-test", messages = listOf(ChatMessage(Role.User, "hi"))),
		)

		val generation = body["generationConfig"]!!.jsonObject
		// An omitted field means "model default"; maxOutputTokens has to stay
		// present because that is the adapter's documented fallback.
		assertTrue(generation["temperature"] == null)
		assertTrue(generation["topP"] == null)
		assertEquals("4096", generation["maxOutputTokens"]?.toString())
	}
}
