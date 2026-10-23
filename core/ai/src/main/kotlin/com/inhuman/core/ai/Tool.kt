package com.inhuman.core.ai

import kotlinx.serialization.json.JsonObject

/**
 * Something the model can ask the app to do.
 *
 * ## Why the approval gate lives on the tool
 *
 * `needsApproval` is evaluated **per call, against the actual arguments**,
 * rather than being a property of the tool as a whole. That distinction is what
 * makes a sensible policy expressible: writing a new file may be fine while
 * overwriting an existing one is not, and deleting inside a scratch directory
 * may be fine while deleting a file the user uploaded is not. A boolean baked
 * into the tool cannot say any of that, so every such tool ends up asking for
 * confirmation on every call -- which trains the user to approve without
 * reading, and is worse than no gate at all.
 *
 * ## Why `execute` returns parts rather than a string
 *
 * A tool legitimately returns more than text: an image it generated, a diff, a
 * structured error. Returning a String forces every tool with richer output to
 * encode it as prose and have the UI decode it again.
 */
interface Tool {

	/** Name the model calls. Must match the schema exactly. */
	val name: String

	/** Shown to the model. This is prompt text, and it is worth writing well. */
	val description: String

	/**
	 * JSON Schema for the arguments, as a plain map.
	 *
	 * A raw map rather than a Kotlin type: the provider passes it through
	 * unexamined, so modelling all of JSON Schema would be a large amount of
	 * code buying nothing. A documented `Map<String, Any?>` is both less code
	 * and more capable.
	 */
	val parameters: Map<String, Any?>

	/**
	 * Whether calling this changes anything that outlives the call.
	 *
	 * ## What this is for
	 *
	 * It records that replaying the tool would be a second real write: a file
	 * written twice, a directory moved onto itself, a delete attempted against
	 * something already gone. It is **not** a blanket "this reply cannot be
	 * regenerated" flag. Regeneration is sent with no tools offered, so it can
	 * rewrite the final text from the existing transcript without replaying
	 * anything. The flag remains the fact a future replay-with-tools feature,
	 * or a UI warning, would need.
	 *
	 * Reading the time again costs nothing. Writing the same file again does
	 * not.
	 *
	 * ## Why this is not `needsApproval`
	 *
	 * They answer different questions and neither implies the other. Approval
	 * asks "may this happen?" *before* it happens. This asks "would replaying
	 * it with tools change something?" *afterwards*. `write_file` mutates and
	 * needs no approval; a future tool could reasonably need approval without
	 * mutating -- an expensive search the user pays for, say -- and it could
	 * be replayed for money rather than correctness.
	 *
	 * ## Why this is deliberately not defaulted
	 *
	 * A default would be wrong about half the tools that will ever exist, and
	 * wrong in the dangerous direction for any caller that does replay with
	 * tools. Every tool states its answer, which is cheap, and it puts the
	 * question in front of whoever adds the next one.
	 */
	val mutates: Boolean

	/**
	 * Whether the effects can be undone inside the app's boundaries.
	 *
	 * Default true: a tool either does not change anything, or changes only a
	 * workspace copy. `ssh_exec` overrides it to false. This is what rollback
	 * consults: a mutating turn without a restorable snapshot must not silently
	 * be deleted.
	 */
	val reversible: Boolean get() = true
	/**
	 * Whether this particular call needs the user's consent first.
	 *
	 * Default is no approval, which is right for reads. Anything that writes,
	 * deletes or reaches outside the workspace should override this.
	 */
	fun needsApproval(arguments: JsonObject): Boolean = false

	/**
	 * Normalise the arguments before anything judges or runs them.
	 *
	 * Models do not always send the object the schema describes. The SSH tools
	 * accept either the connection fields at the top level or the whole object
	 * wrapped in a string field named `arguments`; the tool can unwrap that,
	 * but only if it is asked to before the call is read.
	 *
	 * This used to be done by the legacy turn loop, so the approval gate, the
	 * tool and the consent dialogue could each see a different shape of the
	 * same call depending on which host ran it. Putting it on the tool makes
	 * "what the model asked for" one value again: the engine normalises once,
	 * the gate normalises once, and both pass the result on.
	 *
	 * Default: unchanged. Only a tool that has a wrapped shape overrides it.
	 */
	fun normalize(arguments: JsonObject): JsonObject = arguments

	/**
	 * Run the call.
	 *
	 * Must not throw for an ordinary failure -- a missing file, a quota
	 * exceeded, a refused path are all normal outcomes that belong in the
	 * returned text, where the model can read them and adapt. A thrown
	 * exception ends the turn and tells the model nothing.
	 */
	suspend fun execute(arguments: JsonObject): ToolResult
}

/**
 * What a tool produced.
 *
 * [content] is written for the model to read, so it should be informative
 * rather than merely correct: "file not found: notes.txt" lets the model try a
 * different name, whereas "error" does not.
 */
data class ToolResult(
	val content: String,
	val isError: Boolean = false,
) {
	companion object {
		fun ok(content: String) = ToolResult(content, isError = false)

		/**
		 * A refusal or failure.
		 *
		 * Marked as an error so the transcript can show it differently, but
		 * still fed back to the model -- a tool result the model never sees is
		 * a turn that cannot recover.
		 */
		fun error(message: String) = ToolResult(message, isError = true)
	}
}
