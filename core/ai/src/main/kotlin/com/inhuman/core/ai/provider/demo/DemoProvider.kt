package com.inhuman.core.ai.provider.demo

import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.ReasoningType
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * A provider that answers from a script.
 *
 * ## Why this ships rather than living in a test
 *
 * The streaming path -- provider chunks into view-model state into Compose into
 * a rendered bubble -- is the part of this app most likely to break, and it is
 * also the part an API key gate keeps out of reach during development. A
 * fixture-driven provider makes the whole path exercisable without a key, a
 * network, or a bill.
 *
 * It is also the honest way to see the UI work: a screenshot of a real reply is
 * evidence about the network, while a screenshot of this is evidence about the
 * app. Both matter, and only one of them is available on demand.
 *
 * ## Why it calls tools
 *
 * Text alone exercises one path. The branches that actually break are the ones
 * with state in them: the tool loop, the approval gate, and the chip that has
 * to say a call was refused rather than pending. None of those could be reached
 * without a key while this provider only spoke prose -- which meant the app's
 * most consequential control was the one thing that could not be demonstrated.
 *
 * So when the request carries tools, the script is: create a file, then ask to
 * delete it. Deleting was the one gated operation, so that turn raised the
 * dialog and declining it is what the declined state exists for.
 *
 * **It is no longer gated by default** (`docs/09-approvals.md`: deletion inside
 * a conversation's own directory is reversible, because the agent is confined
 * to that directory and what the user brings in is a copy). The script is
 * unchanged and still worth having -- the delete now runs instead of asking,
 * which is the behaviour to see -- and the approval path is still reachable by
 * configuring a rule on `delete_file`. Nothing here fakes a gate: a demo that
 * asked for approval the app would not ask for would be the same class of lie
 * as a comment that contradicts its code.
 *
 * ## What it deliberately does not do
 *
 * It does not decide anything the app decides. Both calls are emitted **only if
 * the request already offers them**, so an assistant with file tools switched
 * off gets the prose script and no calls -- which is the correct behaviour and
 * also the reason this reads `request.tools` instead of assuming.
 */
class DemoProvider(
	private val chunkDelayMillis: Long = 22,
) : ChatProvider {

	override val id: String = "demo"

	override suspend fun listModels(): List<RemoteModel> = listOf(
		RemoteModel(
			id = "demo-model",
			displayName = "Demo model (no network)",
			contextWindow = 64_000,
			maxOutputTokens = 4_096,
			inputModalities = listOf("text", "image"),
			supportedEffortLevels = listOf("low", "high"),
			defaultEffortLevel = "high",
		),
	)

	override fun stream(request: ChatRequest): Flow<StreamChunk> = flow {
		val offered = request.tools.map { it.name }.toSet()
		val calls = scriptedCalls(request, offered)

		// Reasoning first. Real reasoning models emit this before any visible
		// text, and the UI has to place it above the answer rather than
		// interleaved with it.
		emit(StreamChunk.ReasoningStart(type = ReasoningType.Summary))
		for (piece in reasoningFor(calls).split(" ")) {
			emit(StreamChunk.ReasoningDelta("$piece "))
			delay(chunkDelayMillis)
		}
		emit(StreamChunk.ReasoningEnd())

		// A pause where a real model would be thinking before its first token.
		delay(180)

		emit(StreamChunk.TextStart())
		val answer = when {
			calls.isNotEmpty() -> CALL_PREAMBLE
			// Scoped to *this* turn, which is the bug this fixes. Scanning the
			// whole history for a tool message meant that once a conversation
			// had used a tool anywhere, every later turn answered with
			// AFTER_TOOLS -- including turns that made no calls at all, so a
			// plain question came back labelled "Used 2 tools" while the model
			// had asked for nothing. TEXT_ANSWER was unreachable after the
			// first tool turn.
			request.messages
				.drop(request.messages.indexOfLast { it.role == Role.User } + 1)
				.any { it.role == Role.Tool } -> AFTER_TOOLS

			else -> TEXT_ANSWER
		}
		for (piece in answer.split(" ")) {
			emit(StreamChunk.TextDelta("$piece "))
			delay(chunkDelayMillis)
		}
		emit(StreamChunk.TextEnd())

		calls.forEachIndexed { index, call -> emitCall(index, call) }

		emit(
			StreamChunk.Usage(
				promptTokens = 128,
				completionTokens = answer.length / 4,
				totalTokens = 128 + answer.length / 4,
				cachedPromptTokens = 96,
				reasoningTokens = reasoningFor(calls).length / 4,
			),
		)

		emit(
			StreamChunk.Finish(
				// Said honestly rather than always `Stop`: a turn that asked for
				// tools did not finish answering, and the transcript prints this
				// word next to the reply.
				reason = if (calls.isEmpty()) FinishReason.Stop else FinishReason.ToolCalls,
				responseId = "demo-${System.currentTimeMillis()}",
				model = "demo-model",
			),
		)
	}

	/**
	 * What this turn asks for, if anything.
	 *
	 * ## Why the turn is inferred from the transcript
	 *
	 * A provider is stateless -- it is handed a history and answers it -- and a
	 * demo that kept a counter instead would desynchronise the moment a reply
	 * was regenerated or a branch was switched, then start calling delete on a
	 * file that is already gone. The history says which turn this is, so the
	 * history is what is read: anything after the last user message is this
	 * turn's own work in progress, and a tool result in there means the calls
	 * have been made and it is time to talk.
	 *
	 * The same reasoning explains the empty result for a request with no user
	 * message at all: there is nothing to answer.
	 */
	private fun scriptedCalls(request: ChatRequest, offered: Set<String>): List<ScriptedCall> {
		val lastUser = request.messages.indexOfLast { it.role == Role.User }
		if (lastUser < 0) return emptyList()

		// "plain" in the message means talk, do not act.
		//
		// Not a magic word for its own sake: the tool turn ends by raising the
		// approval gate, so without a way to ask for prose the demo's long
		// answer can only ever be read *after* answering a dialog. That makes
		// the one thing the long answer exists to show -- text arriving and
		// being parsed as it arrives -- the one thing that cannot be watched.
		if (request.messages[lastUser].content.contains(PLAIN_MARKER, ignoreCase = true)) {
			return emptyList()
		}

		val inFlight = request.messages.drop(lastUser + 1)
		if (inFlight.any { it.role == Role.Tool }) return emptyList()
		// A retry of the same turn arrives with the previous attempt's calls
		// still attached. Answering it with the same calls again is the loop
		// this check exists to prevent.
		if (inFlight.any { it.toolCalls.isNotEmpty() }) return emptyList()

		return listOfNotNull(
			fileCall("write_file", offered, WRITE_ARGUMENTS),
			fileCall("delete_file", offered, DELETE_ARGUMENTS),
		)
	}

	private fun fileCall(name: String, offered: Set<String>, arguments: String): ScriptedCall? =
		if (name in offered) ScriptedCall(name, arguments) else null

	private fun reasoningFor(calls: List<ScriptedCall>): String =
		if (calls.isEmpty()) REASONING else REASONING_WITH_TOOLS

	/**
	 * One call, streamed the way a provider streams one.
	 *
	 * The arguments go out as **fragments**, not as a finished string. That is
	 * not decoration: sending them whole would skip the accumulation path, and
	 * a partial JSON document is exactly the input that path exists to handle.
	 */
	private suspend fun FlowCollector<StreamChunk>.emitCall(
		index: Int,
		call: ScriptedCall,
	) {
		emit(StreamChunk.ToolCallStart(index = index, id = "demo-call-$index", name = call.name))
		call.arguments.chunked(ARGUMENT_FRAGMENT).forEach { fragment ->
			emit(StreamChunk.ToolCallDelta(index = index, inputDelta = fragment))
			delay(chunkDelayMillis)
		}
		emit(StreamChunk.ToolCallEnd(index = index))
	}

	/** One call the script will make, as the name and the JSON it carries. */
	private data class ScriptedCall(val name: String, val arguments: String)

	private companion object {
		/** How much of an argument string arrives per chunk. */
		const val ARGUMENT_FRAGMENT = 12

		/** Put this in a message to get prose instead of a tool turn. */
		const val PLAIN_MARKER = "plain"

		/**
		 * The file the script makes and then offers to delete.
		 *
		 * Under `demo/`, so it cannot be mistaken for something the user put
		 * there or for a file a real model wrote.
		 */
		const val DEMO_PATH = "demo/notes.txt"

		const val REASONING =
			"This is a demo reply, so there is no model behind it. " +
				"The text below is emitted one word at a time through the same " +
				"streaming path a real provider uses."

		const val REASONING_WITH_TOOLS =
			"This is a demo reply, so there is no model behind it. " +
				"Tools are switched on for this assistant, so the script will " +
				"create a file and then ask to delete it -- which is the one " +
				"call this app stops to ask about."

		/**
		 * The no-tools answer, and also Markdown on purpose.
		 *
		 * Same reasoning as [AFTER_TOOLS]: an assistant with no tools still
		 * renders through the incremental parser, so the demo's short path has
		 * to carry the constructs that are awkward while streaming rather than
		 * a single plain sentence. The fence opens long before it closes, which
		 * is the state a real reply spends most of its time in.
		 */
		val TEXT_ANSWER = """
			# Hello

			You are reading a simulated stream: the words arrived as individual
			chunks, were accumulated by the view model, and were rendered as they
			came.

			- The dimmed block above is a **reasoning region**, kept separate from
			  the answer rather than merged into it.
			- This reply is Markdown parsed *while it arrives*.

			| Tool | Mutates | Cost |
			| --- | --- | --- |
			| `read_file` | no | free |
			| `web_search` | no | paid per successful call, so a failed request costs nothing |
			| `ssh_exec` | yes, and not reversible | free, but the command runs on another machine |

			```
			a fence that is open, and not yet parseable,
			for as long as the tokens keep arriving
			```

			Add a DeepSeek key in settings to talk to a real model.
		""".trimIndent()

		const val CALL_PREAMBLE =
			"Demo mode with tools. Creating a file, then deleting it. Configure a " +
				"rule on delete_file if you want to be asked first."

		/**
		 * The closing answer, written in Markdown on purpose.
		 *
		 * ## Why the demo reply is not plain prose
		 *
		 * The Markdown renderer is the most intricate thing on the reply path:
		 * it parses incrementally while text arrives, then hands over to a
		 * whole-document parse when the turn ends -- and a half-written
		 * construct, an unclosed fence or a table row without its header, is
		 * the normal state of a reply in progress rather than an error.
		 *
		 * A plain sentence exercises none of that. This one carries the
		 * constructs that are genuinely awkward mid-stream: a fenced block that
		 * opens long before it closes, a bold span arriving in pieces, and a
		 * list whose markers precede their text. All of it offline, no key.
		 */
		val AFTER_TOOLS = """
			That is the whole demo, and this reply is the part worth watching: it
			is **Markdown, parsed while it arrives** rather than after it finishes.

			- The file tools ran for real, so the conversation's folder holds
			  whatever they did.
			- A refusal leaves the file alone, which is the point of asking.
			- Nothing above left this device.

			```kotlin
			// A fenced block is open, and therefore not yet parseable, for as
			// long as the tokens keep arriving.
			val fence = "closed only at the very end"
			```

			Add a DeepSeek key in settings to talk to a real model.
		""".trimIndent()

		val WRITE_ARGUMENTS = """{"path":"$DEMO_PATH","content":"A file the demo made."}"""

		val DELETE_ARGUMENTS = """{"path":"$DEMO_PATH"}"""
	}
}
