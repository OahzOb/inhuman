package com.inhuman.ui.chat

import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggestion tool's two shapes and its failure behaviour.
 *
 * The tool is the one place a second model call happens, so what it does with
 * an empty answer, a text answer, a tool call and a provider failure is the
 * whole contract. "No suggestions" is a success here: the turn has already been
 * answered, and a suggestion that did not arrive must not become a failed tool
 * row.
 */
class SuggestRepliesToolTest {

	private class FakeProvider(private val chunks: List<StreamChunk>) : ChatProvider {
		override val id: String = "fake"
		val requests = mutableListOf<ChatRequest>()

		override suspend fun listModels(): List<RemoteModel> = emptyList()

		override fun stream(request: ChatRequest): Flow<StreamChunk> = flow {
			requests += request
			chunks.forEach { emit(it) }
		}
	}

	private fun scratch(answer: String = "the answer", question: String = "the question") =
		TurnScratch().apply {
			this.answer = answer
			lastUserMessage = question
		}

	private fun text(vararg lines: String): List<StreamChunk> = buildList {
		lines.forEach { add(StreamChunk.TextDelta("$it\n")) }
		add(StreamChunk.Finish(FinishReason.Stop, "r", "m"))
	}

	private fun tool(name: String, arguments: String): List<StreamChunk> = listOf(
		StreamChunk.ToolCallStart(index = 0, id = "call-0", name = name),
		StreamChunk.ToolCallDelta(index = 0, inputDelta = arguments),
		StreamChunk.ToolCallEnd(index = 0),
		StreamChunk.Finish(FinishReason.ToolCalls, "r", "m"),
	)

	private fun execute(
		provider: ChatProvider,
		format: SuggestionFormat,
		scratch: TurnScratch = scratch(),
	): String = runBlocking {
		SuggestRepliesTool(
			provider = provider,
			model = "cheap-model",
			prompt = "prompt",
			format = format,
			scratch = scratch,
		).execute(JsonObject(emptyMap())).content
	}

	@Test
	fun `text format sanitises the lines`() {
		val provider = FakeProvider(text("Continue", "- Explain it", "", "2. Try another", "four"))

		val content = execute(provider, SuggestionFormat.Text)

		assertEquals(
			listOf("Continue", "Explain it", "Try another"),
			Advice.fromJson(content),
		)
	}

	@Test
	fun `tool format reads the emit tool before the text`() {
		val provider = FakeProvider(
			tool(
				EMIT_SUGGESTIONS_TOOL,
				"""{"suggestions":["Continue","Explain the second point"]}""",
			),
		)

		val content = execute(provider, SuggestionFormat.Tool)

		assertEquals(
			listOf("Continue", "Explain the second point"),
			Advice.fromJson(content),
		)
	}

	@Test
	fun `a model that ignores the tool falls back to its text`() {
		// The owner accepted that the tool shape is not guaranteed; a model that
		// answers in prose should still produce chips rather than nothing.
		val provider = FakeProvider(text("Continue"))

		val content = execute(provider, SuggestionFormat.Tool)

		assertEquals(listOf("Continue"), Advice.fromJson(content))
	}

	@Test
	fun `the request asks for the chosen shape and disables thinking`() {
		val textProvider = FakeProvider(text("Continue"))
		execute(textProvider, SuggestionFormat.Text)
		assertTrue(textProvider.requests.single().tools.isEmpty())
		assertEquals(false, textProvider.requests.single().thinking)

		val toolProvider = FakeProvider(text("Continue"))
		execute(toolProvider, SuggestionFormat.Tool)
		assertEquals(
			listOf(EMIT_SUGGESTIONS_TOOL),
			toolProvider.requests.single().tools.map { it.name },
		)
	}

	@Test
	fun `an empty answer sends no request`() {
		val provider = FakeProvider(text("Continue"))

		val content = execute(provider, SuggestionFormat.Text, scratch(answer = "  "))

		assertTrue(Advice.fromJson(content).isEmpty())
		assertTrue(provider.requests.isEmpty())
	}

	@Test
	fun `a provider failure is an empty result, not a failed call`() {
		val provider = FakeProvider(
			listOf(StreamChunk.Failure("nope"), StreamChunk.Finish(FinishReason.Stop, "r", "m")),
		)

		val content = execute(provider, SuggestionFormat.Tool)

		assertTrue(Advice.fromJson(content).isEmpty())
	}
}
