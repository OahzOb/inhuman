package com.inhuman.ui.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the request tells the model when a user message was sent.
 *
 * ## Why the timestamp goes inside the user message
 *
 * The model has no clock. In a conversation that is resumed after an hour, or
 * after a night, an unmarked transcript reads as one continuous sitting:
 * "明天再说" and "现在呢" are one exchange away from each other regardless of how
 * much wall-clock time passed. The fix has to travel in a role the model treats
 * as input, and every provider here accepts only system/user/assistant/tool
 * messages. A prefix on the user's own message is the one shape all five
 * adapters already carry, needs no new field, and is what the user asked for.
 *
 * ## Why it is wire-only
 *
 * The prefix is built by `buildWireHistory` when the request is assembled; it
 * is never written to `messages.content`. Storing it would put app-generated
 * text into the transcript, search, Markdown export, branch switching, Rewind
 * and the title, and the same text would then be prefixed a second time on the
 * next turn. The stored row is the user's words, unchanged; the prefix exists
 * only for the request, which is also why the request viewer can show it.
 *
 * ## Why the decision is deterministic from `createdAt`
 *
 * A conversation is rebuilt from the same rows on every turn, so the prefix set
 * must be a function of data that does not move -- otherwise the request prefix
 * would change from turn to turn and the provider's prefix cache would never
 * hit. Each message's gap is measured against the *previous user message's*
 * stored `createdAt`, so a message that qualified once qualifies forever. Only
 * the message being sent now uses the clock, and only for its own prefix.
 */
enum class TimeHintMode {
	/** No prefix at all. The request is exactly the transcript. */
	Off,

	/** Every user message carries its own send time. */
	Every,

	/**
	 * A prefix only where it carries information: the conversation's first
	 * user message (the time anchor the later gaps are relative to), and any
	 * message at least [TimeHintPolicy.gapMinutes] after the previous user
	 * message.
	 */
	AfterGap,
}

/**
 * The user's stored choice of [TimeHintMode] and gap threshold.
 *
 * [gapMinutes] is kept even while the mode is [TimeHintMode.Every] or
 * [TimeHintMode.Off], so switching back to [TimeHintMode.AfterGap] restores the
 * threshold the user picked instead of silently resetting it to the default.
 */
data class TimeHintPolicy(
	val mode: TimeHintMode = TimeHintMode.AfterGap,
	val gapMinutes: Int = DEFAULT_GAP_MINUTES,
) {
	/** The threshold as it is actually applied and stored. */
	fun normalizedGapMinutes(): Int = gapMinutes.coerceIn(MIN_GAP_MINUTES, MAX_GAP_MINUTES)

	/** The value stored under `SecureStore.KEY_TIME_HINTS_MODE`. */
	fun encodeMode(): String = when (mode) {
		TimeHintMode.Off -> "off"
		TimeHintMode.Every -> "every"
		TimeHintMode.AfterGap -> "gap"
	}

	/** The value stored under `SecureStore.KEY_TIME_HINTS_GAP_MINUTES`. */
	fun encodeGapMinutes(): String = normalizedGapMinutes().toString()

	companion object {
		/**
		 * Ten minutes is the stated default: shorter gaps are a conversation in
		 * progress, where a timestamp is noise the model pays tokens for; an
		 * hour is already an absence worth naming, and ten minutes is the
		 * smallest interval that is clearly "away" rather than "thinking".
		 */
		const val DEFAULT_GAP_MINUTES = 10

		/**
		 * A floor of one minute keeps a stored `0` (or a hand-edited value)
		 * from turning every message into a timestamp by accident; the ceiling
		 * is a day, past which every message qualifies anyway.
		 */
		const val MIN_GAP_MINUTES = 1
		const val MAX_GAP_MINUTES = 24 * 60

		/** The gap thresholds the Settings chips offer, in minutes. */
		val GAP_OPTIONS = listOf(5, 10, 30, 60)

		/**
		 * On by default, in the gap mode.
		 *
		 * This is a formatting preference, not a capability: it cannot widen
		 * what the agent reads, writes or runs, and it changes no stored row.
		 * The default is the behaviour the feature exists for; a user who does
		 * not want it turns it off in Settings.
		 */
		val DEFAULT = TimeHintPolicy(TimeHintMode.AfterGap, DEFAULT_GAP_MINUTES)

		/**
		 * Decode the two stored values.
		 *
		 * Absent or unreadable falls back to [DEFAULT] rather than throwing: a
		 * corrupted preference must not stop a conversation from sending, and
		 * losing the choice for one build is recoverable from Settings.
		 */
		fun decode(mode: String?, gapMinutes: String?): TimeHintPolicy {
			val parsedMode = when (mode?.trim()?.lowercase(Locale.ROOT)) {
				"every" -> TimeHintMode.Every
				"off" -> TimeHintMode.Off
				"gap" -> TimeHintMode.AfterGap
				else -> DEFAULT.mode
			}
			val parsedGap = gapMinutes?.trim()?.toIntOrNull() ?: DEFAULT_GAP_MINUTES
			return TimeHintPolicy(parsedMode, parsedGap.coerceIn(MIN_GAP_MINUTES, MAX_GAP_MINUTES))
		}
	}
}

/**
 * One optional prefix per user message, in transcript order.
 *
 * The result is aligned with [timestamps]: a message with a `null` entry gets
 * no prefix. A non-positive timestamp is not rendered — the row still exists in
 * the request, just without a made-up 1970 date — and the gap chain resumes
 * from the last valid stamp.
 *
 * The time is written as the wall clock in [zone], which defaults to the
 * device's current zone. The zone is deliberately *not* in the text: the model
 * needs to know when the message was sent for the user, and an offset it never
 * uses is tokens on every marked message. A device that changes zones while a
 * conversation is open therefore re-renders old rows in the new zone once, and
 * because that changes the request prefix it costs one cache miss — the same
 * price as any other edit to an already-sent message.
 *
 * [DateTimeFormatter] is thread-safe, so the shared instance is reused.
 */
internal fun timeHintPrefixes(
	timestamps: List<Long>,
	policy: TimeHintPolicy,
	zone: ZoneId = ZoneId.systemDefault(),
): List<String?> {
	val out = ArrayList<String?>(timestamps.size)
	if (policy.mode == TimeHintMode.Off) {
		repeat(timestamps.size) { out.add(null) }
		return out
	}
	val gapMillis = policy.normalizedGapMinutes() * 60_000L
	var previousAt: Long? = null
	for (at in timestamps) {
		if (at <= 0) {
			out.add(null)
			continue
		}
		val gap = previousAt?.let { at - it }
		previousAt = at
		val wanted = when (policy.mode) {
			TimeHintMode.Every -> true
			// A first message has no gap to compare, so it is the anchor: in a
			// conversation where every gap is short it is the only place the
			// model can learn what day it is.
			TimeHintMode.AfterGap -> gap == null || gap >= gapMillis
			// Handled above; listed so a future mode cannot silently fall here.
			TimeHintMode.Off -> false
		}
		out.add(if (wanted) formatTimeHint(at, zone) else null)
	}
	return out
}

/**
 * The exact wire prefix: `[2026-11-20 15:26]` and a newline.
 *
 * ISO-like ordering keeps it unambiguous next to whatever the user typed, and
 * the literal brackets are the convention models already read as a timestamp
 * rather than as part of the sentence. Minute precision is deliberate: seconds
 * are noise for "how long was I away", and the newline keeps a user message
 * that begins with a list or a quote from running into the timestamp line.
 */
internal fun formatTimeHint(epochMillis: Long, zone: ZoneId): String {
	val at = Instant.ofEpochMilli(epochMillis).atZone(zone)
	return "[${HINT_FORMATTER.format(at)}]\n"
}

/**
 * What one wire build needs, resolved against the transcript.
 *
 * [byMessageId] is keyed by the stored row id, so the caller can prefix the
 * messages it actually sends without knowing which ones qualified and without
 * recomputing the order. [draftPrefix] is for the composer's text, which has no
 * row yet; it is measured from the same chain, so previewing and then sending
 * cannot disagree about what the draft looks like.
 *
 * This is the seam a JVM test can hold: the decision is [timeHintPrefixes], but
 * "which message, and is the draft counted after the last stored one" is
 * wiring, and the wire itself is not observable on a device.
 */
internal data class TimeHintPlan(
	val byMessageId: Map<String, String?>,
	val draftPrefix: String?,
)

internal fun planTimeHints(
	messages: List<ChatMessageUi>,
	policy: TimeHintPolicy,
	draftAt: Long? = null,
	zone: ZoneId = ZoneId.systemDefault(),
): TimeHintPlan {
	val userMessages = messages.filter { it.fromUser }
	val stamps = buildList {
		userMessages.forEach { add(it.createdAt) }
		if (draftAt != null) add(draftAt)
	}
	val prefixes = timeHintPrefixes(stamps, policy, zone)
	return TimeHintPlan(
		byMessageId = userMessages.indices.associate { userMessages[it].id to prefixes[it] },
		draftPrefix = if (draftAt != null) prefixes.last() else null,
	)
}

/**
 * Fixed pattern and [Locale.ROOT]: the request text must not change with the
 * device's display language, the way a localized formatter would.
 */
private val HINT_FORMATTER: DateTimeFormatter =
	DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
