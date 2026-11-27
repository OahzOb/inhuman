package com.inhuman.ui.chat

import com.inhuman.core.ai.ToolResult
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.engine.roleplay.RoleplayCodec
import com.inhuman.core.engine.roleplay.RoleplayLimits
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card the fake assistants carry. */
private const val ARIA_CARD =
	"""{"name":"Aria","description":"A smuggler with a debt.","greeting":"You again?"}"""

/**
 * The proposal tool's argument handling and its two sources.
 *
 * The tool boundary is where a model's malformed call has to become text
 * rather than an exception, and where the user's assistant list must not leak:
 * these tests pin the one-of rule, the name resolution, the short persisted
 * summary, and the "never throws" promise for ordinary failures. Building the
 * proposal itself is [SceneProposalTest]'s job.
 */
class RoleplaySceneProposalToolTest {

	private fun aria(): AgentEntity = AgentEntity(
		id = "a1",
		name = "Aria",
		systemPrompt = "You are Aria.",
		rpJson = ARIA_CARD,
		createdAt = 0L,
		updatedAt = 0L,
	)

	private fun tool(vararg agents: AgentEntity): RoleplaySceneProposalTool =
		RoleplaySceneProposalTool { agents.toList() }

	private fun execute(tool: RoleplaySceneProposalTool, arguments: JsonObject): ToolResult =
		runBlocking { tool.execute(arguments) }

	/** The successful call's content, decoded the way the chat would decode it. */
	private fun decodeProposal(result: ToolResult): SceneProposal {
		assertFalse(result.content, result.isError)
		return SceneProposal.fromJson(result.content)
			?: error("tool content is not a proposal: ${result.content}")
	}

	// ------------------------------------------------------------ schema and flags

	@Test
	fun `the tool prepares a proposal and creates nothing`() {
		val tool = tool(aria())

		assertEquals("rp_create_scene", tool.name)
		assertEquals(RoleplaySceneProposalTool.NAME, tool.name)
		assertFalse(tool.mutates)
		assertFalse(tool.endsTurn)
		assertTrue(tool.reversible)
		// The model has to learn from the description that Create is the
		// user's gesture, not this call's effect.
		assertTrue(tool.description.contains("does not create"))
		assertTrue(tool.description.contains("Create"))
	}

	@Test
	fun `the schema offers one-of sources and no required fields`() {
		val parameters = tool(aria()).parameters
		@Suppress("UNCHECKED_CAST")
		val properties = parameters["properties"] as Map<String, Any?>

		assertEquals("object", parameters["type"])
		assertEquals(false, parameters["additionalProperties"])
		assertEquals(emptyList<String>(), parameters["required"])
		assertTrue(properties.containsKey("character"))
		assertTrue(properties.containsKey("card"))
		assertTrue(properties.containsKey("scene"))
		assertTrue(properties.containsKey("title"))
	}

	// -------------------------------------------------------------- inline cards

	@Test
	fun `an inline card produces the proposal JSON the app parses`() {
		val result = execute(
			tool(),
			buildJsonObject {
				put(
					"card",
					buildJsonObject {
						put("name", "The Archivist")
						put("description", "Archivist of a library that floods every spring.")
						put("greeting", "You are late.")
					},
				)
			},
		)

		val proposal = decodeProposal(result)
		assertEquals(SceneProposal.Source.Card, proposal.source)
		assertNull(proposal.agentId)
		assertEquals("", proposal.systemPrompt)
		assertEquals("The Archivist", proposal.characterName)
		assertEquals("The Archivist", proposal.title)
		assertEquals("{}", proposal.sceneJson)
	}

	@Test
	fun `a rejected card is an error, not a throw`() {
		val result = execute(
			tool(),
			buildJsonObject {
				put(
					"card",
					buildJsonObject {
						put("description", "d".repeat(RoleplayLimits.DESCRIPTION_MAX + 1))
					},
				)
			},
		)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("description"))
		assertNull(result.persistedContent)
	}

	// ------------------------------------------------------------------ assistants

	@Test
	fun `a character resolves by trimmed exact name`() {
		val result = execute(
			tool(aria().copy(name = "  Aria  ")),
			buildJsonObject { put("character", " Aria ") },
		)

		val proposal = decodeProposal(result)
		assertEquals(SceneProposal.Source.Character, proposal.source)
		assertEquals("a1", proposal.agentId)
		assertEquals("You are Aria.", proposal.systemPrompt)
		assertEquals(ARIA_CARD, proposal.cardJson)
		assertEquals("Aria", proposal.characterName)
	}

	@Test
	fun `an unknown character is an error that does not list the other names`() {
		val result = execute(tool(aria()), buildJsonObject { put("character", "Nobody") })

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("no assistant"))
		// The resolver must not become a directory of the user's assistants.
		assertFalse(result.content, result.content.contains("Aria"))
	}

	@Test
	fun `a duplicate character name asks for a unique name`() {
		val result = execute(
			tool(aria(), aria().copy(id = "a2")),
			buildJsonObject { put("character", "Aria") },
		)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("more than one"))
		assertNull(result.persistedContent)
	}

	@Test
	fun `a blank character name is an error`() {
		val result = execute(tool(aria()), buildJsonObject { put("character", "   ") })

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("character"))
	}

	@Test
	fun `an unreadable assistant list is an error, not a throw`() {
		val tool = RoleplaySceneProposalTool { throw IllegalStateException("db closed") }

		val result = execute(tool, buildJsonObject { put("character", "Aria") })

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("could not be read"))
	}

	// -------------------------------------------------------------- argument shape

	@Test
	fun `exactly one character source is required`() {
		val both = execute(
			tool(aria()),
			buildJsonObject {
				put("character", "Aria")
				put("card", buildJsonObject { put("name", "Aria") })
			},
		)
		val neither = execute(tool(aria()), buildJsonObject {})

		assertTrue(both.isError)
		assertTrue(both.content, both.content.contains("exactly one"))
		assertTrue(neither.isError)
		assertTrue(neither.content, neither.content.contains("character"))
		assertTrue(neither.content, neither.content.contains("card"))
	}

	@Test
	fun `malformed argument types are errors`() {
		val cardAsString = execute(tool(aria()), buildJsonObject { put("card", "not an object") })
		val characterAsObject = execute(
			tool(aria()),
			buildJsonObject { put("character", buildJsonObject {}) },
		)
		val sceneAsArray = execute(
			tool(aria()),
			buildJsonObject {
				put("card", buildJsonObject { put("name", "Aria") })
				put("scene", buildJsonArray {})
			},
		)
		val titleAsNumber = execute(
			tool(aria()),
			buildJsonObject {
				put("card", buildJsonObject { put("name", "Aria") })
				put("title", 7)
			},
		)

		assertTrue(cardAsString.isError)
		assertTrue(cardAsString.content, cardAsString.content.contains("card"))
		assertTrue(characterAsObject.isError)
		assertTrue(characterAsObject.content, characterAsObject.content.contains("character"))
		assertTrue(sceneAsArray.isError)
		assertTrue(sceneAsArray.content, sceneAsArray.content.contains("scene"))
		assertTrue(titleAsNumber.isError)
		assertTrue(titleAsNumber.content, titleAsNumber.content.contains("title"))
	}

	// ------------------------------------------------------------- scene and title

	@Test
	fun `scene and title reach the proposal`() {
		val result = execute(
			tool(),
			buildJsonObject {
				put(
					"card",
					buildJsonObject {
						put("name", "The Archivist")
						put("description", "Keeps a flooded library.")
					},
				)
				put(
					"scene",
					buildJsonObject {
						put("world", "A library under water.")
						put("userPersona", "A junior clerk.")
					},
				)
				put("title", "The Flood")
			},
		)

		val proposal = decodeProposal(result)
		assertEquals("The Flood", proposal.title)
		assertEquals("The Archivist", proposal.characterName)
		val scene = RoleplayCodec.decodeScene(proposal.sceneJson)
		assertEquals("A library under water.", scene?.world)
		assertEquals("A junior clerk.", scene?.userPersona)
	}

	// -------------------------------------------------------------- result content

	@Test
	fun `the persisted summary is one short line that says it is waiting`() {
		val result = execute(tool(aria()), buildJsonObject { put("character", "Aria") })

		val persisted = result.persistedContent ?: error("persistedContent is required")
		assertTrue(persisted, persisted.contains("Aria"))
		assertTrue(persisted, persisted.contains("waiting"))
		assertTrue(persisted, persisted.contains("do not say it was created"))
		assertEquals(1, persisted.lines().size)
		assertTrue("short enough to keep in every later request", persisted.length < 200)
		// The current turn gets the JSON; the transcript gets the reminder.
		assertTrue(result.content.startsWith("{"))
		assertFalse(result.content == persisted)
	}
}
