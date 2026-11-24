package com.inhuman.ui.chat

/**
 * The naming and lookup rules for cached reply audio.
 *
 * `ChatViewModel.readAloud` writes `generated/speech/<message-id>-<voice>.<ext>`
 * in the conversation's workspace and returns that file on a second tap instead
 * of paying for a second synthesis. Two screens ask the same question from
 * opposite directions -- the transcript asks "is this reply already synthesized
 * in the current voice?", the home list asks "has this conversation any audio at
 * all?" -- and a second copy of the naming rule would be the next bug. So the
 * rule lives here, pure and testable without a device.
 */
object SpeechCache {

	/** A filesystem-safe token for the deterministic speech cache name. */
	fun safeFilePart(value: String?): String? =
		value?.trim()
			?.takeIf { it.isNotEmpty() }
			?.replace(Regex("[^A-Za-z0-9_-]"), "_")
			?.take(64)

	/** The cache-name prefix for one reply in one voice. */
	fun prefix(messageId: String, voice: String?): String =
		"${safeFilePart(messageId) ?: "reply"}-${safeFilePart(voice) ?: "default"}."

	/**
	 * The replies in [messageIds] whose audio for [voice] exists in [fileNames].
	 *
	 * The caller lists `generated/speech` and drops partial files; this only
	 * matches names. Prefix matching, not equality, because the extension
	 * follows the backend (`wav` for DeepSeek, `mp3` for Qwen).
	 */
	fun cachedMessageIds(
		fileNames: Iterable<String>,
		messageIds: Iterable<String>,
		voice: String?,
	): Set<String> {
		val names = fileNames.toList()
		if (names.isEmpty()) return emptySet()
		return messageIds.filterTo(mutableSetOf()) { id ->
			val prefix = prefix(id, voice)
			names.any { it.startsWith(prefix) }
		}
	}

	/** Whether a conversation has any usable reply audio, in any voice. */
	fun hasAnyAudio(fileNames: Iterable<String>): Boolean =
		fileNames.any { it.isNotBlank() }
}
