package com.inhuman.ui.chat

/**
 * A tool call the model has asked for, on its way through the UI.
 *
 * Carries both the accumulated raw JSON and the parsed form, because they are
 * needed at different moments: the raw string while the arguments are still
 * streaming, and the parsed object once they are complete. Parsing early is the
 * classic mistake -- a fragment is not valid JSON.
 */
data class ToolCallUi(
	val index: Int,
	val id: String?,
	val name: String?,
	/** Raw fragments concatenated so far. Not valid JSON until complete. */
	val argumentsJson: String = "",
	val state: State = State.Streaming,
) {
	enum class State {
		/** Arguments still arriving. */
		Streaming,

		/** Complete, waiting for a decision or for execution. */
		Ready,

		/** Executing. */
		Running,

		/** Finished; see the result on the tool message. */
		Done,

		/** The user declined. */
		Declined,

		/** Execution failed. */
		Failed,
	}

	val displayName: String get() = name ?: "tool"
}

/**
 * A request for the user's consent.
 *
 * Held in state rather than raised as a modal as soon as it is detected: a
 * model may ask for several calls at once, and stacking dialogs on top of each
 * other is how a user ends up approving something they never read.
 */
data class PendingApproval(
	val messageId: String,
	val call: ToolCallUi,
	/** What the call would do, phrased for a human rather than for a model. */
	val summary: String,
)
