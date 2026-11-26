package com.inhuman.core.engine.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validator is the contract the authoring model relies on: field-level,
 * structural, and free of the file's prose. These tests pin all three.
 */
class RoleplayValidatorTest {

	@Test
	fun `the published example validates as a card`() {
		val result = RoleplayValidator.validateCard(RoleplaySpec.exampleCard)

		assertTrue(result.ok)
		assertTrue(result.summary.contains("roleplay card"))
		assertTrue(result.summary.contains("greeting: yes"))
	}

	@Test
	fun `a blank card is a blocking problem`() {
		val result = RoleplayValidator.validateCard("{}")

		assertFalse(result.ok)
		assertTrue(result.issues.any { it.message.contains("no field is set") })
	}

	@Test
	fun `malformed JSON is reported without echoing the input`() {
		val result = RoleplayValidator.validateCard("""{"greeting": ["TOP-SECRET"]}""")

		assertFalse(result.ok)
		val rendered = result.issues.joinToString { it.render() }
		assertTrue(rendered.contains("error"))
		assertFalse("the validator must not quote file values", rendered.contains("TOP-SECRET"))
	}

	@Test
	fun `an over-limit field names the field and both lengths`() {
		val card = """{"description": "${"x".repeat(RoleplayLimits.DESCRIPTION_MAX + 1)}"}"""
		val result = RoleplayValidator.validateCard(card)

		assertFalse(result.ok)
		val issue = result.issues.first { it.field == "description" }
		assertTrue(issue.message.contains("${RoleplayLimits.DESCRIPTION_MAX + 1}"))
		assertTrue(issue.message.contains("${RoleplayLimits.DESCRIPTION_MAX}"))
	}

	@Test
	fun `an unknown field is a warning, not a failure`() {
		val result = RoleplayValidator.validateCard(
			"""{"greeting": "hello", "speed": 3}""",
		)

		assertTrue(result.ok)
		assertTrue(result.issues.single().warning)
		assertTrue(result.issues.single().field == "speed")
	}

	@Test
	fun `sampling values outside the offered range are errors`() {
		val result = RoleplayValidator.validateCard(
			"""{"greeting": "hi", "temperature": 5.0, "maxTokens": 999999}""",
		)

		assertFalse(result.ok)
		assertEquals(2, result.issues.count { !it.warning })
	}

	@Test
	fun `a blank scene is only a warning, because no scene is legal`() {
		val result = RoleplayValidator.validateScene("{}")

		assertTrue(result.ok)
		assertTrue(result.issues.single().warning)
	}

	@Test
	fun `scene limits point at the scene field`() {
		val scene = """{"world": "${"y".repeat(RoleplayLimits.WORLD_MAX + 1)}"}"""
		val result = RoleplayValidator.validateScene(scene)

		assertFalse(result.ok)
		assertEquals("world", result.issues.single { !it.warning }.field)
	}
}
