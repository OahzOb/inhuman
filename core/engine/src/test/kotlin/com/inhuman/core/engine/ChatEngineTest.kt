package com.inhuman.core.engine

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.ai.tools.SshArguments
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.tools.FileTools
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
	fun `a policy refusal is a failed result and lets the turn continue`() = runBlocking {
		val workspace = SessionWorkspace.open(tmp.newFolder("declined"))
		val engine = ChatEngine(
			DemoProvider(chunkDelayMillis = 0),
			approval = EngineApproval { _, _ -> EngineDecision.Deny("declined by user") },
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
		assertTrue(events.none { it is EngineEvent.ToolCallDeclined })
		assertTrue(events.any { it is EngineEvent.Finished })
		assertFalse(File(workspace.rootPath(), "demo/hello.txt").exists())
	}

	@Test
	fun `a user refusal is reported as declined, not failed`() = runBlocking {
		val workspace = SessionWorkspace.open(tmp.newFolder("user-declined"))
		val engine = ChatEngine(
			DemoProvider(chunkDelayMillis = 0),
			approval = EngineApproval { _, _ ->
				EngineDecision.Deny("declined by user", stopTurn = true, declined = true)
			},
		)
		val events = engine.run(
			EngineTurn(
				model = "demo-model",
				messages = listOf(ChatMessage(Role.User, "create a file")),
				tools = FileTools.all(workspace),
				thinking = false,
			),
		).toList()

		val declined = events.filterIsInstance<EngineEvent.ToolCallDeclined>().single()
		assertTrue(declined.message.contains("declined"))
		assertTrue(events.none { it is EngineEvent.ToolCallResult })
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

	@Test
	fun `a provider that throws becomes a Failed event`() = runBlocking {
		val throwing = object : ChatProvider {
			override val id = "throwing"
			override suspend fun listModels(): List<RemoteModel> = emptyList()
			override fun stream(request: ChatRequest): Flow<StreamChunk> = flow {
				throw IllegalStateException("transport exploded")
			}
		}
		val events = ChatEngine(throwing).run(
			EngineTurn(
				model = "none",
				messages = listOf(ChatMessage(Role.User, "hello")),
			),
		).toList()

		val failure = events.filterIsInstance<EngineEvent.Failed>().single()
		assertTrue(failure.message.contains("transport exploded"))
		assertEquals(EngineFailure.Provider, failure.cause)
	}

	@Test
	fun `the tool receives normalised arguments`() = runBlocking {
		val tool = RecordingTool(transform = SshArguments::normalize)
		val provider = AnswersAfterOneCall(
			"call-1",
			tool.name,
			"""{"arguments":"{\"host\":\"example.test\",\"username\":\"root\"}"}""",
		)
		ChatEngine(provider).run(
			EngineTurn(
				model = "scripted",
				messages = listOf(ChatMessage(Role.User, "probe")),
				tools = listOf(tool),
			),
		).toList()

		// The provider wrapped the object in a string field; the tool should see
		// the connection fields themselves, not the envelope.
		assertEquals("example.test", tool.seen?.get("host")?.jsonPrimitive?.content)
		assertEquals("root", tool.seen?.get("username")?.jsonPrimitive?.content)
		assertNull(tool.seen?.get("arguments"))
	}

	@Test
	fun `a call in a text-only retry names its cause and does not run`() = runBlocking {
		val tool = RecordingTool()
		val events = ChatEngine(AnswersAfterOneCall("call-1", tool.name, "{}")).run(
			EngineTurn(
				model = "scripted",
				messages = listOf(ChatMessage(Role.User, "answer again")),
				tools = listOf(tool),
				suppressToolCalls = true,
			),
		).toList()

		val failure = events.filterIsInstance<EngineEvent.Failed>().single()
		assertEquals(EngineFailure.ToolCallsSuppressed, failure.cause)
		assertNull(tool.seen)
	}

	@Test
	fun `the call budget names itself when it runs out`() = runBlocking {
		val tool = RecordingTool()
		val events = ChatEngine(AlwaysCalls(tool.name)).run(
			EngineTurn(
				model = "scripted",
				messages = listOf(ChatMessage(Role.User, "loop")),
				tools = listOf(tool),
				maxRounds = 8,
				maxCalls = 1,
			),
		).toList()

		val failure = events.filterIsInstance<EngineEvent.Failed>().single()
		assertEquals(EngineFailure.CallLimit, failure.cause)
		assertEquals(1, tool.executions)
		assertTrue(events.any { it is EngineEvent.ToolCallSkipped })
	}

	@Test
	fun `the round budget names itself when it runs out`() = runBlocking {
		val tool = RecordingTool()
		val events = ChatEngine(AlwaysCalls(tool.name)).run(
			EngineTurn(
				model = "scripted",
				messages = listOf(ChatMessage(Role.User, "loop")),
				tools = listOf(tool),
				maxRounds = 1,
				maxCalls = 10,
			),
		).toList()

		val failure = events.filterIsInstance<EngineEvent.Failed>().single()
		assertEquals(EngineFailure.RoundLimit, failure.cause)
		assertEquals(1, tool.executions)
	}

	@Test
	fun `two calls in one round run one at a time`() = runBlocking {
		// The mobile ceiling is one tool at a time (docs/08 Q4: the desktop's
		// parallel calls are deliberately not ported). This pins the property
		// by measuring overlap, not by reading the loop: a future "run them
		// concurrently" change would raise `maxConcurrent` above one.
		val tool = ConcurrencyTrackingTool()
		val events = ChatEngine(TwoCalls(tool.name)).run(
			EngineTurn(
				model = "scripted",
				messages = listOf(ChatMessage(Role.User, "two calls")),
				tools = listOf(tool),
				maxRounds = 3,
				maxCalls = 4,
			),
		).toList()

		assertEquals(2, tool.executions)
		assertEquals(1, tool.maxConcurrent.get())
		assertTrue(events.any { it is EngineEvent.Finished })
	}

	@Test
	fun `signatures flow from the stream into the next request`() = runBlocking {
		val provider = SignedCall("probe")
		val events = ChatEngine(provider).run(
			EngineTurn(
				model = "scripted",
				messages = listOf(ChatMessage(Role.User, "go")),
				tools = listOf(RecordingTool()),
				maxRounds = 2,
			),
		).toList()

		val assistant = events.filterIsInstance<EngineEvent.AssistantMessage>().first().message
		assertEquals("think-sig", assistant.reasoningSignature)
		assertEquals("call-sig", assistant.toolCalls.single().thoughtSignature)
		assertTrue(assistant.providerState?.contains("sealed-state") == true)

		val sentBack = provider.secondRequest?.messages?.firstOrNull { it.role == Role.Assistant }
		assertEquals("think-sig", sentBack?.reasoningSignature)
		assertEquals("call-sig", sentBack?.toolCalls?.single()?.thoughtSignature)
		assertTrue(sentBack?.providerState?.contains("sealed-state") == true)
	}

	/** Emits one signed call, then captures the request that replayed it. */
	private class SignedCall(private val toolName: String) : ChatProvider {
		override val id = "scripted"
		override suspend fun listModels(): List<RemoteModel> = emptyList()
		var secondRequest: ChatRequest? = null
		private var round = 0

		override fun stream(request: ChatRequest): Flow<StreamChunk> = flow {
			if (round++ == 0) {
				emit(StreamChunk.ReasoningStart())
				emit(StreamChunk.ReasoningDelta("thinking"))
				emit(StreamChunk.ReasoningSignature("think-sig"))
				emit(StreamChunk.ProviderState("""{"type":"reasoning","encrypted_content":"sealed-state"}"""))
				emit(StreamChunk.ReasoningEnd())
				emit(StreamChunk.ToolCallStart(0, "call-1", toolName))
				emit(StreamChunk.ToolCallDelta(0, inputDelta = "{}"))
				emit(StreamChunk.ToolCallEnd(0))
				emit(StreamChunk.ToolCallSignature(0, "call-sig"))
				emit(StreamChunk.Finish(FinishReason.ToolCalls))
			} else {
				secondRequest = request
				emit(StreamChunk.TextDelta("done"))
				emit(StreamChunk.Finish(FinishReason.Stop))
			}
		}
	}

	/** Emits one call, then answers with text on the next request. */
	private class AnswersAfterOneCall(
		private val callId: String,
		private val toolName: String,
		private val callArguments: String,
	) : ChatProvider {
		override val id = "scripted"
		override suspend fun listModels(): List<RemoteModel> = emptyList()
		private var round = 0

		override fun stream(request: ChatRequest): Flow<StreamChunk> = flow {
			if (round++ == 0) {
				emit(StreamChunk.ToolCallStart(0, callId, toolName))
				emit(StreamChunk.ToolCallDelta(0, inputDelta = callArguments))
				emit(StreamChunk.ToolCallEnd(0))
				emit(StreamChunk.Finish(FinishReason.ToolCalls))
			} else {
				emit(StreamChunk.TextDelta("done"))
				emit(StreamChunk.Finish(FinishReason.Stop))
			}
		}
	}

	/** Asks for the same tool on every request, until a budget stops it. */
	private class AlwaysCalls(private val toolName: String) : ChatProvider {
		override val id = "scripted"
		override suspend fun listModels(): List<RemoteModel> = emptyList()
		private var calls = 0

		override fun stream(request: ChatRequest): Flow<StreamChunk> = flow {
			calls++
			emit(StreamChunk.ToolCallStart(0, "call-$calls", toolName))
			emit(StreamChunk.ToolCallDelta(0, inputDelta = "{}"))
			emit(StreamChunk.ToolCallEnd(0))
			emit(StreamChunk.Finish(FinishReason.ToolCalls))
		}
	}

	/** Asks for two calls in the same round, then answers. */
	private class TwoCalls(private val toolName: String) : ChatProvider {
		override val id = "scripted"
		override suspend fun listModels(): List<RemoteModel> = emptyList()
		private var round = 0

		override fun stream(request: ChatRequest): Flow<StreamChunk> = flow {
			if (round++ == 0) {
				emit(StreamChunk.ToolCallStart(0, "call-1", toolName))
				emit(StreamChunk.ToolCallDelta(0, inputDelta = "{}"))
				emit(StreamChunk.ToolCallEnd(0))
				emit(StreamChunk.ToolCallStart(1, "call-2", toolName))
				emit(StreamChunk.ToolCallDelta(1, inputDelta = "{}"))
				emit(StreamChunk.ToolCallEnd(1))
				emit(StreamChunk.Finish(FinishReason.ToolCalls))
			} else {
				emit(StreamChunk.TextDelta("done"))
				emit(StreamChunk.Finish(FinishReason.Stop))
			}
		}
	}

	/**
	 * Records how many `execute` calls were inside each other.
	 *
	 * The delay is what gives a concurrent implementation a chance to overlap;
	 * without it the two calls would finish so fast that the measurement would
	 * pass by accident.
	 */
	private class ConcurrencyTrackingTool : Tool {
		override val name = "probe"
		override val description = "probe"
		override val parameters: Map<String, Any?> = mapOf("type" to "object")
		override val mutates = false
		var executions = 0
		val maxConcurrent = AtomicInteger(0)
		private val active = AtomicInteger(0)

		override suspend fun execute(arguments: JsonObject): ToolResult {
			executions++
			val now = active.incrementAndGet()
			maxConcurrent.updateAndGet { current -> maxOf(current, now) }
			delay(40)
			active.decrementAndGet()
			return ToolResult.ok("ok")
		}
	}

	private class RecordingTool(
		override val name: String = "probe",
		private val transform: (JsonObject) -> JsonObject = { it },
	) : Tool {
		override val description = "probe"
		override val parameters: Map<String, Any?> = mapOf("type" to "object")
		override val mutates = false
		var seen: JsonObject? = null
		var executions = 0

		override fun normalize(arguments: JsonObject): JsonObject = transform(arguments)

		override suspend fun execute(arguments: JsonObject): ToolResult {
			executions++
			seen = arguments
			return ToolResult.ok("ok")
		}
	}
}
