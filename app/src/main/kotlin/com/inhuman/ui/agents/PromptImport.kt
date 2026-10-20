package com.inhuman.ui.agents

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A read guard, not a prompt limit; see [readPromptBytes]. */
internal const val MAX_PROMPT_IMPORT_BYTES = 1 shl 20

/**
 * A prompt file, by extension or by MIME type.
 *
 * Both are checked because Android's providers disagree about `.md`: some
 * report `text/markdown`, some report `application/octet-stream`, and the
 * extension is the only stable signal left. Any `text` MIME type also lets a
 * plain `.text`/`.log` file through, which is harmless for a prompt.
 */
internal fun isPromptText(name: String, mimeType: String?): Boolean {
	val extension = name.substringAfterLast('.', "").lowercase()
	return extension in setOf("txt", "md", "markdown") ||
		mimeType?.startsWith("text/") == true
}

/**
 * Read a prompt file, refusing anything past [MAX_PROMPT_IMPORT_BYTES].
 *
 * The ceiling is a read guard, not a prompt limit: it stops a mis-tapped video
 * or database from being pulled into memory before the user sees what happened.
 * A system prompt that big would not fit any context window anyway.
 */
internal fun readPromptBytes(input: InputStream): ByteArray {
	val out = ByteArrayOutputStream()
	val buffer = ByteArray(16 * 1024)
	var total = 0
	while (true) {
		val read = input.read(buffer)
		if (read < 0) break
		total += read
		if (total > MAX_PROMPT_IMPORT_BYTES) {
			throw IOException("The file is larger than 1 MiB.")
		}
		out.write(buffer, 0, read)
	}
	return out.toByteArray()
}

/**
 * Decode a prompt file as UTF-8, or explain why it cannot be a prompt.
 *
 * Invalid UTF-8 is rejected rather than silently replaced with U+FFFD, because
 * a prompt full of replacement characters is worse than a clear error. A BOM is
 * stripped: it is invisible in the editor and would otherwise become the first
 * character the model is told about.
 */
internal fun decodePromptText(bytes: ByteArray): String {
	val text = try {
		Charsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(bytes))
			.toString()
	} catch (failure: Exception) {
		throw IOException("The file is not valid UTF-8 text.")
	}

	val cleaned = text.removePrefix("\uFEFF")
	if (cleaned.isBlank()) throw IOException("The file is empty.")
	return cleaned
}
