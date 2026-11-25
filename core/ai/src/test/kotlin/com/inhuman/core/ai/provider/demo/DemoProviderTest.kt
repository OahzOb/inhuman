package com.inhuman.core.ai.provider.demo

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.ToolCall
import com.inhuman.core.ai.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the demo actually sends.
 *
 * ## Why this is worth testing
 *
 * The demo is not a fixture for one test -- it ships, and it is the only way to
 * exercise the tool loop, the approval gate and the declined state without an
 * API key. That makes it the app's fallback evidence path, so a wrong script
 * here is a verification that silently proves nothing: a delete the demo never
 * asked for looks exactly like an approval gate that never fired.
 *
 * The two things most likely to be got wrong are the turn logic and the
 * argument fragments, and both are asserted below.
 */
class DemoProviderTest {

	private val noDelay = DemoProvider(chunkDelayMillis = 0)

	private fun tool(name: String) = ToolSpec(name = name, description = "", parameters = emptyMap())

	private fun request(
		tools: List<String> = listOf("write_file", "delete_file"),
		messages: List<ChatMessage> = listOf(ChatMessage(Role.User, "go")),
	) = ChatRequest(
		model = "demo-model",
		messages = messages,
		tools = tools.map(::tool),
	)

	/** The calls a turn asked for, with their arguments assembled. */
	private suspend fun callsIn(request: ChatRequest): List<ToolCall> {
		val chunks = noDelay.stream(request).toList()
		val names = mutableMapOf<Int, String>()
		val ids = mutableMapOf<Int, String>()
		val args = mutableMapOf<Int, StringBuilder>()

		chunks.forEach { chunk ->
			when (chunk) {
				is StreamChunk.ToolCallStart -> {
					chunk.name?.let { names[chunk.index] = it }
					chunk.id?.let { ids[chunk.index] = it }
					args[chunk.index] = StringBuilder().also { args.putIfAbsent(chunk.index, it) }
				}

				is StreamChunk.ToolCallDelta -> {
					chunk.nameDelta?.let { names[chunk.index] = (names[chunk.index] ?: "") + it }
					chunk.inputDelta?.let { args.getOrPut(chunk.index) { StringBuilder() }.append(it) }
				}

				else -> Unit
			}
		}

		return names.keys.sorted().map { index ->
			ToolCall(
				id = ids[index] ?: "",
				name = names[index] ?: "",
				argumentsJson = args[index]?.toString().orEmpty(),
			)
		}
	}

	@Test
	fun `the demo calls suggest_replies after an answer only when it is offered`() = runTest {
		val offered = request(
			tools = listOf(DemoProvider.SUGGEST_TOOL),
			messages = listOf(ChatMessage(Role.User, "plain")),
		)
		assertEquals(
			listOf(DemoProvider.SUGGEST_TOOL),
			callsIn(offered).map { it.name },
		)

		val notOffered = request(
			tools = emptyList(),
			messages = listOf(ChatMessage(Role.User, "plain")),
		)
		assertTrue(callsIn(notOffered).isEmpty())
	}

	@Test
	fun `the suggestion request is answered with the emit tool or with text`() = runTest {
		val toolRequest = request(
			tools = listOf(DemoProvider.EMIT_TOOL),
			messages = listOf(
				ChatMessage(Role.System, "suggest"),
				ChatMessage(Role.User, "[suggest_replies]\nQuestion:\nq\n\nAnswer:\na"),
			),
		)
		val toolCalls = callsIn(toolRequest)
		assertEquals(listOf(DemoProvider.EMIT_TOOL), toolCalls.map { it.name })
		assertTrue(toolCalls.single().argumentsJson.contains("Continue"))

		val textRequest = request(
			tools = emptyList(),
			messages = listOf(
				ChatMessage(Role.System, "suggest"),
				ChatMessage(Role.User, "[suggest_replies]\nQuestion:\nq\n\nAnswer:\na"),
			),
		)
		val text = noDelay.stream(textRequest).toList()
			.filterIsInstance<StreamChunk.TextDelta>()
			.joinToString("") { it.text }
		assertTrue(text.contains("Continue"))
	}

	@Test
	fun `with tools offered and nothing done yet, the script asks for both calls`() = runTest {
		val calls = callsIn(request())

		assertEquals(listOf("write_file", "delete_file"), calls.map { it.name })
		assertTrue("every call needs an id the tool result can answer", calls.all { it.id.isNotBlank() })
	}

	@Test
	fun `arguments survive being streamed in fragments`() = runTest {
		// The whole point of the fragment path: the assembled string has to be
		// valid JSON, because the view model parses it once and a fragment on
		// its own never is.
		val calls = callsIn(request())

		val write = Json.parseToJsonElement(calls[0].argumentsJson) as JsonObject
		assertEquals("demo/notes.txt", write["path"].toString().trim('"'))
		assertNotNull("the write has to carry content", write["content"])

		val delete = Json.parseToJsonElement(calls[1].argumentsJson) as JsonObject
		assertEquals("demo/notes.txt", delete["path"].toString().trim('"'))
	}

	@Test
	fun `the delete targets the file the write just made`() = runTest {
		// A delete of a path that was never created would raise the approval
		// dialog and then fail, which reads as a broken gate rather than a
		// working one.
		val calls = callsIn(request())
		val write = Json.parseToJsonElement(calls[0].argumentsJson) as JsonObject
		val delete = Json.parseToJsonElement(calls[1].argumentsJson) as JsonObject

		assertEquals(write["path"], delete["path"])
	}

	@Test
	fun `a turn that asked for tools does not claim to have finished`() = runTest {
		val finish = noDelay.stream(request()).toList().filterIsInstance<StreamChunk.Finish>().single()

		assertEquals(FinishReason.ToolCalls, finish.reason)
	}

	@Test
	fun `once a tool has answered, the script talks instead of calling again`() = runTest {
		// This is the turn that follows the approval decision, and getting it
		// wrong is an infinite loop: the same calls asked for again, forever.
		val afterTools = listOf(
			ChatMessage(Role.User, "go"),
			ChatMessage(
				Role.Assistant,
				"",
				toolCalls = listOf(ToolCall("demo-call-0", "write_file", """{"path":"demo/notes.txt"}""")),
			),
			ChatMessage(Role.Tool, "Created demo/notes.txt (22 B, 1 lines)", toolCallId = "demo-call-0"),
		)

		assertTrue(callsIn(request(messages = afterTools)).isEmpty())

		val finish = noDelay.stream(request(messages = afterTools)).toList()
			.filterIsInstance<StreamChunk.Finish>().single()
		assertEquals(FinishReason.Stop, finish.reason)
	}

	@Test
	fun `an assistant with file tools switched off is offered no calls at all`() = runTest {
		// The app filters by permission before the provider ever sees the
		// request, so a demo that ignored the list would be demonstrating a
		// capability the assistant does not have.
		assertTrue(callsIn(request(tools = emptyList())).isEmpty())
	}

	@Test
	fun `only the tools actually offered are called`() = runTest {
		val calls = callsIn(request(tools = listOf("delete_file")))

		assertEquals(listOf("delete_file"), calls.map { it.name })
	}

	@Test
	fun `a request with no user message answers nothing`() = runTest {
		assertTrue(callsIn(request(messages = emptyList())).isEmpty())
	}

	@Test
	fun `a role-play scene gets the scene script and no file calls`() = runTest {
		// A continuous scene always carries recall and unavailable_tools, so
		// "no tools offered" cannot be the trigger. The system marker is what
		// separates a story from a work turn, and file tools must not fire
		// while the script is telling one.
		val scene = request(
			tools = listOf("write_file", "delete_file", "recall"),
			messages = listOf(
				ChatMessage(Role.System, "[ROLE-PLAY RULES]\nStay in character as Aster."),
				ChatMessage(Role.User, "I step out of the rain."),
			),
		)

		val chunks = noDelay.stream(scene).toList()
		val text = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
		assertTrue(text.contains("You are late"))
		assertTrue("the script names itself a demo", text.contains("Demo mode"))
		assertTrue(callsIn(scene).isEmpty())
		assertEquals(
			FinishReason.Stop,
			chunks.filterIsInstance<StreamChunk.Finish>().single().reason,
		)
	}
}
