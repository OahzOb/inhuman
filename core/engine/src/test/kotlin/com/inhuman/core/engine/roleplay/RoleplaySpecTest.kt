package com.inhuman.core.engine.roleplay

import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The published format is generated from the codec, so this test pins the two
 * ways it could drift: a field added to a serializer but not to the description,
 * and a limit changed in one place but not the other.
 */
class RoleplaySpecTest {

	@Test
	fun `card fields are exactly the serialized card fields`() {
		val described = RoleplaySpec.cardFields.map { it.jsonName }
		val serialized = RoleplayCard.serializer().descriptor.elementNames.toList()
		assertEquals(serialized, described)
	}

	@Test
	fun `scene fields are exactly the serialized scene fields`() {
		val described = RoleplaySpec.sceneFields.map { it.jsonName }
		val serialized = RoleplayScene.serializer().descriptor.elementNames.toList()
		assertEquals(serialized, described)
	}

	@Test
	fun `text limits in the description are the enforced limits`() {
		val cardLimits = RoleplaySpec.cardFields.associate { it.jsonName to it.limit }
		assertEquals(RoleplayLimits.DESCRIPTION_MAX, cardLimits["description"])
		assertEquals(RoleplayLimits.PERSONALITY_MAX, cardLimits["personality"])
		assertEquals(RoleplayLimits.SCENARIO_MAX, cardLimits["scenario"])
		assertEquals(RoleplayLimits.GREETING_MAX, cardLimits["greeting"])
		assertEquals(RoleplayLimits.EXAMPLE_DIALOGUE_MAX, cardLimits["exampleDialogue"])
		assertEquals(RoleplayLimits.BOUNDARIES_MAX, cardLimits["boundaries"])
		assertEquals(RoleplayLimits.AUTHOR_NOTE_MAX, cardLimits["authorNote"])
		// Sampling fields describe a range, not a text limit.
		assertEquals(null, cardLimits["temperature"])
		assertEquals(null, cardLimits["topP"])
		assertEquals(null, cardLimits["maxTokens"])

		val sceneLimits = RoleplaySpec.sceneFields.associate { it.jsonName to it.limit }
		assertEquals(RoleplayLimits.WORLD_MAX, sceneLimits["world"])
		assertEquals(RoleplayLimits.PREMISE_MAX, sceneLimits["premise"])
		assertEquals(RoleplayLimits.USER_PERSONA_MAX, sceneLimits["userPersona"])
		assertEquals(RoleplayLimits.FORMAT_MAX, sceneLimits["format"])
		assertEquals(RoleplayLimits.NOTES_MAX, sceneLimits["notes"])
	}

	@Test
	fun `both examples are valid input for the codec`() {
		assertNotNull(RoleplayCodec.decodeCard(RoleplaySpec.exampleCard))
		assertNotNull(RoleplayCodec.decodeScene(RoleplaySpec.exampleScene))
	}

	@Test
	fun `the text names every field, limit and the format version`() {
		val text = RoleplaySpec.text
		(RoleplaySpec.cardFields + RoleplaySpec.sceneFields)
			.map { it.jsonName }
			.distinct()
			.forEach { name ->
				assertTrue("format text must name field $name", text.contains(name))
			}
		assertTrue(text.contains(RoleplaySpec.FORMAT))
		assertTrue(text.contains(RoleplayLimits.MAX_CARD_CHARS.toString()))
		assertTrue(text.contains(RoleplayLimits.MAX_SCENE_CHARS.toString()))
		assertTrue("unknown-field rule must be stated", text.contains("unknown fields"))
		assertFalse("text must not claim capabilities", text.contains("tool can be added"))
	}
}
