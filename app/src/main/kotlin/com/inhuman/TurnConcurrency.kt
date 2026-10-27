package com.inhuman

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many model turns this process may run at once.
 *
 * ## Why a hard number
 *
 * A phone has one network path and one battery, and a user can read one reply
 * at a time. Two is the deliberate ceiling: one turn the user is looking at and
 * one running in the background with its own notification. A third stream
 * competes for the same radio and CPU, makes every reply slower, and cannot be
 * read simultaneously -- so it is refused rather than queued. Queuing would be
 * worse: the user would believe a turn had started when it had not, and there is
 * no screen that would show the queue.
 *
 * This is **not** a user setting. Like the tool-round budget it is a bound on
 * resource use, not a capability; `docs/08-port-philosophy.md` §1.1 refuses a
 * mode that would let it be raised into "unlimited".
 *
 * ## What it is not
 *
 * It does not replace the per-conversation gate: `ChatViewModel.isStreaming`
 * still allows exactly one turn in one conversation, so this counter bounds
 * *different* conversations. Tools remain serial inside a turn; see the engine
 * test that pins concurrent execution at one.
 *
 * The counter is process-local. A process death drops it with everything else
 * in memory, and the resumed turn acquires a fresh permit through the normal
 * path.
 */
object TurnConcurrency {

	/** One visible turn plus one in the background. */
	const val MAX_ACTIVE_TURNS = 2

	private val active = AtomicInteger(0)

	/** For tests and diagnostics; never used to make a decision by itself. */
	val current: Int get() = active.get()

	/**
	 * Take one slot, or return false when the ceiling is reached.
	 *
	 * The compare-and-set loop is what makes two taps on two conversations
	 * unable to both pass a check-then-increment.
	 */
	fun tryAcquire(): Boolean {
		while (true) {
			val now = active.get()
			if (now >= MAX_ACTIVE_TURNS) return false
			if (active.compareAndSet(now, now + 1)) return true
		}
	}

	/**
	 * Return a slot.
	 *
	 * Clamped at zero: a double release is a bug, but it must not make the
	 * ceiling larger than [MAX_ACTIVE_TURNS] for every later turn.
	 */
	fun release() {
		while (true) {
			val now = active.get()
			if (now == 0) return
			if (active.compareAndSet(now, now - 1)) return
		}
	}
}