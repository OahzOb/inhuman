package com.inhuman.core.engine.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The freeze is the feature: once a scene has a snapshot, nothing about the
 * live assistant may leak into it, and a snapshot with no card must not fall
 * back to one.
 */
class RoleplayPersonaTest {

	private fun snapshot(
		prompt: String = "SYS-SNAP",
		name: String = "SnapChar",
		card: String = """{"description":"SNAP-CARD"}""",
	) = RoleplaySnapshot(systemPrompt = prompt, characterName = name, card = card)

	@Test
	fun `a pinned scene uses the snapshot and ignores the live assistant`() {
		val persona = RoleplayPersonaResolver.resolve(
			snapshot = snapshot(),
			liveSystemPrompt = "SYS-LIVE",
			liveName = "LiveChar",
			liveCard = """{"description":"LIVE-CARD"}""",
		)

		assertEquals("SYS-SNAP", persona.systemPrompt)
		assertEquals("SnapChar", persona.characterName)
		assertEquals("SNAP-CARD", persona.card?.description)
	}

	@Test
	fun `a pinned scene with no card does not resurrect the live card`() {
		val persona = RoleplayPersonaResolver.resolve(
			snapshot = snapshot(card = ""),
			liveSystemPrompt = "SYS-LIVE",
			liveName = "LiveChar",
			liveCard = """{"description":"LIVE-CARD"}""",
		)

		assertNull(persona.card)
		assertEquals("SYS-SNAP", persona.systemPrompt)
	}

	@Test
	fun `an unpinned scene uses the live assistant`() {
		val persona = RoleplayPersonaResolver.resolve(
			snapshot = null,
			liveSystemPrompt = "SYS-LIVE",
			liveName = "LiveChar",
			liveCard = """{"description":"LIVE-CARD"}""",
		)

		assertEquals("SYS-LIVE", persona.systemPrompt)
		assertEquals("LiveChar", persona.characterName)
		assertEquals("LIVE-CARD", persona.card?.description)
	}

	@Test
	fun `a pinned blank prompt stays blank instead of borrowing the live prompt`() {
		val persona = RoleplayPersonaResolver.resolve(
			snapshot = snapshot(prompt = "", card = ""),
			liveSystemPrompt = "SYS-LIVE",
			liveName = "LiveChar",
			liveCard = null,
		)

		assertEquals("", persona.systemPrompt)
		assertNull("no card in either row", persona.card)
	}
}
