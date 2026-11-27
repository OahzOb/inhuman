package com.inhuman.ui.chat

import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.SavedPromptEntity
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.core.data.db.WorkspaceKinds
import com.inhuman.core.engine.roleplay.RoleplayCodec
import com.inhuman.core.engine.roleplay.RoleplayLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ARIA_CARD =
	"""{"name":"Aria","description":"A smuggler with a debt.","greeting":"You again?"}"""

private fun agent(
	id: String = "a1",
	name: String = "Aria",
	prompt: String = "You are Aria.",
	card: String = ARIA_CARD,
): AgentEntity = AgentEntity(
	id = id,
	name = name,
	systemPrompt = prompt,
	rpJson = card,
	createdAt = 0L,
	updatedAt = 0L,
)

private fun savedPrompt(title: String, text: String): SavedPromptEntity = SavedPromptEntity(
	id = "p-$title",
	title = title,
	text = text,
	createdAt = 0L,
	updatedAt = 0L,
)

private fun workspace(
	name: String,
	kind: String = WorkspaceKinds.NONE,
	permanent: Boolean = true,
): WorkspaceEntity = WorkspaceEntity(
	id = "w-$name",
	name = name,
	isPermanent = permanent,
	kind = kind,
	createdAt = 0L,
	updatedAt = 0L,
)

private fun cardOf(json: String): JsonObject =
	Json.parseToJsonElement(json) as JsonObject

private fun assistantOk(result: DraftResult<AssistantProposal>): AssistantProposal =
	when (result) {
		is DraftResult.Ok -> result.value
		is DraftResult.Rejected -> error("expected a draft, got: ${result.message}")
	}

private fun assistantError(result: DraftResult<AssistantProposal>): String =
	when (result) {
		is DraftResult.Ok -> error("expected a rejection, got: ${result.value}")
		is DraftResult.Rejected -> result.message
	}

private fun instructionOk(result: DraftResult<InstructionProposal>): InstructionProposal =
	when (result) {
		is DraftResult.Ok -> result.value
		is DraftResult.Rejected -> error("expected a draft, got: ${result.message}")
	}

private fun instructionError(result: DraftResult<InstructionProposal>): String =
	when (result) {
		is DraftResult.Ok -> error("expected a rejection, got: ${result.value}")
		is DraftResult.Rejected -> result.message
	}

/**
 * The configuration workshop's drafts on the JVM.
 *
 * The interesting rules are the ones the editor also holds: a draft must be
 * saveable (a name and some content), it must describe a real change, and it
 * must never carry a field the model has no business setting. The instruction
 * side has the reader's normalisation, so "already the same" is equality rather
 * than formatting, and the wire form is total because it is read back from a
 * transcript.
 */
class ConfigProposalsTest {

	// ------------------------------------------------------------ create

	@Test
	fun `a new assistant draft carries its name and prompt`() {
		val proposal = assistantOk(
			AssistantProposals.create(
				name = "  Helper ",
				prompt = " Be brief. ",
				card = null,
				agents = emptyList(),
			),
		)

		assertNull(proposal.agentId)
		assertEquals("Helper", proposal.name)
		assertTrue(proposal.changeName)
		assertEquals("Be brief.", proposal.prompt)
		assertTrue(proposal.changePrompt)
		assertTrue(proposal.changeCard)
		assertEquals("", proposal.cardJson)
		assertTrue(proposal.summary, proposal.summary.contains("Be brief."))
	}

	@Test
	fun `a card can be the only content of a new assistant`() {
		val proposal = assistantOk(
			AssistantProposals.create(
				name = "Aria",
				prompt = null,
				card = cardOf(ARIA_CARD),
				agents = emptyList(),
			),
		)

		assertFalse(proposal.changePrompt)
		assertNull(proposal.prompt)
		assertTrue(proposal.changeCard)
		// The stored card is the codec's canonical form, and it decodes back.
		assertEquals("Aria", RoleplayCodec.decodeCard(proposal.cardJson)?.name)
		assertTrue(proposal.summary, proposal.summary.contains("smuggler"))
	}

	@Test
	fun `a duplicate assistant name is rejected`() {
		val message = assistantError(
			AssistantProposals.create(
				name = " Aria ",
				prompt = "Another one.",
				card = null,
				agents = listOf(agent()),
			),
		)

		assertTrue(message, message.contains("already exists"))
	}

	@Test
	fun `a new assistant without a name or without content is rejected`() {
		val noName = assistantError(
			AssistantProposals.create(name = "  ", prompt = "Be brief.", card = null, agents = emptyList()),
		)
		val noContent = assistantError(
			AssistantProposals.create(name = "Helper", prompt = null, card = null, agents = emptyList()),
		)

		assertTrue(noName, noName.contains("name"))
		assertTrue(noContent, noContent.contains("prompt"))
		assertTrue(noContent, noContent.contains("card"))
	}

	@Test
	fun `an unusable card is rejected by the engine validator`() {
		val message = assistantError(
			AssistantProposals.create(
				name = "Aria",
				prompt = null,
				card = buildJsonObject {
					put("description", "d".repeat(RoleplayLimits.DESCRIPTION_MAX + 1))
				},
				agents = emptyList(),
			),
		)

		assertTrue(message, message.contains("description"))
		assertTrue(message, message.contains("nothing was prepared"))
	}

	@Test
	fun `an over-long prompt is rejected`() {
		val message = assistantError(
			AssistantProposals.create(
				name = "Helper",
				prompt = "p".repeat(MAX_PROPOSED_PROMPT_CHARS + 1),
				card = null,
				agents = emptyList(),
			),
		)

		assertTrue(message, message.contains("longer"))
	}

	// ------------------------------------------------------------ update

	@Test
	fun `renaming only leaves the prompt and card out of the draft`() {
		val proposal = assistantOk(
			AssistantProposals.update(agent(), name = "Ria", prompt = null, card = null),
		)

		assertEquals("a1", proposal.agentId)
		assertTrue(proposal.changeName)
		assertEquals("Ria", proposal.name)
		assertFalse(proposal.changePrompt)
		assertNull(proposal.prompt)
		assertFalse(proposal.changeCard)
		assertEquals("", proposal.cardJson)
	}

	@Test
	fun `a prompt rewrite is carried in full and is the only change`() {
		val proposal = assistantOk(
			AssistantProposals.update(agent(), name = null, prompt = " New instructions. ", card = null),
		)

		assertFalse(proposal.changeName)
		assertTrue(proposal.changePrompt)
		assertEquals("New instructions.", proposal.prompt)
		assertFalse(proposal.changeCard)
	}

	@Test
	fun `an omitted card keeps the stored one, and an empty object clears it`() {
		val kept = assistantOk(
			AssistantProposals.update(agent(), name = null, prompt = "New.", card = null),
		)
		assertFalse(kept.changeCard)

		val changed = assistantOk(
			AssistantProposals.update(
				agent(card = ""),
				name = null,
				prompt = "New.",
				card = cardOf(ARIA_CARD),
			),
		)
		assertTrue(changed.changeCard)
		assertEquals("Aria", RoleplayCodec.decodeCard(changed.cardJson)?.name)

		val cleared = assistantOk(
			AssistantProposals.update(agent(), name = null, prompt = "New.", card = buildJsonObject {}),
		)
		assertTrue(cleared.changeCard)
		assertEquals("", cleared.cardJson)
	}

	@Test
	fun `an update that changes nothing is rejected`() {
		val message = assistantError(
			AssistantProposals.update(agent(), name = null, prompt = "You are Aria.", card = null),
		)

		assertTrue(message, message.contains("would not change anything"))
	}

	@Test
	fun `a prompt may be cleared while a card keeps the assistant usable`() {
		val proposal = assistantOk(
			AssistantProposals.update(agent(), name = null, prompt = "", card = null),
		)

		assertTrue(proposal.changePrompt)
		assertEquals("", proposal.prompt)
		assertFalse(proposal.changeCard)
	}

	@Test
	fun `an update may not leave the assistant with neither prompt nor card`() {
		val message = assistantError(
			AssistantProposals.update(agent(), name = null, prompt = "", card = buildJsonObject {}),
		)

		assertTrue(message, message.contains("neither"))
	}

	@Test
	fun `a blank rename is rejected`() {
		val message = assistantError(
			AssistantProposals.update(agent(), name = "   ", prompt = "New.", card = null),
		)

		assertTrue(message, message.contains("name"))
	}

	// ------------------------------------------------------- proposal wire form

	@Test
	fun `assistant proposals round-trip through their JSON`() {
		val create = assistantOk(
			AssistantProposals.create("Helper", "Be brief.", cardOf(ARIA_CARD), emptyList()),
		)
		assertEquals(create, AssistantProposal.fromJson(create.toJson()))

		val update = assistantOk(
			AssistantProposals.update(agent(), name = "Ria", prompt = "New.", card = null),
		)
		assertEquals(update, AssistantProposal.fromJson(update.toJson()))
	}

	@Test
	fun `reading an assistant proposal is total`() {
		assertNull(AssistantProposal.fromJson("not json"))
		assertNull(AssistantProposal.fromJson("{}"))
		assertNull(
			AssistantProposal.fromJson(
				"""{"kind":"instruction","agentId":null,"name":"A","changeName":true,""""
					+ """"prompt":null,"changePrompt":false,"cardJson":"","changeCard":true,""""
					+ """"summary":""}""",
			),
		)
		// changePrompt true without a prompt has no editor state to open.
		assertNull(
			AssistantProposal.fromJson(
				"""{"kind":"assistant","agentId":null,"name":"A","changeName":true,""""
					+ """"prompt":null,"changePrompt":true,"cardJson":"","changeCard":true,""""
					+ """"summary":""}""",
			),
		)
		// A prompt over the proposal cap cannot come from this release's tool.
		assertNull(
			AssistantProposal.fromJson(
				"""{"kind":"assistant","agentId":null,"name":"A","changeName":true,""""
					+ """"prompt":"${"p".repeat(MAX_PROPOSED_PROMPT_CHARS + 1)}","""
					+ """"changePrompt":true,"cardJson":"","changeCard":false,"summary":""}""",
			),
		)
		// An update with nothing to change cannot open an editor.
		assertNull(
			AssistantProposal.fromJson(
				"""{"kind":"assistant","agentId":"a1","name":"A","changeName":false,""""
					+ """"prompt":null,"changePrompt":false,"cardJson":"","changeCard":false,""""
					+ """"summary":""}""",
			),
		)
	}

	// ------------------------------------------------------------ instructions

	@Test
	fun `instruction preparation uses the reader's normalisation`() {
		val proposal = instructionOk(
			InstructionProposals.prepare(
				workspace("Notes"),
				text = "\uFEFF# Hi\r\n\r\nBody  \r\n",
				current = null,
			),
		)

		assertEquals("Notes", proposal.workspaceName)
		assertEquals("# Hi\n\nBody", proposal.text)
		assertNull(proposal.existingBytes)
		assertTrue(proposal.summary, proposal.summary.contains("# Hi"))
	}

	@Test
	fun `an instruction file that is already identical is rejected`() {
		val current = WorkspaceInstructions(body = "# Hi", truncated = false, bytes = 4)
		val message = instructionError(
			InstructionProposals.prepare(workspace("Notes"), "# Hi\r\n", current),
		)

		assertTrue(message, message.contains("already has exactly this text"))
	}

	@Test
	fun `clearing an instruction file is a draft with no text`() {
		val current = WorkspaceInstructions(body = "# Hi", truncated = false, bytes = 4)
		val proposal = instructionOk(
			InstructionProposals.prepare(workspace("Notes"), "", current),
		)

		assertEquals("", proposal.text)
		assertEquals(4, proposal.existingBytes)
		assertEquals("", proposal.summary)
	}

	@Test
	fun `instruction text is refused for NUL, the size cap and a temporary workspace`() {
		val nul = instructionError(
			InstructionProposals.prepare(workspace("Notes"), "a\u0000b", null),
		)
		val huge = instructionError(
			InstructionProposals.prepare(
				workspace("Notes"),
				"x".repeat(MAX_WORKSPACE_INSTRUCTIONS_BYTES + 1),
				null,
			),
		)
		val temporary = instructionError(
			InstructionProposals.prepare(workspace("Notes", permanent = false), "# Hi", null),
		)

		assertTrue(nul, nul.contains("NUL"))
		assertTrue(huge, huge.contains("KiB"))
		assertTrue(temporary, temporary.contains("permanent"))
	}

	@Test
	fun `instruction proposals round-trip and are read totally`() {
		val proposal = instructionOk(
			InstructionProposals.prepare(workspace("Notes"), "# Hi\n", null),
		)
		assertEquals(proposal, InstructionProposal.fromJson(proposal.toJson()))

		assertNull(InstructionProposal.fromJson("not json"))
		assertNull(InstructionProposal.fromJson("""{"kind":"assistant"}"""))
		assertNull(
			InstructionProposal.fromJson(
				"""{"kind":"instruction","workspaceName":"","text":"","existingBytes":null,"summary":""}""",
			),
		)
	}

	// ------------------------------------------------------------ resolution

	@Test
	fun `a workspace resolves by trimmed display name, permanent only`() {
		val notes = workspace("Notes")
		val temporary = workspace("Notes", permanent = false).copy(id = "w-temp")

		val ok = resolveInstructionWorkspace(listOf(notes, temporary), " Notes ")
		assertTrue(ok is WorkspaceLookup.Ok)
		assertEquals(notes.id, (ok as WorkspaceLookup.Ok).workspace.id)

		assertTrue(resolveInstructionWorkspace(listOf(notes), "Missing") is WorkspaceLookup.Missing)
		// A temporary workspace cannot own instructions, so its name does not
		// resolve even though a row with that name exists.
		assertTrue(
			resolveInstructionWorkspace(listOf(temporary), "Notes") is WorkspaceLookup.Missing,
		)
	}

	@Test
	fun `a duplicate workspace name is ambiguous rather than a guess`() {
		val one = workspace("Notes")
		val two = workspace("Notes").copy(id = "w-other")

		val lookup = resolveInstructionWorkspace(listOf(one, two), "Notes")

		assertTrue(lookup is WorkspaceLookup.Ambiguous)
	}

	// ------------------------------------------------------ prompt library

	@Test
	fun `a new saved prompt is named and previewed`() {
		val proposal = promptOk(
			PromptProposals.prepare("Terse", "Be brief.\nAsk one question.", emptyList()),
		)

		assertEquals("Terse", proposal.title)
		assertEquals("Be brief.\nAsk one question.", proposal.text)
		assertNull(proposal.existingChars)
		assertTrue(proposal.summary, proposal.summary.contains("Be brief"))
	}

	@Test
	fun `a saved prompt with an existing title says it replaces that entry`() {
		val proposal = promptOk(
			PromptProposals.prepare(
				"Terse",
				"Be even briefer.",
				listOf(savedPrompt("Terse", "Be brief.")),
			),
		)

		assertEquals("Be brief.".length, proposal.existingChars)
	}

	@Test
	fun `a no-op saved prompt is rejected`() {
		val result = PromptProposals.prepare(
			"Terse",
			"Be brief.",
			listOf(savedPrompt("Terse", "Be brief.")),
		)

		assertTrue(result is DraftResult.Rejected)
		assertTrue((result as DraftResult.Rejected).message.contains("exact text"))
	}

	@Test
	fun `a saved prompt needs a title and non-blank text`() {
		assertTrue(PromptProposals.prepare(" ", "text", emptyList()) is DraftResult.Rejected)
		assertTrue(PromptProposals.prepare("T", "  ", emptyList()) is DraftResult.Rejected)
		assertTrue(
			PromptProposals.prepare("T", "x".repeat(MAX_PROPOSED_PROMPT_CHARS + 1), emptyList())
				is DraftResult.Rejected,
		)
	}

	@Test
	fun `a prompt proposal survives JSON and rejects a malformed one`() {
		val proposal = PromptProposal("Terse", "Be brief.", 4, "Be brief.")
		assertEquals(proposal, PromptProposal.fromJson(proposal.toJson()))

		assertNull(PromptProposal.fromJson("not json"))
		assertNull(PromptProposal.fromJson("""{"kind":"assistant"}"""))
		assertNull(
			PromptProposal.fromJson(
				"""{"kind":"prompt","title":"","text":"x","existingChars":null,"summary":""}""",
			),
		)
		assertNull(
			PromptProposal.fromJson(
				"""{"kind":"prompt","title":"T","text":" ","existingChars":null,"summary":""}""",
			),
		)
	}

	private fun promptOk(result: DraftResult<PromptProposal>): PromptProposal = when (result) {
		is DraftResult.Ok -> result.value
		is DraftResult.Rejected -> error("unexpected rejection: ${result.message}")
	}
}
