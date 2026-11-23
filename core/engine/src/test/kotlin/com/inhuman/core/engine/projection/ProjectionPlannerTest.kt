package com.inhuman.core.engine.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection planner is a pure function over values, so every acceptance
 * property in the proposal is tested here without a device, a database or a
 * network. The cases are the ones a wrong boundary would break: short
 * conversations, complete-turn cuts, tool groups, a stale brief boundary,
 * an impossible transcript, and the estimator's unit rules.
 */
class ProjectionPlannerTest {

	private val policy = ProjectionPolicy()

	// 10_000 window, 2_000 output reserve -> 8_000 usable:
	// high water 5_200, target 3_200, hard limit 6_800.
	private val smallWindow = 10_000
	private val maxOutput = 2_000
	private val smallPolicy = ProjectionPolicy()

	@Test
	fun `a short conversation is sent whole`() {
		val messages = conversation(turns = 1, userChars = 100, assistantChars = 100)
		val plan = plan(messages, overhead = 1_000, window = 100_000)

		assertFalse(plan.foldNeeded)
		assertFalse(plan.tooLarge)
		assertFalse(plan.exceedsHardLimit)
		assertEquals(0, plan.firstSentIndex)
		assertNull(plan.throughMessageId)
		assertEquals(0, plan.omittedMessages)
		assertEquals(0, plan.omittedTurns)
		assertEquals(210, plan.keepVerbatimTokens)
		assertEquals(210, plan.estimatedPayloadTokens)
		assertEquals(1_210, plan.estimatedTokens)
		assertEquals(0, plan.foldFromIndex)
		assertEquals(0, plan.foldToIndex)
	}

	@Test
	fun `weighted characters score CJK at one and ASCII at a quarter`() {
		assertEquals(2, weightedChars("你好"))
		assertEquals(2, weightedChars("abcdefgh"))
		assertEquals(1, weightedChars("hello"))
		assertEquals(0, weightedChars(""))
	}

	@Test
	fun `the token estimate applies the margin images and overhead`() {
		val message = message("m", fromUser = true, chars = 100)

		assertEquals(105, estimateTokens(listOf(message), overheadTokens = null, ratio = 1.0, policy = policy))
		assertEquals(155, estimateTokens(listOf(message), overheadTokens = 50, ratio = 1.0, policy = policy))
		assertEquals(
			1_500,
			estimateTokens(
				listOf(message(id = "image", fromUser = false, chars = 0, hasImages = true)),
				overheadTokens = null,
				ratio = 1.0,
				policy = policy,
			),
		)
	}

	@Test
	fun `an empty estimate is safe with and without overhead`() {
		assertEquals(0, estimateTokens(emptyList(), overheadTokens = null, ratio = 1.0, policy = policy))
		assertEquals(100, estimateTokens(emptyList(), overheadTokens = 100, ratio = 1.0, policy = policy))
	}

	@Test
	fun `the ratio is clamped to the calibration band`() {
		val message = message("m", fromUser = true, chars = 100)

		assertEquals(210, estimateTokens(listOf(message), overheadTokens = null, ratio = 10.0, policy = policy))
		assertEquals(53, estimateTokens(listOf(message), overheadTokens = null, ratio = 0.1, policy = policy))
	}

	@Test
	fun `calibrate uses the measurement and clamps it`() {
		assertEquals(1.2, calibrate(actualPromptTokens = 1_200, estimatedPayloadTokens = 1_000), 0.0)
		assertEquals(0.5, calibrate(actualPromptTokens = 100, estimatedPayloadTokens = 1_000), 0.0)
		assertEquals(2.0, calibrate(actualPromptTokens = 5_000, estimatedPayloadTokens = 1_000), 0.0)
	}

	@Test
	fun `calibrate falls back to the previous ratio when a side is missing`() {
		assertEquals(1.4, calibrate(actualPromptTokens = null, estimatedPayloadTokens = 1_000, previousRatio = 1.4), 0.0)
		assertEquals(1.4, calibrate(actualPromptTokens = 1_200, estimatedPayloadTokens = 0, previousRatio = 1.4), 0.0)
		assertEquals(1.0, calibrate(actualPromptTokens = null, estimatedPayloadTokens = null), 0.0)
		assertEquals(1.2, calibrate(actualPromptTokens = 1_200, estimatedPayloadTokens = 1_000, previousRatio = 3.0), 0.0)
	}

	@Test
	fun `a long conversation folds at a complete turn boundary`() {
		val messages = conversation(turns = 6)
		val plan = plan(messages, window = smallWindow, maxOutput = maxOutput)

		assertTrue(plan.foldNeeded)
		assertFalse(plan.tooLarge)
		// 6 turns at 2_100 tokens each. Keeping the last two reaches the target
		// (3_200) and satisfies the minimum keep, so exactly the last two stay.
		assertEquals(8, plan.firstSentIndex)
		assertEquals(8, plan.foldToIndex)
		assertEquals(0, plan.foldFromIndex)
		assertEquals(8, plan.omittedMessages)
		assertEquals(4, plan.omittedTurns)
		assertEquals(4_200, plan.keepVerbatimTokens)
		assertEquals(4_200, plan.estimatedPayloadTokens)
		assertEquals(4_200, plan.estimatedTokens)
		assertEquals("a3", plan.throughMessageId)
		assertTrue(messages[plan.firstSentIndex].fromUser)
		assertTrue(
			"kept window must reach the target fraction",
			plan.keepVerbatimTokens >= 0.40 * (smallWindow - maxOutput),
		)
	}

	@Test
	fun `a fold never splits a tool group`() {
		val messages = toolConversation(turns = 4)
		val plan = plan(messages, window = smallWindow, maxOutput = maxOutput)

		assertTrue(plan.foldNeeded)
		assertEquals(8, plan.firstSentIndex)
		assertTrue(messages[plan.firstSentIndex].fromUser)
		assertEquals(8, plan.foldToIndex)
		// The boundary is the final assistant message of turn 1; the call and
		// its result at indexes 5 and 6 stay together in the folded range.
		assertEquals("f1", plan.throughMessageId)
		assertFalse(messages[7].isToolResult)
		assertTrue(plan.foldToIndex > 6)
		assertEquals(8, plan.omittedMessages)
		assertEquals(2, plan.omittedTurns)
	}

	@Test
	fun `a stale brief boundary is ignored`() {
		val messages = conversation(turns = 1, userChars = 10, assistantChars = 10)
		val plan = plan(
			messages,
			briefBoundaryId = "not-in-this-transcript",
			briefTokens = 50_000,
			window = 100_000,
		)

		assertEquals(0, plan.firstSentIndex)
		assertNull(plan.throughMessageId)
		assertEquals(22, plan.estimatedPayloadTokens)
		assertEquals(22, plan.estimatedTokens)
		assertEquals(0, plan.foldFromIndex)
	}

	@Test
	fun `an active brief starts the projection after its boundary`() {
		val messages = conversation(turns = 2, userChars = 100, assistantChars = 100)
		val plan = plan(
			messages,
			briefBoundaryId = "a0",
			briefTokens = 40,
			window = 100_000,
		)

		assertFalse(plan.foldNeeded)
		assertEquals(2, plan.firstSentIndex)
		assertEquals("a0", plan.throughMessageId)
		assertEquals(210, plan.keepVerbatimTokens)
		assertEquals(250, plan.estimatedPayloadTokens)
		assertEquals(250, plan.estimatedTokens)
		assertEquals(2, plan.foldFromIndex)
		assertEquals(2, plan.foldToIndex)
	}

	@Test
	fun `over the hard limit with no foldable turn is too large`() {
		val messages = conversation(turns = 2, userChars = 4_000, assistantChars = 4_000)
		val plan = plan(messages, window = smallWindow, maxOutput = maxOutput)

		assertFalse(plan.foldNeeded)
		assertTrue(plan.tooLarge)
		assertTrue(plan.exceedsHardLimit)
		assertEquals(0, plan.firstSentIndex)
		assertEquals(16_800, plan.estimatedTokens)
	}

	@Test
	fun `a transcript with fewer turns than the minimum keep is too large`() {
		val messages = conversation(turns = 1, userChars = 4_000, assistantChars = 4_000)
		val plan = plan(messages, window = smallWindow, maxOutput = maxOutput)

		assertFalse(plan.foldNeeded)
		assertTrue(plan.tooLarge)
		assertTrue(plan.exceedsHardLimit)
		assertEquals(0, plan.firstSentIndex)
	}

	@Test
	fun `a fold that cannot get under the hard limit is too large`() {
		val messages = conversation(turns = 3, userChars = 4_000, assistantChars = 4_000)
		val plan = plan(messages, window = smallWindow, maxOutput = maxOutput)

		assertTrue(plan.foldNeeded)
		assertTrue(plan.tooLarge)
		assertTrue(plan.exceedsHardLimit)
		assertEquals(2, plan.firstSentIndex)
		assertEquals(2, plan.foldToIndex)
		assertEquals(2, plan.omittedMessages)
		assertEquals(1, plan.omittedTurns)
	}

	@Test
	fun `a projection over the hard limit is fixed by its planned fold`() {
		val messages = conversation(turns = 6)
		val plan = plan(messages, window = smallWindow, maxOutput = maxOutput)

		// The unplanned transcript is 12_600 tokens, well over 6_800, but the
		// planned projection is 4_200: the useful answer is "can be sent".
		assertFalse(plan.tooLarge)
		assertFalse(plan.exceedsHardLimit)
	}

	@Test
	fun `over the high water line without a foldable turn is sent unchanged`() {
		val messages = conversation(turns = 2, userChars = 1_300, assistantChars = 1_300)
		val plan = plan(messages, window = smallWindow, maxOutput = maxOutput)

		assertFalse(plan.foldNeeded)
		assertFalse(plan.tooLarge)
		assertFalse(plan.exceedsHardLimit)
		assertEquals(0, plan.firstSentIndex)
		assertEquals(5_460, plan.estimatedTokens)
	}

	@Test
	fun `an unknown window falls back to the assumed one`() {
		val plan = plan(
			messages = emptyList(),
			overhead = 60_000,
			window = null,
			maxOutput = null,
		)

		assertFalse(plan.foldNeeded)
		assertTrue(plan.tooLarge)
		assertTrue(plan.exceedsHardLimit)
		assertEquals(60_000, plan.estimatedTokens)
	}

	@Test
	fun `a short projection with an unknown window still fits`() {
		val messages = conversation(turns = 1, userChars = 100, assistantChars = 100)
		val plan = plan(messages, overhead = 0, window = null, maxOutput = null)

		assertFalse(plan.foldNeeded)
		assertFalse(plan.tooLarge)
		assertEquals(210, plan.estimatedTokens)
	}

	@Test
	fun `the same inputs always produce the same plan`() {
		val messages = conversation(turns = 6)
		val first = plan(messages, window = smallWindow, maxOutput = maxOutput)
		val second = plan(messages, window = smallWindow, maxOutput = maxOutput)

		assertEquals(first, second)
		assertEquals(first.hashCode(), second.hashCode())
	}

	@Test
	fun `a NaN ratio falls back to the policy default`() {
		val message = message("m", fromUser = true, chars = 100)
		// 105 weighted-margin tokens times the 1.15 default ratio.
		assertEquals(121, estimateTokens(listOf(message), overheadTokens = null, ratio = Double.NaN, policy = policy))
	}

	private fun plan(
		messages: List<ProjectionMessage>,
		briefBoundaryId: String? = null,
		briefTokens: Int = 0,
		window: Int? = null,
		maxOutput: Int? = null,
		overhead: Int? = null,
	): ProjectionPlan = planProjection(
		messages = messages,
		briefBoundaryId = briefBoundaryId,
		briefTokens = briefTokens,
		window = window,
		maxOutputTokens = maxOutput,
		overheadTokens = overhead,
		ratio = 1.0,
		policy = smallPolicy,
	)

	private fun conversation(
		turns: Int,
		userChars: Int = 1_000,
		assistantChars: Int = 1_000,
	): List<ProjectionMessage> = buildList {
		repeat(turns) { index ->
			add(message("u$index", fromUser = true, chars = userChars))
			add(message("a$index", fromUser = false, chars = assistantChars))
		}
	}

	private fun toolConversation(turns: Int, chars: Int = 1_000): List<ProjectionMessage> = buildList {
		repeat(turns) { index ->
			add(message("u$index", fromUser = true, chars = chars))
			add(message("a$index", fromUser = false, chars = chars))
			add(message("t$index", fromUser = false, isToolResult = true, chars = chars))
			add(message("f$index", fromUser = false, chars = chars))
		}
	}

	private fun message(
		id: String,
		fromUser: Boolean,
		isToolResult: Boolean = false,
		chars: Int = 0,
		hasImages: Boolean = false,
	): ProjectionMessage = ProjectionMessage(
		id = id,
		fromUser = fromUser,
		isToolResult = isToolResult,
		chars = chars,
		hasImages = hasImages,
	)
}
