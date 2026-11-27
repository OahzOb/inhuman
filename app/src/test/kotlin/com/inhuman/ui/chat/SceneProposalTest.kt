package com.inhuman.ui.chat

import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.engine.roleplay.RoleplayCodec
import com.inhuman.core.engine.roleplay.RoleplayLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The assistant card the "existing assistant" tests start from. */
private const val ASSISTANT_CARD =
	"""{"name":"Aria","description":"A smuggler with a debt.","greeting":"You again?"}"""

/**
 * The proposal document and the two ways to build one.
 *
 * What matters here is the contract the tool and the UI share: a valid card
 * becomes a proposal with a frozen persona, an invalid one is refused with the
 * field named rather than a value quoted, and a stored proposal always parses
 * back into the same value. The definition of "valid" is deliberately not
 * restated -- the tests go through the same `RoleplayCodec`/`RoleplayValidator`
 * the app uses, so a change to the engine's rules shows up here.
 */
class SceneProposalTest {

	private fun agent(
		name: String = "Aria",
		systemPrompt: String = "You are Aria.",
		rpJson: String = ASSISTANT_CARD,
	): AgentEntity = AgentEntity(
		id = "a1",
		name = name,
		systemPrompt = systemPrompt,
		rpJson = rpJson,
		createdAt = 0L,
		updatedAt = 0L,
	)

	private fun ok(result: ProposalResult): SceneProposal {
		assertTrue("expected Ok, got $result", result is ProposalResult.Ok)
		return (result as ProposalResult.Ok).proposal
	}

	private fun rejected(result: ProposalResult): String {
		assertTrue("expected Rejected, got $result", result is ProposalResult.Rejected)
		return (result as ProposalResult.Rejected).message
	}

	private fun cardOf(vararg fields: Pair<String, String>): JsonObject = buildJsonObject {
		fields.forEach { (key, value) -> put(key, value) }
	}

	// ---------------------------------------------------------------- JSON form

	@Test
	fun `a proposal round-trips through its JSON`() {
		val inline = ok(SceneProposals.fromInlineCard(cardOf("name" to "Aria"), null, null))
		val fromAssistant = ok(SceneProposals.fromAssistant(agent(), null, null))

		assertEquals(inline, SceneProposal.fromJson(inline.toJson()))
		assertEquals(fromAssistant, SceneProposal.fromJson(fromAssistant.toJson()))
	}

	@Test
	fun `fromJson returns null for anything that is not a proposal`() {
		assertNull(SceneProposal.fromJson(""))
		assertNull(SceneProposal.fromJson("not json"))
		assertNull(SceneProposal.fromJson("[]"))
		assertNull(SceneProposal.fromJson("""{"source":"card"}"""))
		assertNull(SceneProposal.fromJson("""{"source":"assistant"}"""))

		// Every key is required on read: a truncated row must not decode into a
		// proposal with an invented empty field.
		val root = Json.parseToJsonElement(
			ok(SceneProposals.fromInlineCard(cardOf("name" to "Aria"), null, null)).toJson(),
		) as JsonObject
		assertNull(SceneProposal.fromJson(JsonObject(root - "title").toString()))
		assertNull(SceneProposal.fromJson(JsonObject(root - "sceneJson").toString()))

		// The source and the agent id have to agree, because scene creation
		// reads the assistant prompt from a Character proposal and does not for
		// an inline card.
		assertNull(SceneProposal.fromJson(root.toString().replace("\"card\"", "\"nope\"")))
		assertNull(
			SceneProposal.fromJson(
				JsonObject(root + ("agentId" to JsonPrimitive("a1"))).toString(),
			),
		)
		val character = Json.parseToJsonElement(
			ok(SceneProposals.fromAssistant(agent(), null, null)).toJson(),
		) as JsonObject
		assertNull(SceneProposal.fromJson(JsonObject(character - "agentId").toString()))

		// Wrong types are malformed, not coerced.
		assertNull(
			SceneProposal.fromJson(JsonObject(root + ("title" to JsonPrimitive(7))).toString()),
		)
	}

	@Test
	fun `unknown keys do not disturb the reader`() {
		val proposal = ok(SceneProposals.fromInlineCard(cardOf("name" to "Aria"), null, null))
		val root = Json.parseToJsonElement(proposal.toJson()) as JsonObject
		val withExtra = JsonObject(root + ("writtenBy" to JsonPrimitive("a newer release")))

		assertEquals(proposal, SceneProposal.fromJson(withExtra.toString()))
	}

	// ------------------------------------------------------------- inline cards

	@Test
	fun `an inline card becomes a proposal`() {
		val proposal = ok(
			SceneProposals.fromInlineCard(
				buildJsonObject {
					put("name", "The Archivist")
					put("description", "Archivist of a library\nthat floods every spring.")
					put("greeting", "You are late.")
				},
				buildJsonObject { put("world", "A library that floods.") },
				null,
			),
		)

		assertEquals(SceneProposal.Source.Card, proposal.source)
		assertNull(proposal.agentId)
		assertEquals("", proposal.systemPrompt)
		assertEquals("The Archivist", proposal.characterName)
		assertEquals("The Archivist", proposal.title)
		assertEquals("Archivist of a library that floods every spring.", proposal.description)
		assertEquals("A library that floods.", RoleplayCodec.decodeScene(proposal.sceneJson)?.world)
		// The stored card is the codec's canonical form, not the model's
		// whitespace: scene creation decodes exactly this text.
		assertEquals("The Archivist", RoleplayCodec.decodeCard(proposal.cardJson)?.name)
		assertFalse(proposal.cardJson.contains("\n"))
	}

	@Test
	fun `an absent or blank scene is the empty object`() {
		val absent = ok(SceneProposals.fromInlineCard(cardOf("name" to "Aria"), null, null))
		val blank = ok(
			SceneProposals.fromInlineCard(cardOf("name" to "Aria"), buildJsonObject {}, null),
		)

		assertEquals("{}", absent.sceneJson)
		assertEquals("{}", blank.sceneJson)
	}

	@Test
	fun `a nameless card uses the title`() {
		val proposal = ok(
			SceneProposals.fromInlineCard(cardOf("description" to "A smuggler."), null, "Rain"),
		)

		assertEquals("Rain", proposal.characterName)
		assertEquals("Rain", proposal.title)
	}

	@Test
	fun `a card without a name or a title is rejected`() {
		val message = rejected(
			SceneProposals.fromInlineCard(cardOf("description" to "A smuggler."), null, null),
		)

		assertTrue(message.contains("name"))
		assertTrue(message.contains("title"))
	}

	@Test
	fun `an over-limit field is rejected with the field named`() {
		val message = rejected(
			SceneProposals.fromInlineCard(
				cardOf(
					"name" to "Aria",
					"description" to "d".repeat(RoleplayLimits.DESCRIPTION_MAX + 1),
				),
				null,
				null,
			),
		)

		assertTrue(message, message.contains("description"))
		assertTrue(message, message.contains("${RoleplayLimits.DESCRIPTION_MAX + 1}"))
	}

	@Test
	fun `an over-limit total is rejected even when every field fits`() {
		// The per-field maxima sum to more than the card total, so this card
		// passes every field check and only the total can refuse it.
		val atEveryFieldMaximum = buildJsonObject {
			put("name", "n".repeat(RoleplayLimits.NAME_MAX))
			put("description", "d".repeat(RoleplayLimits.DESCRIPTION_MAX))
			put("personality", "p".repeat(RoleplayLimits.PERSONALITY_MAX))
			put("scenario", "s".repeat(RoleplayLimits.SCENARIO_MAX))
			put("greeting", "g".repeat(RoleplayLimits.GREETING_MAX))
			put("exampleDialogue", "e".repeat(RoleplayLimits.EXAMPLE_DIALOGUE_MAX))
			put("boundaries", "b".repeat(RoleplayLimits.BOUNDARIES_MAX))
			put("authorNote", "a".repeat(RoleplayLimits.AUTHOR_NOTE_MAX))
		}

		val message = rejected(
			SceneProposals.fromInlineCard(atEveryFieldMaximum, null, null),
		)

		assertTrue(message, message.contains("total card text"))
	}

	@Test
	fun `an invalid scene is rejected with the field named`() {
		val message = rejected(
			SceneProposals.fromInlineCard(
				cardOf("name" to "Aria"),
				buildJsonObject {
					put("world", "w".repeat(RoleplayLimits.WORLD_MAX + 1))
				},
				null,
			),
		)

		assertTrue(message, message.contains("world"))
	}

	// --------------------------------------------------------------- assistants

	@Test
	fun `an assistant keeps its card JSON and prompt verbatim`() {
		// Odd spacing and an unknown field are deliberate: the snapshot copy
		// must be the stored text, not a re-encode of it.
		val rpJson =
			"""{ "name": "Aria", "description": "A smuggler with a debt.", "customField": "kept" }"""

		val proposal = ok(SceneProposals.fromAssistant(agent(rpJson = rpJson), null, "Rain"))

		assertEquals(SceneProposal.Source.Character, proposal.source)
		assertEquals("a1", proposal.agentId)
		assertEquals(rpJson, proposal.cardJson)
		assertEquals("You are Aria.", proposal.systemPrompt)
		assertEquals("Aria", proposal.characterName)
		assertEquals("Rain", proposal.title)
		assertEquals("A smuggler with a debt.", proposal.description)
		assertEquals("{}", proposal.sceneJson)
	}

	@Test
	fun `an assistant card without a name falls back to the assistant name`() {
		val proposal = ok(
			SceneProposals.fromAssistant(
				agent(rpJson = """{"description":"A smuggler with a debt."}"""),
				null,
				null,
			),
		)

		assertEquals("Aria", proposal.characterName)
		assertEquals("Aria", proposal.title)
	}

	@Test
	fun `an assistant with a nameless card and no name is rejected`() {
		val message = rejected(
			SceneProposals.fromAssistant(
				agent(name = " ", rpJson = """{"description":"A smuggler."}"""),
				null,
				"Rain",
			),
		)

		assertTrue(message, message.contains("name"))
	}

	@Test
	fun `an assistant without a usable card is rejected`() {
		val missing = rejected(SceneProposals.fromAssistant(agent(rpJson = ""), null, null))
		val malformed = rejected(
			SceneProposals.fromAssistant(agent(rpJson = """{"name": 5}"""), null, null),
		)

		assertTrue(missing, missing.contains("no usable character card"))
		assertTrue(malformed, malformed.contains("no usable character card"))
	}

	@Test
	fun `a stored assistant card over a limit is rejected`() {
		val tooLong = "d".repeat(RoleplayLimits.DESCRIPTION_MAX + 1)
		val message = rejected(
			SceneProposals.fromAssistant(
				agent(rpJson = """{"name":"Aria","description":"$tooLong"}"""),
				null,
				null,
			),
		)

		assertTrue(message, message.contains("description"))
	}
}
