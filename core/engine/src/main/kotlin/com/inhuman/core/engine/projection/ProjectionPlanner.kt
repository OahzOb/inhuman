package com.inhuman.core.engine.projection

/**
 * Decide which part of a transcript one request should send verbatim.
 *
 * This is the pure half of the continuous-session design: the record never
 * changes, and the question "what does the model see this time" is answered by
 * a function from the transcript plus a budget. The caller assembles the actual
 * request (images, tool schemas, time hints) from the indices this returns.
 *
 * ## What it deliberately does not do
 *
 * - It does not call a model and does not fold anything. It returns the
 *   boundary a fold would end at; generating the new brief is a separate,
 *   paid request owned by the caller.
 * - It does not read a clock, a database or a file. Every input is a value, so
 *   the same inputs always produce the same plan and the plan can be tested on
 *   the JVM.
 * - It does not apply [ProjectionPolicy.maxBatchTokens]: a batch that does not
 *   fit is the caller's loop (`maxAutoFolds` / `maxPrepareFolds`), not a
 *   boundary decision.
 *
 * ## The algorithm
 *
 * 1. Resolve the usable window: the reported window (or the assumed one), minus
 *    an output reserve, with a floor on the reserve.
 * 2. Start from the current brief boundary. Nothing over the high-water line is
 *    touched: the plan simply confirms the current projection.
 * 3. Over the line, walk complete user turns from the end backwards, keeping
 *    the last [ProjectionPolicy.minKeepTurns] turns and adding earlier turns
 *    until the kept tokens reach the target fraction. The boundary is the last
 *    message of the turn before the first kept one, so a tool call and its
 *    results cannot be split.
 * 4. If even the minimum keep is over the hard limit, report
 *    [ProjectionPlan.tooLarge]. The caller explains it; it must not send.
 */
fun planProjection(
	messages: List<ProjectionMessage>,
	briefBoundaryId: String?,
	briefTokens: Int,
	window: Int?,
	maxOutputTokens: Int?,
	overheadTokens: Int?,
	ratio: Double,
	policy: ProjectionPolicy = ProjectionPolicy(),
): ProjectionPlan {
	val overhead = (overheadTokens ?: 0).coerceAtLeast(0)
	val briefIndex = if (briefBoundaryId == null) {
		-1
	} else {
		messages.indexOfFirst { it.id == briefBoundaryId }
	}
	// A brief is only active when its boundary is in this transcript. A stale
	// row from a branch that was switched away from must not hide the prefix it
	// does not describe: ignore it and send from the first message.
	val currentFirst = if (briefIndex >= 0) briefIndex + 1 else 0
	val activatedBrief = if (briefIndex >= 0) briefTokens.coerceAtLeast(0) else 0

	val keepNow = payloadTokens(messages, currentFirst, messages.size, ratio, policy)
	val payloadNow = keepNow.toLong() + activatedBrief
	val totalNow = payloadNow + overhead
	val usable = usableTokens(window, maxOutputTokens, policy)
	val highWaterLine = usable.toDouble() * policy.highWater
	val targetLine = usable.toDouble() * policy.target
	val hardLimitLine = usable.toDouble() * policy.hardLimit

	if (totalNow <= highWaterLine) {
		return noFoldPlan(
			messages = messages,
			currentFirst = currentFirst,
			keepNow = keepNow,
			payloadNow = payloadNow,
			totalNow = totalNow,
			hardLimitLine = hardLimitLine,
		)
	}

	val minKeep = policy.minKeepTurns.coerceAtLeast(1)
	// Fold boundaries are turn boundaries; an in-turn insertion is not one.
	val userStarts = messages.indices.filter {
		messages[it].fromUser && !messages[it].injected && it >= currentFirst
	}
	// Fewer complete turns than the floor: the plan may not fold one, because
	// keeping fewer turns than the floor is not a legal projection.
	if (userStarts.size < minKeep) {
		return noFoldPlan(
			messages = messages,
			currentFirst = currentFirst,
			keepNow = keepNow,
			payloadNow = payloadNow,
			totalNow = totalNow,
			hardLimitLine = hardLimitLine,
		)
	}

	var keptTurnIndex = userStarts.size - 1
	var keptTurns = 1
	var keptTokens = payloadTokens(
		messages,
		userStarts[keptTurnIndex],
		messages.size,
		ratio,
		policy,
	).toLong()
	while ((keptTurns < minKeep || keptTokens.toDouble() < targetLine) && keptTurnIndex > 0) {
		keptTurnIndex--
		keptTurns++
		keptTokens += payloadTokens(
			messages,
			userStarts[keptTurnIndex],
			userStarts[keptTurnIndex + 1],
			ratio,
			policy,
		)
	}

	var keptStart = userStarts[keptTurnIndex]
	// The boundary is the message immediately before the first kept turn, so it
	// always ends a complete turn: a user message, an assistant answer, or the
	// final tool result of a group.
	var boundary = keptStart - 1
	if (boundary < currentFirst) {
		return noFoldPlan(
			messages = messages,
			currentFirst = currentFirst,
			keepNow = keepNow,
			payloadNow = payloadNow,
			totalNow = totalNow,
			hardLimitLine = hardLimitLine,
		)
	}

	var keepTokens = payloadTokens(messages, keptStart, messages.size, ratio, policy)
	var payloadAfter = keepTokens.toLong() + activatedBrief
	var totalAfter = payloadAfter + overhead

	// The target walk can leave the brief plus the kept window over the hard
	// limit when system overhead is large. Tightening to the minimum keep is
	// still legal and is the last chance to make the projection sendable.
	if (totalAfter > hardLimitLine) {
		val minimumStart = userStarts[userStarts.size - minKeep]
		if (minimumStart > keptStart) {
			keptStart = minimumStart
			boundary = keptStart - 1
			keepTokens = payloadTokens(messages, keptStart, messages.size, ratio, policy)
			payloadAfter = keepTokens.toLong() + activatedBrief
			totalAfter = payloadAfter + overhead
		}
	}

	val tooLarge = totalAfter > hardLimitLine
	val firstSent = keptStart
	return ProjectionPlan(
		firstSentIndex = firstSent,
		throughMessageId = messages.getOrNull(boundary)?.id,
		omittedMessages = firstSent - currentFirst,
		omittedTurns = messages.subList(currentFirst, firstSent)
			.count { it.fromUser && !it.injected },
		estimatedTokens = clampToInt(totalAfter),
		estimatedPayloadTokens = clampToInt(payloadAfter),
		foldNeeded = true,
		exceedsHardLimit = tooLarge,
		tooLarge = tooLarge,
		keepVerbatimTokens = keepTokens,
		foldFromIndex = currentFirst,
		foldToIndex = boundary + 1,
	)
}

/**
 * The projection confirmed unchanged: either it fits, or it cannot be folded.
 *
 * A transcript that is over the high-water line but has no complete turn to
 * fold is not [ProjectionPlan.tooLarge] unless it is also over the hard limit:
 * it fits, it is merely not worth pretending otherwise.
 */
private fun noFoldPlan(
	messages: List<ProjectionMessage>,
	currentFirst: Int,
	keepNow: Int,
	payloadNow: Long,
	totalNow: Long,
	hardLimitLine: Double,
): ProjectionPlan {
	val overHardLimit = totalNow > hardLimitLine
	return ProjectionPlan(
		firstSentIndex = currentFirst,
		throughMessageId = messages.getOrNull(currentFirst - 1)?.id,
		omittedMessages = 0,
		omittedTurns = 0,
		estimatedTokens = clampToInt(totalNow),
		estimatedPayloadTokens = clampToInt(payloadNow),
		foldNeeded = false,
		exceedsHardLimit = overHardLimit,
		tooLarge = overHardLimit,
		keepVerbatimTokens = keepNow,
		foldFromIndex = currentFirst,
		foldToIndex = currentFirst,
	)
}

/**
 * The window minus the output reserve.
 *
 * The reserve is a quarter of the window at most, so a model that reports an
 * enormous output ceiling cannot make the request side impossible; it never
 * goes below [ProjectionPolicy.outputReserveMin], because an answer needs room
 * even on a small window. [ProjectionPolicy.assumedWindow] is used when the
 * provider reports nothing, and the result is kept at least 1 so every
 * threshold comparison stays meaningful.
 */
private fun usableTokens(window: Int?, maxOutputTokens: Int?, policy: ProjectionPolicy): Int {
	val resolvedWindow = window?.takeIf { it > 0 } ?: policy.assumedWindow.coerceAtLeast(1)
	val quarter = resolvedWindow / 4
	val outputBudget = maxOutputTokens?.takeIf { it > 0 } ?: quarter
	val reserve = minOf(outputBudget, quarter).coerceAtLeast(policy.outputReserveMin.coerceAtLeast(0))
	return (resolvedWindow - reserve).coerceAtLeast(1)
}

private fun clampToInt(value: Long): Int =
	value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
