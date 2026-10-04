package com.inhuman.core.ai.stream

/**
 * One Server-Sent Events frame, already unescaped but not yet interpreted.
 *
 * The split between this and [com.inhuman.core.ai.StreamChunk] is the important
 * one: this type is **transport**, that one is **provider semantics**. SSE has
 * a spec, and every provider follows it; what goes *inside* `data` is where
 * they all differ. Parsing the envelope here means each provider decoder deals
 * only with its own JSON, and swapping the HTTP client later touches nothing
 * below this line.
 *
 * @param event the `event:` field, or null for the default message type.
 *   Required to be preserved: OpenAI's Responses API, Anthropic and MCP all use
 *   named events, and a parser that keeps only `data` cannot read them.
 * @param data the concatenation of every `data:` line in the frame, joined with
 *   newlines as the spec requires.
 * @param id the `id:` field, if present. Unused today; kept because it is what a
 *   resumable stream would need and dropping it silently forecloses that.
 * @param retryMillis the `retry:` field, if a server sent one.
 */
data class SseFrame(
	val event: String?,
	val data: String,
	val id: String? = null,
	val retryMillis: Long? = null,
)

/**
 * A spec-compliant Server-Sent Events parser, fed one line at a time.
 *
 * ## Why hand-written
 *
 * The providers that matter here do not all send plain `data:`-only frames, and
 * the differences are exactly what a naive parser gets wrong:
 *
 * - **Comments.** Any line beginning with `:` is a comment and must be ignored.
 *   DeepSeek documents that a streaming request receives `: keep-alive`
 *   comments while it waits for inference to begin, and its docs warn that a
 *   client parsing the response itself has to handle them. A parser that
 *   assumes every chunk is JSON dies on the first one.
 * - **Multi-line data.** A frame may carry several `data:` lines that must be
 *   joined with `\n`. Taking only the last one truncates the payload.
 * - **Named events.** Anthropic and the OpenAI Responses API put meaning in
 *   `event:`; a parser that discards it cannot tell a `content_block_delta`
 *   from a `message_stop`.
 * - **No trailing blank line.** The final frame can arrive without the blank
 *   line that normally terminates it, so [endOfStream] must flush.
 *
 * ## Contract
 *
 * Feed lines with [accept], which returns a frame only when one is complete.
 * Call [endOfStream] once the body is exhausted. Instances are stateful and
 * must not be shared between responses.
 *
 * Line endings are the caller's problem to normalise, but this is lenient:
 * a trailing `\r` is stripped, because SSE mandates CRLF and a decoder that
 * forgets leaves a `\r` glued to the end of every token.
 */
class SseParser {

	private val dataLines = StringBuilder()
	private var eventName: String? = null
	private var lastId: String? = null
	private var retry: Long? = null
	private var sawAnyField = false

	/**
	 * Accept one line and return a frame if that line completed one.
	 *
	 * @return the completed frame, or null if more lines are needed.
	 */
	fun accept(line: String): SseFrame? {
		// Strip a single trailing CR. SSE is CRLF-delimited; a reader that has
		// already split on LF leaves the CR behind, and it would otherwise end
		// up inside every token of the answer.
		val clean = line.removeSuffix("\r")

		// A blank line dispatches whatever has accumulated.
		if (clean.isEmpty()) return dispatch()

		// A line starting with ':' is a comment and carries no field. This is
		// the keep-alive that DeepSeek sends while inference has not started.
		if (clean.startsWith(":")) return null

		val colon = clean.indexOf(':')
		val field = if (colon < 0) clean else clean.substring(0, colon)
		// Per spec, a single space after the colon is removed and any further
		// whitespace is significant.
		val value = when {
			colon < 0 -> ""
			colon + 1 >= clean.length -> ""
			clean[colon + 1] == ' ' -> clean.substring(colon + 2)
			else -> clean.substring(colon + 1)
		}

		sawAnyField = true
		when (field) {
			"data" -> {
				if (dataLines.isNotEmpty()) dataLines.append('\n')
				dataLines.append(value)
			}
			"event" -> eventName = value
			"id" -> lastId = value
			"retry" -> retry = value.toLongOrNull()
			// Unknown fields are ignored, as the spec requires. Providers add
			// their own and a strict parser would break on each new one.
		}
		return null
	}

	/**
	 * Flush any unterminated frame at the end of the body.
	 *
	 * A frame is only dispatched by a blank line, so a server that closes
	 * immediately after its last `data:` line -- which is common, and which
	 * some proxies cause by trimming the final newline -- would otherwise lose
	 * the final chunk. That chunk is often the one carrying `finish_reason`.
	 */
	fun endOfStream(): SseFrame? = dispatch()

	private fun dispatch(): SseFrame? {
		if (!sawAnyField) return null

		val frame = if (dataLines.isEmpty() && eventName == null) {
			// Only `id:`/`retry:` fields -- nothing to hand on.
			null
		} else {
			SseFrame(
				event = eventName,
				data = dataLines.toString(),
				id = lastId,
				retryMillis = retry,
			)
		}

		dataLines.setLength(0)
		eventName = null
		retry = null
		sawAnyField = false
		// `lastId` intentionally survives: the spec says the last event ID
		// persists across frames until replaced, which is what makes a
		// reconnection resumable.
		return frame
	}
}
