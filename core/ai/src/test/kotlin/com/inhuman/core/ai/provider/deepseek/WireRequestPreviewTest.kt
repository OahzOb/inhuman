package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.ToolSpec
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inspector must show the real body, so this pins the provider's preview to
 * the fields `stream` sends: composed system text, tool schema, thinking mode,
 * cache-isolation user id and the streamed usage request.
 */
class WireRequestPreviewTest {

	private val provider = DeepSeekProvider(apiKey = "sk-test")

	@Test
	fun previewCarriesTheComposedSystemAndToolSchema() {
		val request = ChatRequest(
			model = "deepseek-chat",
			messages = listOf(
				ChatMessage(Role.System, "You are a helpful assistant."),
				ChatMessage(Role.User, "hello"),
			),
			tools = listOf(
				ToolSpec(
					name = "read_file",
					description = "Read a file.",
					parameters = mapOf(
						"type" to "object",
						"properties" to mapOf(
							"path" to mapOf("type" to "string", "description" to "Relative path"),
						),
						"required" to listOf("path"),
					),
				),
			),
			thinking = false,
			reasoningEffort = "high",
			userId = "conversation-1",
		)

		val body = provider.previewRequest(request)!!
		val messages = body["messages"]!!.jsonArray
		assertEquals("You are a helpful assistant.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
		assertEquals("hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
		val tool = body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
		assertEquals("read_file", tool["name"]!!.jsonPrimitive.content)
		assertEquals("Read a file.", tool["description"]!!.jsonPrimitive.content)
		assertTrue(tool["parameters"].toString().contains("\"path\""))
		assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
		assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
		assertEquals("conversation-1", body["user_id"]!!.jsonPrimitive.content)
		assertTrue(body.containsKey("stream_options"))
	}
}
