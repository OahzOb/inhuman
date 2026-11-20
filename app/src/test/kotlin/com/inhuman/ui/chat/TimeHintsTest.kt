package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The time-hint rule, extracted so it can be pinned on the JVM.
 *
 * The interesting part of this feature is invisible on a device: a request
 * built days ago cannot be re-observed, and the cases that decide whether the
 * prefix helps or hurts are numbers — exactly at the threshold, a gap measured
 * from the wrong message, a clock that moved backwards. Per the rule in
 * `AGENTS.md`, the invariant lives in a pure function and this test carries the
 * argument the device cannot.
 */
class TimeHintsTest {

	private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

	/** A wall-clock instant in the test's zone, so the expected strings are readable. */
	private fun at(hour: Int, minute: Int, day: Int = 20): Long =
		ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, shanghai).toInstant().toEpochMilli()

	private fun prefixes(
		vararg timestamps: Long,
		policy: TimeHintPolicy,
		zone: ZoneId = shanghai,
	): List<String?> = timeHintPrefixes(timestamps.toList(), policy, zone)

	@Test
	fun `off adds nothing`() {
		val policy = TimeHintPolicy(TimeHintMode.Off, 10)
		assertEquals(listOf<String?>(null, null), prefixes(at(9, 0), at(11, 0), policy = policy))
	}

	@Test
	fun `every message carries its own send time`() {
		val policy = TimeHintPolicy(TimeHintMode.Every, 10)
		assertEquals(
			listOf("[2026-11-20 09:00]\n", "[2026-11-20 10:05]\n"),
			prefixes(at(9, 0), at(10, 5), policy = policy),
		)
	}

	@Test
	fun `gap mode marks the first message and nothing under the threshold`() {
		val policy = TimeHintPolicy(TimeHintMode.AfterGap, 10)
		assertEquals(
			listOf("[2026-11-20 09:00]\n", null),
			prefixes(at(9, 0), at(9, 9), policy = policy),
		)
	}

	@Test
	fun `exactly the threshold qualifies, because the choice says at least`() {
		val policy = TimeHintPolicy(TimeHintMode.AfterGap, 10)
		val prefixes = prefixes(at(9, 0), at(9, 10), policy = policy)
		assertEquals("[2026-11-20 09:10]\n", prefixes[1])
	}

	@Test
	fun `the gap is measured from the previous user message, not from the last one`() {
		// Two hours between the first two, five minutes for the third: a
		// request that measured "time since anything happened" would mark the
		// third after a long tool run, and an ordinary quick follow-up would
		// become a timestamp.
		val policy = TimeHintPolicy(TimeHintMode.AfterGap, 10)
		val prefixes = prefixes(at(9, 0), at(11, 0), at(11, 5), policy = policy)
		assertEquals("[2026-11-20 09:00]\n", prefixes[0])
		assertEquals("[2026-11-20 11:00]\n", prefixes[1])
		assertEquals(null, prefixes[2])
	}

	@Test
	fun `a backwards clock does not qualify`() {
		val policy = TimeHintPolicy(TimeHintMode.AfterGap, 10)
		val prefixes = prefixes(at(10, 0), at(9, 0), policy = policy)
		assertEquals(null, prefixes[1])
	}

	@Test
	fun `a missing timestamp is not rendered as 1970, and the chain resumes`() {
		val policy = TimeHintPolicy(TimeHintMode.AfterGap, 10)
		val prefixes = prefixes(at(9, 0), 0L, at(9, 30), policy = policy)
		assertEquals(null, prefixes[1])
		// The gap after the missing row is measured from the last valid stamp.
		assertEquals("[2026-11-20 09:30]\n", prefixes[2])
	}

	@Test
	fun `the wall clock is the device zone, so changing zones re-renders old rows`() {
		val policy = TimeHintPolicy(TimeHintMode.Every, 10)
		assertEquals(
			listOf("[2026-11-20 01:00]\n"),
			prefixes(at(9, 0), policy = policy, zone = ZoneId.of("UTC")),
		)
	}

	@Test
	fun `the policy round-trips through the two stored values`() {
		val cases = listOf(
			TimeHintPolicy(TimeHintMode.Off, 10),
			TimeHintPolicy(TimeHintMode.Every, 10),
			TimeHintPolicy(TimeHintMode.AfterGap, 30),
		)
		for (policy in cases) {
			assertEquals(
				policy,
				TimeHintPolicy.decode(policy.encodeMode(), policy.encodeGapMinutes()),
			)
		}
	}

	@Test
	fun `an unreadable stored policy falls back to the default`() {
		assertEquals(TimeHintPolicy.DEFAULT, TimeHintPolicy.decode(null, null))
		assertEquals(TimeHintPolicy.DEFAULT, TimeHintPolicy.decode("nonsense", "not a number"))
		// The mode is stored by the app in lower case; decode tolerates case so
		// a value typed by a person debugging the store is not ignored.
		assertEquals(TimeHintMode.AfterGap, TimeHintPolicy.decode("GAP", "10").mode)
	}

	@Test
	fun `the threshold is clamped on read`() {
		assertEquals(
			TimeHintPolicy.MIN_GAP_MINUTES,
			TimeHintPolicy.decode("gap", "0").normalizedGapMinutes(),
		)
		assertEquals(
			TimeHintPolicy.MAX_GAP_MINUTES,
			TimeHintPolicy.decode("gap", "99999").normalizedGapMinutes(),
		)
	}

	@Test
	fun `the default is after a gap of ten minutes and the options offer it`() {
		assertEquals(TimeHintMode.AfterGap, TimeHintPolicy.DEFAULT.mode)
		assertEquals(10, TimeHintPolicy.DEFAULT.normalizedGapMinutes())
		assertTrue(TimeHintPolicy.DEFAULT_GAP_MINUTES in TimeHintPolicy.GAP_OPTIONS)
	}

	@Test
	fun `the plan keys hints by message id and leaves non-user rows alone`() {
		val policy = TimeHintPolicy(TimeHintMode.AfterGap, 10)
		val messages = listOf(
			ChatMessageUi(id = "u1", fromUser = true, createdAt = at(9, 0)),
			ChatMessageUi(id = "a1", fromUser = false, createdAt = at(9, 1)),
			ChatMessageUi(id = "t1", fromUser = false, isToolResult = true, createdAt = at(9, 2)),
			ChatMessageUi(id = "u2", fromUser = true, createdAt = at(9, 5)),
		)

		val plan = planTimeHints(messages, policy, zone = shanghai)

		assertEquals("[2026-11-20 09:00]\n", plan.byMessageId["u1"])
		assertFalse("a1" in plan.byMessageId)
		assertFalse("t1" in plan.byMessageId)
		// Assistant and tool rows between the two user messages do not shorten
		// the gap: it is five minutes, under the threshold.
		assertEquals(null, plan.byMessageId["u2"])
		assertEquals(null, plan.draftPrefix)
	}

	@Test
	fun `the draft is measured after the last stored user message`() {
		val policy = TimeHintPolicy(TimeHintMode.AfterGap, 10)
		val messages = listOf(
			ChatMessageUi(id = "u1", fromUser = true, createdAt = at(9, 0)),
		)

		val soon = planTimeHints(messages, policy, draftAt = at(9, 3), zone = shanghai)
		assertEquals(null, soon.draftPrefix)

		val later = planTimeHints(messages, policy, draftAt = at(9, 20), zone = shanghai)
		assertEquals("[2026-11-20 09:20]\n", later.draftPrefix)
		// The stored message keeps its own anchor; the draft is not in the map
		// because it has no row id yet.
		assertEquals("[2026-11-20 09:00]\n", later.byMessageId["u1"])
		assertEquals(1, later.byMessageId.size)
	}
}
