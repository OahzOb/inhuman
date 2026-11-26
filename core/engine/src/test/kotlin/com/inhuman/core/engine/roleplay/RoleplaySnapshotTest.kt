package com.inhuman.core.engine.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The persona snapshot is the freeze that keeps an old scene's prompt stable
 * when its assistant is edited; the codec must round-trip it and treat junk as
 * "not pinned yet" rather than breaking the scene.
 */
class RoleplaySnapshotTest {

	@Test
	fun `a snapshot round-trips prompt, name and card`() {
		val snapshot = RoleplaySnapshot(
			systemPrompt = "You are a helpful assistant.",
			characterName = "Archivist",
			card = RoleplaySpec.exampleCard,
		)

		val decoded = RoleplayCodec.decodeSnapshot(RoleplayCodec.encodeSnapshot(snapshot))

		assertEquals(snapshot, decoded)
	}

	@Test
	fun `an empty or malformed snapshot reads as not pinned`() {
		assertNull(RoleplayCodec.decodeSnapshot(""))
		assertNull(RoleplayCodec.decodeSnapshot("   "))
		assertNull(RoleplayCodec.decodeSnapshot("{"))
		assertNull(RoleplayCodec.decodeSnapshot("[]"))
	}

	@Test
	fun `an unknown field survives rather than failing the snapshot`() {
		val raw = """{"systemPrompt":"p","characterName":"n","card":"{}","futureField":1}"""

		val decoded = RoleplayCodec.decodeSnapshot(raw)

		assertEquals("p", decoded?.systemPrompt)
		assertEquals("n", decoded?.characterName)
	}
}
