package com.inhuman.core.engine.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card/scene codec is the only thing standing between a user's text and the
 * database, so its failure direction is the contract: an unreadable value turns
 * the feature off instead of breaking the assistant, and a blank card is not
 * stored at all.
 */
class RoleplayCodecTest {

	@Test
	fun `a full card round-trips`() {
		val card = RoleplayCard(
			description = "A lighthouse keeper.",
			personality = "Dry, patient.",
			scenario = "The lamp has gone out.",
			greeting = "You are late.",
			exampleDialogue = "{{user}}: Hello.\n{{char}}: You are late.",
			boundaries = "Never harm the user's character.",
			authorNote = "Keep replies short.",
			temperature = 0.9,
			topP = 0.95,
			maxTokens = 4_096,
		)

		val decoded = RoleplayCodec.decodeCard(RoleplayCodec.encodeCard(card))

		assertEquals(card, decoded)
	}

	@Test
	fun `blank values are absent rather than stored`() {
		assertNull(RoleplayCodec.decodeCard(null))
		assertNull(RoleplayCodec.decodeCard(""))
		assertNull(RoleplayCodec.decodeCard("   "))
		// Whitespace is not content: a card with no text is the same state as no
		// card, so the assistant stays a plain assistant.
		assertNull(RoleplayCodec.decodeCard("""{"description":"   "}"""))
		assertEquals("", RoleplayCodec.encodeCard(RoleplayCard()))
	}

	@Test
	fun `a card may carry only sampling settings`() {
		// The character can be defined by the assistant's own prompt while the
		// card only tunes pronunciation of the reply; that is still a stored
		// card, but it injects no card block.
		val decoded = RoleplayCodec.decodeCard("""{"temperature":0.8}""")

		assertEquals(0.8, decoded?.temperature ?: 0.0, 0.0)
		assertNull(RoleplayPrompt.renderCard(decoded!!, "Aster"))
	}

	@Test
	fun `a malformed value disables the feature instead of failing the assistant`() {
		assertNull(RoleplayCodec.decodeCard("{not json"))
		assertNull(RoleplayCodec.decodeCard("""{"description":42}"""))
		assertNull(RoleplayCodec.decodeScene("[[["))
	}

	@Test
	fun `unknown keys are ignored so a newer card still opens`() {
		val decoded = RoleplayCodec.decodeCard(
			"""{"description":"Keeper","newFieldFromTheFuture":{"a":1},"tags":["x"]}""",
		)

		assertEquals("Keeper", decoded?.description)
	}

	@Test
	fun `a scene round-trips and a blank scene is absent`() {
		val scene = RoleplayScene(
			world = "A drowned coast.",
			premise = "The tide does not go out.",
			userPersona = "A surveyor.",
			format = "Second person, three short paragraphs.",
			notes = "No time skips.",
		)

		assertEquals(scene, RoleplayCodec.decodeScene(RoleplayCodec.encodeScene(scene)))
		assertNull(RoleplayCodec.decodeScene(""))
		assertNull(RoleplayCodec.decodeScene("""{"world":" "}"""))
		assertEquals("", RoleplayCodec.encodeScene(RoleplayScene()))
	}

	@Test
	fun `the greeting substitutes the character name and keeps the user placeholder`() {
		val card = RoleplayCard(greeting = "{{Char}} looks up. \"{{user}}, you are late.\"")

		assertEquals(
			"Aster looks up. \"{{user}}, you are late.\"",
			RoleplayPrompt.greeting(card, "Aster"),
		)
	}

	@Test
	fun `limits are reported per field and for the total`() {
		val card = RoleplayCard(
			description = "d".repeat(RoleplayLimits.DESCRIPTION_MAX + 1),
			greeting = "g".repeat(RoleplayLimits.GREETING_MAX),
		)
		val cardErrors = RoleplayLimits.cardErrors(card)
		assertEquals(1, cardErrors.size)
		assertEquals(RoleplayField.Description, cardErrors.first().field)
		assertEquals(RoleplayLimits.DESCRIPTION_MAX + 1, cardErrors.first().actual)

		// A scene whose fields are each legal but whose total is over the
		// scene ceiling is still refused: the total is the real budget.
		val scene = RoleplayScene(
			world = "w".repeat(RoleplayLimits.WORLD_MAX),
			premise = "p".repeat(RoleplayLimits.PREMISE_MAX),
			userPersona = "u".repeat(RoleplayLimits.USER_PERSONA_MAX),
			format = "f".repeat(RoleplayLimits.FORMAT_MAX),
			notes = "n".repeat(RoleplayLimits.NOTES_MAX),
		)
		assertTrue(RoleplayLimits.sceneChars(scene) > RoleplayLimits.MAX_SCENE_CHARS)
		val sceneErrors = RoleplayLimits.sceneErrors(scene)
		assertEquals(listOf(RoleplayField.SceneTotal), sceneErrors.map { it.field })
	}

	@Test
	fun `limits accept a value at the boundary`() {
		val card = RoleplayCard(greeting = "g".repeat(RoleplayLimits.GREETING_MAX))
		assertTrue(RoleplayLimits.cardErrors(card).isEmpty())
	}
}
