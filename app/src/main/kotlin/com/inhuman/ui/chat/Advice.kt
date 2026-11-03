package com.inhuman.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The one place follow-up text is sanitised, and the tool result's shape.
 *
 * ## Why this is not a streaming block any more
 *
 * The old design appended a `<<<suggestions` block to the answer, hid it while
 * streaming and stripped it before the row was written. That existed so the
 * suggestions cost no extra request. They now come from the `suggest_replies`
 * tool: the model calls it after answering, the tool asks the configured
 * suggestion model, and the result is written to the transcript as an ordinary
 * tool result. Nothing is hidden, so there is no marker and no `visible`
 * filter -- only the two questions this file still owns: what counts as a
 * suggestion, and what the result looks like on the wire.
 *
 * ## Why the rules are here rather than in the tool
 *
 * The suggestion model can answer in text or call a tool, and both shapes can
 * carry bullets, numbering, duplicates, empty lines or five items where the
 * prompt asked for three. Applying the same [sanitize] to both is what keeps
 * "at most three, one line each" true regardless of which shape came back. A
 * second copy of these rules is how the two paths start disagreeing.
 */
internal object Advice {

	/** Trim, strip bullets/numbering, drop blanks, cap and de-duplicate. */
	fun sanitize(lines: Sequence<String>): List<String> = lines
		.map { line ->
			line.trim()
				.replace(Regex("^\\d+[.)]\\s*"), "")
				.trimStart('-', '*')
				.trim()
		}
		.filter { it.isNotEmpty() }
		.map { it.take(80) }
		.distinct()
		.take(3)
		.toList()

	/**
	 * The structured shape the tool contract uses: `{"suggestions":[...]}`.
	 *
	 * Deliberately total: a malformed result is no suggestions, not a failure.
	 * The turn has already been answered; a tool result that throws would turn
	 * a cosmetic miss into a failed call in the transcript.
	 */
	fun fromJson(json: String): List<String> = runCatching {
		val array = Json.parseToJsonElement(json).jsonObject.getValue("suggestions").jsonArray
		sanitize(array.map { it.jsonPrimitive.content }.asSequence())
	}.getOrDefault(emptyList())

	/** The tool result's content for a list, in the shape [fromJson] reads. */
	fun toJson(items: List<String>): String = buildJsonObject {
		put(
			"suggestions",
			buildJsonArray {
				items.forEach { add(it) }
			},
		)
	}.toString()
}
