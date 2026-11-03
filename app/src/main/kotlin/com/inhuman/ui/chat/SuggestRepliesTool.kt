package com.inhuman.ui.chat

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ToolSpec
import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/** The name the main model calls, and the name the transcript stores. */
internal const val SUGGEST_TOOL_NAME = "suggest_replies"

/** The tool the suggestion model itself is asked to call, when the format is tool. */
internal const val EMIT_SUGGESTIONS_TOOL = "emit_suggestions"

/** How long the extra request may take before the turn simply has no chips. */
private const val SUGGESTION_TIMEOUT_MS = 10_000L

/**
 * The first line of the user message so a scripted provider can tell this
 * request apart from a conversation turn.
 *
 * A demo that keyed on "Question:" would mistake a user's own message for a
 * suggestion request and answer it with chips. The marker is prompt text; the
 * model reads it as a label, and `DemoProvider` is the only other reader.
 */
internal const val SUGGESTION_REQUEST_MARK = "[suggest_replies]"

/** Which shape the suggestion model is asked for. */
internal enum class SuggestionFormat(val wire: String) {
	Text("text"),
	Tool("tool"),
	;

	companion object {
		fun of(value: String?): SuggestionFormat =
			entries.firstOrNull { it.wire == value } ?: Tool
	}
}

/**
 * What the turn had written when the tool ran.
 *
 * The tool is built before the turn starts but executes after the round that
 * called it, so the answer is read through this holder rather than passed in
 * the arguments: asking the model to repeat its own answer inside the call
 * would bill the text twice and give it a second chance to truncate it.
 *
 * Written by the UI thread, read by the tool dispatcher, hence `@Volatile`:
 * without it the tool can see an answer from before the last delta.
 */
internal class TurnScratch {
	@Volatile var answer: String = ""
	@Volatile var lastUserMessage: String = ""
}

/**
 * The tool that turns one answer into 2-3 follow-ups.
 *
 * ## What it is, and what it is not
 *
 * It is an ordinary tool in the main turn: it appears in the catalogue and the
 * picker, it is called by the model, and its result is persisted like any other
 * tool result. What is unusual is [endsTurn]: the result is the whole point, so
 * the engine stops instead of sending the suggestions back to the model to be
 * repeated.
 *
 * ## Why the extra request is here
 *
 * The owner asked for a separately configured suggestion model (a cheaper one,
 * a different prompt). That is a second request by construction; putting it
 * behind a tool is what makes the price visible in the transcript and in the
 * tool list instead of hidden in the turn. `paid = true` in the catalogue is
 * the same fact stated where the tool is chosen (`docs/08-port-philosophy.md`
 * §1.4).
 *
 * ## Failure is silence
 *
 * A missing model, a timeout, a provider error or a malformed argument object
 * all produce an empty list, not an error row: the answer has already been
 * delivered, and a suggestion that did not arrive is not a failed turn. The
 * cause goes to diagnostics without the question or the answer.
 */
internal class SuggestRepliesTool(
	private val provider: ChatProvider,
	private val model: String,
	private val prompt: String,
	private val format: SuggestionFormat,
	private val scratch: TurnScratch,
) : Tool {

	override val name: String = SUGGEST_TOOL_NAME

	override val description: String =
		"Call this once, after your final answer, when 2-3 plausible follow-ups " +
			"exist. Do not call it before answering, and do not repeat its result " +
			"in the answer. It returns nothing you need to read."

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to emptyMap<String, Any?>(),
	)

	override val mutates: Boolean = false

	override val endsTurn: Boolean = true

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val answer = scratch.answer.trim()
		if (answer.isEmpty()) return ToolResult.ok(Advice.toJson(emptyList()))
		val question = scratch.lastUserMessage.trim()
		return try {
			withTimeout(SUGGESTION_TIMEOUT_MS) {
				ToolResult.ok(Advice.toJson(ask(question, answer)))
			}
		} catch (e: TimeoutCancellationException) {
			Diag.w("suggest", "suggestion call timed out")
			ToolResult.ok(Advice.toJson(emptyList()))
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Diag.w("suggest", "suggestion call failed: ${e.javaClass.simpleName}")
			ToolResult.ok(Advice.toJson(emptyList()))
		}
	}

	private suspend fun ask(question: String, answer: String): List<String> {
		val request = ChatRequest(
			model = model,
			messages = listOf(
				ChatMessage(Role.System, prompt),
				ChatMessage(
					Role.User,
					"$SUGGESTION_REQUEST_MARK\nQuestion:\n$question\n\nAnswer:\n$answer",
				),
			),
			tools = if (format == SuggestionFormat.Tool) listOf(emitSpec()) else emptyList(),
			// A suggestion is not a reasoning task; leaving this on would bill
			// thinking tokens for three short lines.
			thinking = false,
			maxTokens = 300,
		)

		val text = StringBuilder()
		val names = mutableMapOf<Int, StringBuilder>()
		val arguments = mutableMapOf<Int, StringBuilder>()
		var failure: String? = null

		provider.stream(request).collect { chunk ->
			when (chunk) {
				is StreamChunk.TextDelta -> text.append(chunk.text)

				is StreamChunk.ToolCallStart -> names.getOrPut(chunk.index) { StringBuilder() }
					.apply { chunk.name?.let { append(it) } }

				is StreamChunk.ToolCallDelta -> {
					chunk.nameDelta?.let {
						names.getOrPut(chunk.index) { StringBuilder() }.append(it)
					}
					chunk.inputDelta?.let {
						arguments.getOrPut(chunk.index) { StringBuilder() }.append(it)
					}
				}

				is StreamChunk.Failure -> failure = chunk.message

				else -> Unit
			}
		}

		if (failure != null) {
			Diag.w("suggest", "provider refused the suggestion request")
			return emptyList()
		}

		// The tool shape is preferred when the model produced it; the text parse
		// is the fallback for a model that ignored the schema, which is the same
		// "no absolute guarantee" the owner accepted for the call itself.
		val call = arguments.entries.firstOrNull { (index, _) ->
			names[index]?.toString() == EMIT_SUGGESTIONS_TOOL
		}
		if (format == SuggestionFormat.Tool && call != null) {
			val structured = Advice.fromJson(call.value.toString())
			if (structured.isNotEmpty()) return structured
		}
		return Advice.sanitize(text.toString().lineSequence())
	}

	private fun emitSpec() = ToolSpec(
		name = EMIT_SUGGESTIONS_TOOL,
		description =
			"Return 2-3 short follow-ups the user might send next. " +
				"One line each, no bullets or numbering, at most 60 characters.",
		parameters = mapOf(
			"type" to "object",
			"properties" to mapOf(
				"suggestions" to mapOf(
					"type" to "array",
					"items" to mapOf("type" to "string"),
					"maxItems" to 3,
				),
			),
			"required" to listOf("suggestions"),
		),
	)
}

/**
 * The prompt used when the owner left the per-assistant prompt empty.
 *
 * Written for the suggestion model, not for the main one: it sees only the last
 * question and the finished answer, and its job is to predict the user's next
 * message rather than to comment on the answer.
 */
internal val DEFAULT_SUGGESTION_PROMPT: String =
	"You suggest what the user might type next after reading an assistant's " +
		"answer. Return 2-3 short follow-ups, one per line, at most 60 characters " +
		"each, in the language of the question. Do not use bullets or numbering. " +
		"Prefer concrete next steps over generic praise. If nothing is worth " +
		"suggesting, return nothing."

