package com.inhuman.ui.agents

import com.inhuman.core.engine.roleplay.RoleplayCard
import com.inhuman.core.engine.roleplay.RoleplayCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping between an assistant draft and its stored row.
 *
 * This is the part of the role-play card that can be wrong without looking wrong:
 * a card decoded on edit but not encoded on save would silently drop the
 * character on the next assistant change, and a card-only assistant would show
 * a disabled Save with nothing to fix. It is pure data, so a JVM test covers it
 * without a device or Robolectric.
 */
class AgentDraftRoleplayTest {

	@Test
	fun `a card-only character can be saved`() {
		val draft = draft(
			name = "Aria",
			systemPrompt = "",
			card = RoleplayCard(description = "A courier who talks in clipped sentences."),
		)

		assertTrue(draft.canSave)
	}

	@Test
	fun `a plain assistant still needs a prompt`() {
		assertFalse(draft(name = "Aria", systemPrompt = "").canSave)
		assertTrue(draft(name = "Aria", systemPrompt = "Be brief.").canSave)
	}

	@Test
	fun `a missing name still blocks saving a card-only character`() {
		val draft = draft(
			name = "",
			systemPrompt = "",
			card = RoleplayCard(greeting = "You again."),
		)

		assertFalse(draft.canSave)
	}

	@Test
	fun `the card round-trips through the stored json`() {
		val card = RoleplayCard(
			description = "A courier.",
			personality = "Clipped, impatient.",
			scenario = "A rain-soaked depot.",
			greeting = "You again.",
			exampleDialogue = "Aria: Late is late.",
			boundaries = "Never describes the player's thoughts.",
			authorNote = "Keep sentences short.",
			temperature = 1.1,
			topP = 0.9,
			maxTokens = 2_048,
		)

		val stored = draft(name = "Aria", systemPrompt = "", card = card)
			.toEntity(now = 7L)
			.rpJson

		assertEquals(card, RoleplayCodec.decodeCard(stored))
	}

	@Test
	fun `an empty card is stored as the value that turns the feature off`() {
		val stored = draft(name = "Aria", systemPrompt = "Be brief.")
			.toEntity(now = 7L)
			.rpJson

		// `""` is what `RoleplayCodec` and the request builder read as "no card";
		// `"{}"` would be a card that is present but empty, which is a different
		// state to explain.
		assertEquals("", stored)
	}

	@Test
	fun `the assistant keeps its own fields when a card is attached`() {
		val draft = draft(
			name = "  Aria  ",
			systemPrompt = "  Be brief.  ",
			card = RoleplayCard(description = "A courier."),
		)

		val entity = draft.toEntity(now = 7L)

		assertEquals("Aria", entity.name)
		assertEquals("Be brief.", entity.systemPrompt)
		assertEquals(draft.id, entity.id)
		assertEquals(7L, entity.updatedAt)
	}

	private fun draft(
		name: String,
		systemPrompt: String,
		card: RoleplayCard = RoleplayCard(),
	) = AgentDraft(
		id = "agent-1",
		isNew = true,
		isDefault = false,
		name = name,
		systemPrompt = systemPrompt,
		card = card,
		model = null,
		reasoningEffort = null,
		thinking = false,
		createdAt = 1L,
	)
}
