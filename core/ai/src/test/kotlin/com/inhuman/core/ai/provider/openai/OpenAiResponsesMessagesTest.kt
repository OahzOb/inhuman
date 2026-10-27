package com.inhuman.core.ai.provider.openai

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.ToolCall
import com.inhuman.core.ai.ToolSpec
import com.inhuman.core.ai.stream.SseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlinx.serialization.json.jsonObject

/**
 * The Responses wire is typed input items, not Chat Completions. These tests pin
 * the translation the engine depends on: one user message with text and an
 * image, assistant text plus function-call items, tool outputs, and the
 * function-call event stream mapped back to the neutral start/delta/end shape.
 */
class OpenAiResponsesMessagesTest {

	@Test
	fun `request maps instructions, typed items and tools`() {
		val image = byteArrayOf(1, 2, 3)
		val body = OpenAiResponsesWire.requestBody(
			ChatRequest(
				model = "gpt-test",
				messages = listOf(
					ChatMessage(Role.System, "be terse"),
					ChatMessage(Role.User, "look", images = listOf(com.inhuman.core.ai.ChatImage("image/png", image))),
					ChatMessage(
						Role.Assistant,
						"checking",
						toolCalls = listOf(
							ToolCall(
								"call_1",
								"list_files",
								"""{"path":"."}""",
								providerItemId = "fc_1",
							),
						),
					),
					ChatMessage(Role.Tool, "a.txt", toolCallId = "call_1"),
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

		assertEquals("be terse", body["instructions"]?.toString()?.trim('"'))
		assertEquals("false", body["store"]?.toString())
		val input = body["input"].toString()
		assertTrue(input, input.contains("\"type\":\"input_text\""))
		assertTrue(input, input.contains("data:image/png;base64,${Base64.getEncoder().encodeToString(image)}"))
		assertTrue(input, input.contains("\"role\":\"assistant\""))
		assertTrue(input, input.contains("\"type\":\"output_text\""))
		assertTrue(input, input.contains("\"type\":\"function_call\""))
		assertTrue(input, input.contains("\"id\":\"fc_1\""))
		assertTrue(input, input.contains("\"call_id\":\"call_1\""))
		assertTrue(input, input.contains("\"type\":\"function_call_output\""))
		val tools = body["tools"].toString()
		assertTrue(tools, tools.contains("\"type\":\"function\""))
		assertTrue(tools, tools.contains("list_files"))
		assertTrue(tools, tools.contains("\"parameters\""))
	}

	@Test
	fun `text events become start, deltas, end, usage and finish`() {
		val decoder = OpenAiResponsesDecoder()
		val chunks = listOf(
			SseFrame(
				"response.output_item.added",
				"""{"type":"response.output_item.added","item":{"id":"msg_1","type":"message","role":"assistant"}}""",
			),
			SseFrame(
				"response.output_text.delta",
				"""{"type":"response.output_text.delta","item_id":"msg_1","delta":"Hello"}""",
			),
			SseFrame(
				"response.completed",
				"""{"type":"response.completed","response":{"id":"resp_1","model":"gpt-test",
				 "usage":{"input_tokens":7,"output_tokens":2,"total_tokens":9,
				 "input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":1}}}}""",
			),
		).flatMap { decoder.decode(it) }

		assertTrue(chunks.toString(), chunks.any { it is StreamChunk.TextStart })
		assertTrue(chunks.toString(), chunks.any { it is StreamChunk.TextDelta && it.text == "Hello" })
		assertTrue(chunks.toString(), chunks.any { it is StreamChunk.TextEnd })
		val usage = chunks.filterIsInstance<StreamChunk.Usage>().single()
		assertEquals(7, usage.promptTokens ?: -1)
		assertEquals(2, usage.completionTokens ?: -1)
		assertEquals(9, usage.totalTokens ?: -1)
		assertEquals(3, usage.cachedPromptTokens ?: -1)
		assertEquals(1, usage.reasoningTokens ?: -1)
		val finish = chunks.filterIsInstance<StreamChunk.Finish>().single()
		assertEquals(FinishReason.Stop, finish.reason)
		assertEquals("resp_1", finish.responseId)
	}

	@Test
	fun `function call events keep the call id and arguments`() {
		val decoder = OpenAiResponsesDecoder()
		val chunks = listOf(
			SseFrame(
				"response.output_item.added",
				"""{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call",
				 "call_id":"call_1","name":"list_files","arguments":""}}""",
			),
			SseFrame(
				"response.function_call_arguments.delta",
				"""{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"path\":"}""",
			),
			SseFrame(
				"response.function_call_arguments.delta",
				"""{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\".\"}"}""",
			),
			SseFrame(
				"response.function_call_arguments.done",
				"""{"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\"path\":\".\"}"}""",
			),
			SseFrame(
				"response.completed",
				"""{"type":"response.completed","response":{"id":"resp_2","model":"gpt-test"}}""",
			),
		).flatMap { decoder.decode(it) }

		val start = chunks.filterIsInstance<StreamChunk.ToolCallStart>().single()
		assertEquals("call_1", start.id)
		assertEquals("fc_1", start.itemId)
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
	fun `reasoning state is requested, replayed and collected`() {
		val state = """[{"type":"reasoning","id":"rs_1","encrypted_content":"sealed"}]"""
		val body = OpenAiResponsesWire.requestBody(
			ChatRequest(
				model = "gpt-test",
				messages = listOf(
					ChatMessage(Role.User, "go"),
					ChatMessage(
						Role.Assistant,
						"",
						providerState = state,
						toolCalls = listOf(
							ToolCall(
								"call_1",
								"list_files",
								"""{"path":"."}""",
								providerItemId = "fc_1",
							),
						),
					),
					ChatMessage(Role.Tool, "a.txt", toolCallId = "call_1"),
				),
				thinking = true,
				reasoningEffort = "high",
				temperature = 0.2,
			),
		)

		assertEquals("high", body["reasoning"]?.jsonObject?.get("effort")?.toString()?.trim('"'))
		assertTrue(
			"encrypted reasoning must be requested with store=false: $body",
			body["include"].toString().contains("reasoning.encrypted_content"),
		)
		val input = body["input"].toString()
		assertTrue(input, input.contains("\"encrypted_content\":\"sealed\""))
		assertTrue("temperature is not sent with reasoning", body["temperature"] == null)

		val chunks = OpenAiResponsesDecoder().decode(
			SseFrame(
				"response.output_item.done",
				"""{"type":"response.output_item.done","item":{"id":"rs_2","type":"reasoning",
				 "encrypted_content":"sealed-2"}}""",
			),
		)
		val stateChunk = chunks.filterIsInstance<StreamChunk.ProviderState>().single()
		assertTrue(stateChunk.json.contains("sealed-2"))
	}

	@Test
	fun `a failed response becomes a failure`() {
		val chunks = OpenAiResponsesDecoder().decode(
			SseFrame(
				"response.failed",
				"""{"type":"response.failed","response":{"error":{"code":"server_error","message":"Overloaded"}}}""",
			),
		)

		assertEquals("Overloaded", (chunks.single() as StreamChunk.Failure).message)
	}

	@Test
	fun `a base url without v1 still reaches the responses endpoint`() {
		assertEquals(
			"https://api.openai.com/v1/responses",
			OpenAiResponsesWire.endpoint("https://api.openai.com", "/responses"),
		)
		assertEquals(
			"https://api.openai.com/v1/responses",
			OpenAiResponsesWire.endpoint("https://api.openai.com/v1", "/responses"),
		)
	}

	@Test
	fun `an empty response closes with a finish`() {
		val chunks = OpenAiResponsesDecoder().endOfStream()

		assertEquals(FinishReason.Unknown, chunks.filterIsInstance<StreamChunk.Finish>().single().reason)
	}
}