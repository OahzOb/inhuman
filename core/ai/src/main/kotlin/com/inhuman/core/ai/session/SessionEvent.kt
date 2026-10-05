package com.inhuman.core.ai.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One entry in a conversation's durable session log.
 *
 * ## Where this comes from
 *
 * The shape is not invented. It was extracted from 102 real DeepSeek Harness
 * session logs -- 113,129 events -- and every event in them carries exactly
 * these four fields:
 *
 * ```jsonc
 * { "type": "tool/call", "seq": 1234, "time": 1790348406004, "data": { … } }
 * ```
 *
 * The one exception is the `session` record, which carries `version`, `id`,
 * `createdAt`, `cwd` and friends and **no `seq`** -- because it is the file
 * header rather than a log entry. [isDurable] encodes that difference, and the
 * difference matters: the records named `*-chunks` also lack a `seq` (they use
 * `seq0`), they account for 6.3 MB of 249 MB, and they are streaming-render
 * accelerators rather than history. A reader may skip them and lose only the
 * ability to replay a reply token by token.
 *
 * [data] is kept as raw JSON rather than a sealed hierarchy of typed payloads.
 * The reference implementation has 43 durable event kinds and grows them
 * between releases; a typed model would need editing for every one, and a
 * reader that only folds the handful it understands would fail to open a log
 * written by a newer version. Per-kind accessors live in the projection, which
 * is where the knowledge actually belongs.
 */
data class SessionEvent(
	val type: String,
	/**
	 * Monotonic position in the log, or null for a record that is not history.
	 *
	 * This is the watermark a projection folds from, and the tie-break for
	 * ordering. Null means "not part of the durable log".
	 */
	val seq: Long?,
	val time: Long,
	val data: JsonObject = JsonObject(emptyMap()),
) {
	/** Whether this record belongs to the replayable history. */
	val isDurable: Boolean get() = seq != null

	companion object {
		private val json = Json { ignoreUnknownKeys = true; isLenient = true }

		/**
		 * Read one JSONL line.
		 *
		 * Returns null rather than throwing: a log written by a newer release
		 * can contain a shape this build does not know, and a conversation that
		 * refuses to open is worse than one missing an event it never
		 * understood. A torn final line -- the normal result of a process death
		 * mid-append -- is the same case.
		 */
		fun parseOrNull(line: String): SessionEvent? {
			if (line.isBlank()) return null
			return runCatching {
				val root = json.parseToJsonElement(line).jsonObject
				val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
				SessionEvent(
					type = type,
					// `seq0` on a chunk record is deliberately not read as `seq`:
					// treating an accelerator as history would let a projection
					// fold the same reply twice.
					seq = root["seq"]?.jsonPrimitive?.longOrNull,
					time = root["time"]?.jsonPrimitive?.longOrNull
						?: root["createdAt"]?.jsonPrimitive?.longOrNull
						?: 0L,
					data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
						?: JsonObject(emptyMap()),
				)
			}.getOrNull()
		}

		/** Read a whole log. Blank lines and malformed ones are skipped. */
		fun parseAll(text: String): List<SessionEvent> =
			text.lineSequence().mapNotNull { parseOrNull(it) }.toList()
	}
}

/** Convenience readers for the common payload fields. */
internal fun JsonObject.string(name: String): String? =
	this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.int(name: String): Int? =
	this[name]?.jsonPrimitive?.intOrNull

internal fun JsonObject.long(name: String): Long? =
	this[name]?.jsonPrimitive?.longOrNull

internal fun JsonObject.obj(name: String): JsonObject? =
	this[name]?.let { runCatching { it.jsonObject }.getOrNull() }
