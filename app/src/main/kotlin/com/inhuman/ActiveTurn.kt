package com.inhuman

/**
 * The one turn the Stop notification action may cancel.
 *
 * A notification has no reference to the composable's view model. A tiny
 * process-wide hand-off is smaller than a broadcast receiver, and the
 * foreground service guarantees the process is alive while the action exists.
 *
 * The token prevents an old view model's `onCleared` from clearing a newer
 * turn that happened to start before it was disposed.
 */
object ActiveTurn {
	private val lock = Any()
	private var token: Any? = null
	private var stop: (() -> Unit)? = null

	fun register(owner: Any, action: () -> Unit) {
		synchronized(lock) {
			token = owner
			stop = action
		}
	}

	fun clear(owner: Any) {
		synchronized(lock) {
			if (token === owner) {
				token = null
				stop = null
			}
		}
	}

	/** Called from the notification action; safe when no turn is active. */
	fun requestStop() {
		val action = synchronized(lock) { stop }
		action?.invoke()
	}
}
