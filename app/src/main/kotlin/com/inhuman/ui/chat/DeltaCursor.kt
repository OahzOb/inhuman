package com.inhuman.ui.chat

/**
 * Tracks how much of a growing reply has already been handed to the renderer.
 *
 * ## The invariant, and why it needs a type
 *
 * The Markdown renderer's incremental parser **appends whatever it is given**;
 * it holds no copy of the document to compare against
 * (`StreamingMarkdownStateImpl.append` is three lines: append to a
 * `StringBuilder`, append to a `StreamingMarkdownFile`, publish a snapshot).
 * So the caller carries the whole burden of correctness, and there are exactly
 * two ways to get it wrong:
 *
 * - **Send too much**, by re-sending a prefix, and the reply renders twice.
 * - **Send too little**, by skipping a slice, and text silently disappears
 *   from an answer the user is paying for.
 *
 * Neither is visible in a screenshot taken after the stream, which is why this
 * is a type with tests rather than three integers inside a collect block.
 *
 * The text is expected to grow by appending only -- which is how a reply
 * arrives, and what makes "everything after the last cursor" the correct slice.
 * A buffer that *shrank* is treated as a fresh document, because that is the
 * only reading under which the rendered text still equals the reply.
 */
class DeltaCursor(initialText: String = "") {

	/**
	 * How much of the caller's buffer the renderer already has.
	 *
	 * There is deliberately no copy of the text here. It used to keep one
	 * (`seen`) on the theory that a shrinking buffer needed the previous
	 * contents; the shrink case is decided by [sent] alone and re-sends the
	 * new buffer whole, so the copy was written on every publish and never
	 * read -- an O(n) copy of a growing reply, 25 times a second.
	 */
	private var sent = initialText.length

	/**
	 * The slice to send for [current], or null when there is nothing new.
	 *
	 * Returning null rather than an empty string keeps the "nothing happened"
	 * case out of the channel, so a collector is never woken to append "".
	 */
	fun next(current: CharSequence): String? {
		// A shorter buffer means this is not the same document streamed
		// further; start over so the renderer's content matches.
		//
		// Sending everything from the beginning rather than the tail is the
		// point: `sent` indexes into a document that no longer exists, so a
		// slice from it is meaningless. The renderer has no way to be *told*
		// what it should contain -- `StreamingMarkdownState` exposes only
		// `append` -- so re-sending is the only way to make its content equal
		// the reply again.
		if (current.length < sent) {
			sent = current.length
			return if (current.isEmpty()) null else current.toString()
		}

		if (current.length <= sent) return null

		val slice = current.subSequence(sent, current.length).toString()
		sent = current.length
		return slice
	}
}
