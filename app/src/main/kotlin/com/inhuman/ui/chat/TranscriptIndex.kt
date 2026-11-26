package com.inhuman.ui.chat

/**
 * Keeps [TranscriptIndex] current without re-deriving it from scratch on every
 * publish.
 *
 * ## Why this is not a memo with a cheap key
 *
 * B11 warned against one for a good reason: a key made of `size`, `last id` and
 * a couple of flags cannot see an *older* row being rewritten in place, and
 * `ChatViewModel` does exactly that after a reload or a rewind. A stale footer
 * showing the previous turn's totals is worse than a scan.
 *
 * This does not key on anything. It compares the new list against the previous
 * one **by reference**, which is only valid because of how the view model
 * builds the list: `_state.copy(messages = messages + row)` reuses every earlier
 * row object, and a streaming row is the last one. The three shapes that reach
 * here are therefore:
 *
 * - the same list instance (only `isStreaming` changed) -- reuse everything but
 *   recompute the interrupted answer, which is O(tail);
 * - one appended row with a shared prefix -- patch the open turn and, when the
 *   row is a turn root, the jump list; O(turn length), not O(transcript);
 * - the same rows with a changed tail inside the open turn -- recompute that
 *   turn only. This is the per-publish case while a reply streams.
 *
 * Anything else -- a shorter list, a changed older row, a structurally equal
 * but rebuilt list -- falls back to a full pass. That is the property that
 * makes it safe: the fallback is *every* shape this class does not positively
 * recognise, so a future mutation path cannot be silently mis-derived; it can
 * only be slower. `TranscriptIndexerTest` asserts equality against a full pass
 * over randomised edit sequences for exactly that reason.
 */
internal class TranscriptIndexer {

	private var previous: List<ChatMessageUi> = emptyList()
	private var current: TranscriptIndex = TranscriptIndex()

	fun update(messages: List<ChatMessageUi>, streaming: Boolean): TranscriptIndex {
		val old = previous
		val interrupted = isInterruptedTurn(messages, streaming)
		val firstDiff = if (messages.size == old.size) firstDifference(old, messages) else -1

		val next = when {
			// Same instance: no row changed, so only the streaming flag can matter.
			messages === old -> current.copy(interrupted = interrupted)

			old.isEmpty() -> rebuild(messages, interrupted)

			// A new list object with the same rows: nothing to derive again.
			firstDiff == messages.size -> current.copy(interrupted = interrupted)

			messages.size == old.size + 1 && sharesPrefix(old, messages) ->
				appended(messages, old.size, interrupted)

			// A changed row inside the open turn. `openTurnStart` is the last turn
			// root, so anything before it belongs to a finished turn and takes the
			// slow path.
			firstDiff in 0 until messages.size && firstDiff >= openTurnStart(messages) ->
				openTurnChanged(messages, firstDiff, interrupted)

			else -> rebuild(messages, interrupted)
		}

		previous = messages
		current = next
		return next
	}

	private fun rebuild(messages: List<ChatMessageUi>, interrupted: Boolean): TranscriptIndex =
		TranscriptIndex(
			turns = turnSummariesOf(messages),
			userTurns = userTurnsOf(messages),
			interrupted = interrupted,
		)

	/** One row appended; every earlier row is the same instance. */
	private fun appended(
		messages: List<ChatMessageUi>,
		newIndex: Int,
		interrupted: Boolean,
	): TranscriptIndex {
		val row = messages[newIndex]
		// A turn root starts a new turn: the previous one's range is unchanged, and
		// the new one has no assistant yet, so no summary changes. The jump list
		// gains one entry -- recomputed rather than appended because the preview
		// projection lives in ChatSearch.kt and is not worth a second copy.
		if (row.isTurnRoot) {
			return current.copy(userTurns = userTurnsOf(messages), interrupted = interrupted)
		}
		return current.copy(
			turns = replaceOpenTurn(messages),
			interrupted = interrupted,
		)
	}

	/** One or more rows of the open turn changed; earlier turns did not. */
	private fun openTurnChanged(
		messages: List<ChatMessageUi>,
		firstDiff: Int,
		interrupted: Boolean,
	): TranscriptIndex {
		val start = openTurnStart(messages)
		// Editing the question itself changes its jump-list preview. That is a user
		// edit, not a per-stream-tick path, so a full jump-list rebuild is fine.
		val userTurns = if (firstDiff == start && messages[start].isTurnRoot) {
			userTurnsOf(messages)
		} else {
			current.userTurns
		}
		return current.copy(
			turns = replaceOpenTurn(messages),
			userTurns = userTurns,
			interrupted = interrupted,
		)
	}

	/**
	 * [turns] with the open turn re-derived, keeping map order and the other
	 * turns untouched.
	 */
	private fun replaceOpenTurn(messages: List<ChatMessageUi>): Map<String, TurnSummary> {
		val start = openTurnStart(messages)
		val rebuilt = turnSummaryFor(messages, start, messages.size)
		val out = LinkedHashMap<String, TurnSummary>(current.turns.size + 1)
		var placed = false
		for ((key, summary) in current.turns) {
			if (summary.start == start) {
				if (rebuilt != null) {
					out[rebuilt.endId] = rebuilt
					placed = true
				}
				// A turn that now has no assistant row drops its footer.
			} else {
				out[key] = summary
			}
		}
		if (!placed && rebuilt != null) out[rebuilt.endId] = rebuilt
		return out
	}

	/** Index of the last turn root, or 0 for a leading assistant block. */
	private fun openTurnStart(messages: List<ChatMessageUi>): Int {
		for (index in messages.indices.reversed()) {
			if (messages[index].isTurnRoot) return index
		}
		return 0
	}

	private fun sharesPrefix(old: List<ChatMessageUi>, new: List<ChatMessageUi>): Boolean {
		for (index in old.indices) {
			if (old[index] !== new[index]) return false
		}
		return true
	}

	/** Index of the first row that is not the same instance, or [new.size]. */
	private fun firstDifference(old: List<ChatMessageUi>, new: List<ChatMessageUi>): Int {
		val limit = minOf(old.size, new.size)
		for (index in 0 until limit) {
			if (old[index] !== new[index]) return index
		}
		return if (old.size == new.size) new.size else limit
	}
}
