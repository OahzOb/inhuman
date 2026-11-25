package com.inhuman.ui.chat

import com.inhuman.core.engine.projection.ProjectionMessage
import com.inhuman.core.engine.projection.ProjectionPolicy
import com.inhuman.core.engine.projection.estimateTokens
import com.inhuman.core.engine.projection.weightedChars

/**
 * The parts of continuous-session maintenance that can be decided without a
 * database, a clock or a provider.
 *
 * They live here rather than inside `ChatViewModel` so the choices that cost
 * money -- how much one request folds, what a stored brief is allowed to look
 * like -- are unit-tested on the JVM. The view model supplies the transcript
 * and the estimator; these functions only decide.
 */

/** A brief after the size rule has been applied. */
internal data class CappedBriefText(val text: String, val truncated: Boolean)

/**
 * Projection weights for one active-branch transcript.
 *
 * The record's own text is what the planner estimates; tool call arguments are
 * counted because they are replayed in the request, and reasoning is counted
 * because a thinking turn must replay it.
 */
internal fun recordMessages(messages: List<ChatMessageUi>): List<ProjectionMessage> =
	messages.map { message ->
		ProjectionMessage(
			id = message.id,
			fromUser = message.fromUser,
			isToolResult = message.isToolResult,
			chars = weightedChars(message.plainText) +
				weightedChars(message.reasoningText) +
				message.toolCalls.sumOf { weightedChars(it.argumentsJson) },
			hasImages = message.attachments.isNotEmpty(),
			injected = message.injected,
		)
	}

/**
 * Cut an over-long brief at a section or line boundary.
 *
 * A silently shortened brief is a lie about coverage, so the caller records
 * [CappedBriefText.truncated] and the sheet shows it. Section boundaries come
 * first: losing a whole tail section is more honest than losing half of one.
 */
internal fun capBrief(raw: String, maxBriefTokens: Int): CappedBriefText {
	val limit = maxBriefTokens.coerceAtLeast(1)
	if (weightedChars(raw) <= limit) return CappedBriefText(raw, truncated = false)
	var cut = raw.length
	for (index in raw.indices.reversed()) {
		if (weightedChars(raw.substring(0, index)) <= limit) {
			cut = index
			break
		}
	}
	val sectionCut = raw.lastIndexOf("\n### ", cut)
	val lineCut = raw.lastIndexOf('\n', cut)
	val at = when {
		sectionCut > 0 -> sectionCut
		lineCut > 0 -> lineCut
		else -> cut
	}
	return CappedBriefText(raw.substring(0, at).trimEnd(), truncated = true)
}

/**
 * The end of one fold batch, capped at [ProjectionPolicy.maxBatchTokens].
 *
 * [targetEnd] is the planner's boundary and is the exclusive end of a complete
 * turn. The result is always a complete-turn boundary too: a user message, its
 * answer, and every tool result in between leave the verbatim window together.
 * A single turn that is already over the cap is folded alone rather than split;
 * the summarizer's own transcript limit bounds that request.
 */
internal fun chooseBatchEnd(
	messages: List<ChatMessageUi>,
	start: Int,
	targetEnd: Int,
	maxBatchTokens: Int,
	ratio: Double,
	policy: ProjectionPolicy,
): Int {
	if (targetEnd <= start) return start
	val projections = recordMessages(messages)
	val turnEnds = buildList {
		for (index in start until messages.size) {
			// The end of a turn is where the *next* turn root starts; an
			// in-turn insertion continues the current turn and must not end a
			// fold batch, or the brief would cover half a turn.
			if (index == messages.lastIndex || messages[index + 1].isTurnRoot) {
				add(index + 1)
			}
		}
	}
	val candidates = turnEnds.filter { it <= targetEnd }
	if (candidates.isEmpty()) return (turnEnds.firstOrNull() ?: start).coerceAtMost(targetEnd)
	var chosen = candidates.first()
	for (end in candidates) {
		val tokens = estimateTokens(
			messages = projections.subList(start, end),
			overheadTokens = null,
			ratio = ratio,
			policy = policy,
		)
		if (tokens <= maxBatchTokens) chosen = end else break
	}
	return chosen
}
