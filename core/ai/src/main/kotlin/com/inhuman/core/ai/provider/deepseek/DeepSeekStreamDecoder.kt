package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.ReasoningType
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.stream.SseFrame

/**
 * Turns DeepSeek's SSE frames into [StreamChunk]s.
 *
 * ## Shape of the wire format
 *
 * DeepSeek's `/chat/completions` is OpenAI-compatible, so a frame looks like:
 *
 * ```
 * data: {"choices":[{"delta":{"content":"He"},"finish_reason":null}],...}
 * data: {"choices":[{"delta":{"reasoning_content":"Let me"},"finish_reason":null}]}
 * data: [DONE]
 * ```
 *
 * Three things about this are easy to get wrong:
 *
 * 1. **Reasoning and text are separate fields on the same delta.** Either,
 *    both, or neither may be present in a given frame. A decoder that reads
 *    only `content` silently drops the model's reasoning; one that assumes
 *    they arrive in separate frames mis-orders them when they do not.
 *
 * 2. **Tool-call arguments arrive as string fragments, indexed.** The frame
 *    carries `tool_calls: [{index: 0, function: {arguments: "{\"pa"}}]` and
 *    later `"th\":\"a.txt\"}"`. It is not valid JSON until the last fragment.
 *    Parsing per fragment is the single most common way tool calling breaks,
 *    so this decoder concatenates and emits [StreamChunk.ToolCallEnd] to mark
 *    the point at which parsing becomes legal. The `index` field is the
 *    provider's ordering key and must be used as the map key -- parallel calls
 *    are interleaved on the wire, and keying on arrival order corrupts every
 *    call after the first.
 *
 * 3. **`[DONE]` is a sentinel, not JSON.** `Json.parseToJsonElement` throws on
 *    it, so it has to be tested for before any parsing happens.
 *
 * Instances are **stateful and single-use**: one per response stream. The
 * decoder remembers which regions are open so it can emit matching End chunks.
 */
class DeepSeekStreamDecoder {

	private var textOpen = false
	private var reasoningOpen = false
	private val openToolCalls = LinkedHashSet<Int>()
	private var sawFinish = false

	/** Decode one frame. Returns zero or more chunks, in order. */
	fun decode(frame: SseFrame): List<StreamChunk> {
		val payload = frame.data.trim()

		// The sentinel. Not JSON, and the only explicit end-of-stream signal
		// this protocol has.
		if (payload == "[DONE]") {
			return closeEverything().let { closing ->
				if (sawFinish) closing
				else closing + StreamChunk.Finish(FinishReason.Unknown)
			}
		}

		if (payload.isEmpty()) return emptyList()

		val root = try {
			DeepSeekJson.asObjectOrNull(DeepSeekJson.parseToJsonElement(payload))
		} catch (e: Exception) {
			// A frame that is not JSON is either a provider-side notice or a
			// truncated line. Surfacing it beats dropping it -- and the payload
			// is included, because without it the message says only that
			// something was wrong with something. Truncated to keep a runaway
			// frame from filling the transcript, and it is the head of the
			// frame that says what it was.
			return listOf(
				StreamChunk.Failure(
					"could not parse stream frame: ${payload.take(200)}",
					e,
				),
			)
		} ?: return emptyList()

		val out = mutableListOf<StreamChunk>()

		// Usage can arrive on its own frame (with an empty `choices` array)
		// when the client asked for it, and also on the final frame.
		DeepSeekJson.usage(root)?.let { out += it }

		val choice = DeepSeekJson.firstChoice(root)
		if (choice != null) {
			val delta = DeepSeekJson.delta(choice)

			if (delta != null) {
				// Reasoning first. When a frame carries both, the reasoning is
				// conceptually prior, and emitting text first would render the
				// answer above the thinking that produced it.
				DeepSeekJson.reasoningText(delta)?.takeIf { it.isNotEmpty() }?.let { r ->
					if (!reasoningOpen) {
						reasoningOpen = true
						out += StreamChunk.ReasoningStart(type = ReasoningType.Summary)
					}
					out += StreamChunk.ReasoningDelta(r)
				}

				DeepSeekJson.contentText(delta)?.takeIf { it.isNotEmpty() }?.let { t ->
					if (!textOpen) {
						// Text opening implies reasoning is done: a provider does
						// not go back to thinking after it starts answering.
						if (reasoningOpen) {
							reasoningOpen = false
							out += StreamChunk.ReasoningEnd()
						}
						textOpen = true
						out += StreamChunk.TextStart()
					}
					out += StreamChunk.TextDelta(t)
				}

				DeepSeekJson.toolCalls(delta).forEach { call ->
					val index = call.index
					val isNew = openToolCalls.add(index)
					if (isNew) {
						out += StreamChunk.ToolCallStart(
							index = index,
							id = call.id,
							name = call.name,
						)
					}

					// The payload is emitted whether or not this frame opened
					// the call, and that is the point: the *first* frame for a
					// tool call carries the id, the name AND the opening
					// fragment of the arguments together. Emitting only the
					// Start here drops that fragment, and the assembled JSON
					// then fails to parse with a missing opening brace -- a
					// failure that only shows up once a tool is actually
					// called with arguments.
					if (call.name != null || call.arguments != null) {
						out += StreamChunk.ToolCallDelta(
							index = index,
							// The name rides the Start chunk, so it is not
							// repeated here.
							//
							// The limit of that, stated rather than implied: a
							// provider that sends the *whole* name on every
							// frame would have it echoed into a renderer that
							// concatenates deltas, and a provider that splits
							// the name while also echoing it in full on the
							// opening frame would have the tail lost. Neither
							// is a shape this app has met. What is supported,
							// and was not before, is a name split across frames
							// with nothing on the opener to duplicate it: the
							// consumer now assembles the fragments instead of
							// keeping only the first.
							nameDelta = null,
							// Passed through as a raw fragment. Parsing here
							// would be wrong: it is very often not valid JSON.
							inputDelta = call.arguments,
						)
					}
				}
			}

			DeepSeekJson.finishReason(choice)?.let { reason ->
				sawFinish = true
				out += closeEverything()
				out += StreamChunk.Finish(
					reason = mapFinishReason(reason),
					responseId = DeepSeekJson.id(root),
					model = DeepSeekJson.model(root),
				)
			}
		}

		return out
	}

	/**
	 * Flush at end of body.
	 *
	 * A server that closes without a blank line, or a connection trimmed by a
	 * proxy, leaves regions open and no `[DONE]`. Without this the UI keeps
	 * showing a streaming cursor forever.
	 */
	fun endOfStream(): List<StreamChunk> =
		if (sawFinish) emptyList()
		else closeEverything() + StreamChunk.Finish(FinishReason.Unknown)

	private fun closeEverything(): List<StreamChunk> {
		val out = mutableListOf<StreamChunk>()
		if (reasoningOpen) {
			reasoningOpen = false
			out += StreamChunk.ReasoningEnd()
		}
		if (textOpen) {
			textOpen = false
			out += StreamChunk.TextEnd()
		}
		openToolCalls.forEach { out += StreamChunk.ToolCallEnd(it) }
		openToolCalls.clear()
		return out
	}

	private fun mapFinishReason(raw: String): FinishReason = when (raw) {
		"stop" -> FinishReason.Stop
		"tool_calls", "function_call" -> FinishReason.ToolCalls
		"length" -> FinishReason.Length
		"content_filter" -> FinishReason.ContentFilter
		else -> FinishReason.Unknown
	}
}
