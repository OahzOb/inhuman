package com.inhuman.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the regenerate guard tracks the transcript, or goes stale.
 *
 * ## The bug this exists to prevent
 *
 * `currentTurnMutated` used to be a stored field, assigned in `reload()` and
 * raised in `executeCalls`. `send()` never cleared it, so after a single
 * mutating turn **every later reply in the session** claimed it could not be
 * regenerated. Reopening the conversation did not help either: the chat view
 * model is keyed and activity-scoped, so the same instance came back without
 * `init` or `reload()` running again, and only a process restart or a branch
 * switch recalculated the flag.
 *
 * A field that has to be cleared by every future caller is a field that will
 * not be. These tests assert the property that makes clearing unnecessary --
 * the answer is read from the transcript, so it cannot disagree with it.
 */
class ChatUiStateTest {

	private fun message(
		id: String,
		fromUser: Boolean = false,
		mutated: Boolean = false,
	) = ChatMessageUi(id = id, fromUser = fromUser, mutated = mutated)

	@Test
	fun `a turn that changed files cannot be regenerated`() {
		val state = ChatUiState(
			messages = listOf(
				message("u1", fromUser = true),
				message("a1", mutated = true),
				message("a2"),
			),
		)

		assertTrue(state.currentTurnMutated)
	}

	@Test
	fun `the turn after a mutating turn can be regenerated`() {
		// The exact sequence that used to be broken, and the reason the bug was
		// reported as "regenerate is disabled for the rest of the session".
		val previousTurn = listOf(
			message("u1", fromUser = true),
			message("a1", mutated = true),
		)
		val thisTurn = listOf(
			message("u2", fromUser = true),
			message("a2"),
		)
		val state = ChatUiState(messages = previousTurn + thisTurn)

		assertFalse("a write two exchanges ago is not rewritten by answering again", state.currentTurnMutated)
	}

	@Test
	fun `the mutation on the asking message counts, not the summary after it`() {
		// The shape that defeats the obvious check. A turn that uses a tool is
		// usually two messages: the model asks for the write, the tool answers,
		// and then the model says "done". The mutation is recorded on the first,
		// so reading it off the *last* reply gives the wrong answer for the
		// common case -- which is the case the guard exists for.
		val state = ChatUiState(
			messages = listOf(
				message("u1", fromUser = true),
				message("a1", mutated = true),
				message("t1", fromUser = false),
				message("a2", mutated = false),
			),
		)

		assertTrue(state.currentTurnMutated)
	}

	@Test
	fun `nothing to regenerate is not blocked`() {
		assertFalse(ChatUiState().currentTurnMutated)
		assertFalse(ChatUiState(messages = listOf(message("a1"))).currentTurnMutated)
		assertFalse(
			ChatUiState(messages = listOf(message("u1", fromUser = true))).currentTurnMutated,
		)
	}

	@Test
	fun `the answer follows the transcript if the transcript changes`() {
		// The property that makes the field unnecessary: there is no separate
		// copy to keep in step, so a screen cannot read one answer while the
		// guard reads another.
		val mutatedTurn = ChatUiState(
			messages = listOf(message("u1", fromUser = true), message("a1", mutated = true)),
		)
		assertTrue(mutatedTurn.currentTurnMutated)

		val nextTurn = mutatedTurn.copy(
			messages = mutatedTurn.messages + listOf(
				message("u2", fromUser = true),
				message("a2"),
			),
		)
		assertFalse(nextTurn.currentTurnMutated)
	}
}
