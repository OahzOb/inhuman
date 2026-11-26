package com.inhuman.ui.chat

/**
 * How many list items the transcript's `LazyColumn` renders above `messages`.
 *
 * The banner rows -- the classic-mode compaction summary, the "summarizing"
 * line, and the continuous-session maintenance notice -- are items of their
 * own, so every conversion between a `messages` index and a list index has to
 * add this count back. The conditions mirror the `LazyColumn`'s rendering one
 * for one, including the `!continuous` gate on the two classic rows, which is
 * the point of having a named function: the counter once lived as two inline
 * `if`s beside the jump code, was not updated when the maintenance banner
 * arrived, and for as long as that row was visible the turn badge read one
 * turn ahead and every search or turn jump landed on the banner instead of the
 * message. One function is the single place both sides can be made to agree.
 */
internal fun transcriptLeadingItems(
	continuous: Boolean,
	compaction: Boolean,
	compacting: Boolean,
	maintaining: Boolean,
): Int =
	(if (!continuous && compaction) 1 else 0) +
		(if (!continuous && compacting) 1 else 0) +
		(if (maintaining) 1 else 0)
