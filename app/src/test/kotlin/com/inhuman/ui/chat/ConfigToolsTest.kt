package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.SavedPromptEntity
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.core.data.db.WorkspaceKinds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOOL_ARIA_CARD =
	"""{"name":"Aria","description":"A smuggler with a debt."}"""

private fun toolAgent(
	id: String = "a1",
	name: String = "Aria",
	prompt: String = "You are Aria.",
	card: String = TOOL_ARIA_CARD,
	model: String? = "deepseek-flash",
): AgentEntity = AgentEntity(
	id = id,
	name = name,
	systemPrompt = prompt,
	rpJson = card,
	model = model,
	createdAt = 0L,
	updatedAt = 0L,
)

private fun toolWorkspace(
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

private fun toolPrompt(title: String, text: String): SavedPromptEntity = SavedPromptEntity(
	id = "p-$title",
	title = title,
	text = text,
	createdAt = 0L,
	updatedAt = 0L,
)

/**
 * The configuration workshop's helpers at their tool boundary.
 *
 * What matters here is not the draft model ([ConfigProposalsTest]'s job) but the
 * promises the request depends on: reads return content and never capability
 * configuration, a workspace is named rather than identified, host failures are
 * text instead of a failed turn, and a proposal says explicitly that nothing has
 * been written.
 */
class ConfigToolsTest {

	private val notes = toolWorkspace("Notes")
	private val workshop = toolWorkspace("Workshop", WorkspaceKinds.WORKSHOP)
	private val temporary = toolWorkspace("Scratch", permanent = false)

	private fun execute(tool: Tool, arguments: JsonObject): ToolResult =
		runBlocking { tool.execute(arguments) }

	private fun array(result: ToolResult): JsonArray {
		assertFalse(result.content, result.isError)
		return Json.parseToJsonElement(result.content).jsonArray
	}

	private fun decodeAssistantProposal(result: ToolResult): AssistantProposal {
		assertFalse(result.content, result.isError)
		return AssistantProposal.fromJson(result.content)
			?: error("tool content is not an assistant proposal: ${result.content}")
	}

	private fun decodeInstructionProposal(result: ToolResult): InstructionProposal {
		assertFalse(result.content, result.isError)
		return InstructionProposal.fromJson(result.content)
			?: error("tool content is not an instruction proposal: ${result.content}")
	}

	// --------------------------------------------------------------- assistants

	@Test
	fun `every helper is a non-mutating host helper`() {
		val tools = listOf(
			ConfigListAssistantsTool { emptyList() },
			ConfigReadAssistantTool { emptyList() },
			ConfigProposeAssistantTool { emptyList() },
			ConfigListInstructionsTool({ emptyList() }, { _: WorkspaceEntity -> null }),
			ConfigReadInstructionTool({ emptyList() }, { _: WorkspaceEntity -> null }),
			ConfigProposeInstructionTool({ emptyList() }, { null }, { _: WorkspaceEntity -> null }),
		)

		assertEquals(
			listOf(
				ConfigTools.LIST_ASSISTANTS,
				ConfigTools.READ_ASSISTANT,
				ConfigTools.PROPOSE_ASSISTANT,
				ConfigTools.LIST_INSTRUCTIONS,
				ConfigTools.READ_INSTRUCTION,
				ConfigTools.PROPOSE_INSTRUCTION,
			),
			tools.map { it.name },
		)
		assertTrue(tools.none { it.mutates })
		// A proposal is not a capability: nothing here is irreversible or ends
		// the turn early.
		assertTrue(tools.all { it.reversible })
		assertTrue(tools.none { it.endsTurn })
	}

	@Test
	fun `the assistant list is content, not a capability report`() {
		val result = execute(
			ConfigListAssistantsTool {
				listOf(
					toolAgent(),
					toolAgent(id = "a2", name = "Plain", prompt = "", card = ""),
				)
			},
			buildJsonObject {},
		)

		val rows = array(result)
		assertEquals(2, rows.size)
		val aria = rows[0].jsonObject
		assertEquals("Aria", aria["name"]?.jsonPrimitive?.content)
		assertEquals(true, aria["hasCard"]?.jsonPrimitive?.content?.toBoolean())
		assertEquals("Aria", aria["cardName"]?.jsonPrimitive?.content)
		assertEquals("You are Aria.".length, aria["promptChars"]?.jsonPrimitive?.content?.toInt())
		assertEquals("You are Aria.", aria["promptPreview"]?.jsonPrimitive?.content)
		assertEquals("deepseek-flash", aria["model"]?.jsonPrimitive?.content)
		// The row must not become a directory of what the assistant may do.
		assertFalse(result.content, result.content.contains("tools"))
		assertFalse(result.content, result.content.contains("approval"))
		assertFalse(result.content, result.content.contains("provider"))
	}

	@Test
	fun `a list read failure is text, not a throw`() {
		val result = execute(
			ConfigListAssistantsTool { throw IllegalStateException("db closed") },
			buildJsonObject {},
		)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("could not be read"))
	}

	@Test
	fun `reading an assistant returns its prompt and card`() {
		val result = execute(
			ConfigReadAssistantTool { listOf(toolAgent()) },
			buildJsonObject { put("assistant", " Aria ") },
		)

		val row = Json.parseToJsonElement(result.content).jsonObject
		assertEquals("You are Aria.", row["systemPrompt"]?.jsonPrimitive?.content)
		assertEquals(false, row["promptTruncated"]?.jsonPrimitive?.content?.toBoolean())
		assertNotNull(row["cardJson"])
		assertTrue(result.persistedContent.orEmpty().contains("Aria"))
	}

	@Test
	fun `an unknown or duplicate assistant name is an error`() {
		val unknown = execute(
			ConfigReadAssistantTool { listOf(toolAgent()) },
			buildJsonObject { put("assistant", "Nobody") },
		)
		val duplicate = execute(
			ConfigReadAssistantTool { listOf(toolAgent(), toolAgent(id = "a2")) },
			buildJsonObject { put("assistant", "Aria") },
		)

		assertTrue(unknown.isError)
		assertTrue(unknown.content, unknown.content.contains("no assistant is named"))
		assertTrue(duplicate.isError)
		assertTrue(duplicate.content, duplicate.content.contains("more than one"))
	}

	@Test
	fun `an over-long prompt is cut and says so`() {
		val result = execute(
			ConfigReadAssistantTool {
				listOf(toolAgent(prompt = "p".repeat(MAX_PROPOSED_PROMPT_CHARS + 20)))
			},
			buildJsonObject { put("assistant", "Aria") },
		)

		val row = Json.parseToJsonElement(result.content).jsonObject
		assertEquals(true, row["promptTruncated"]?.jsonPrimitive?.content?.toBoolean())
		assertEquals(
			MAX_PROPOSED_PROMPT_CHARS,
			row["systemPrompt"]?.jsonPrimitive?.content?.length,
		)
	}

	@Test
	fun `a create draft is a proposal and says nothing has changed`() {
		val result = execute(
			ConfigProposeAssistantTool { listOf(toolAgent()) },
			buildJsonObject {
				put("name", "Helper")
				put("prompt", "Be brief.")
			},
		)

		val proposal = decodeAssistantProposal(result)
		assertNull(proposal.agentId)
		assertEquals("Helper", proposal.name)
		assertTrue(proposal.changePrompt)
		assertEquals("", proposal.cardJson)
		val persisted = result.persistedContent ?: error("persistedContent is required")
		assertTrue(persisted, persisted.contains("nothing has changed"))
		assertTrue(persisted, persisted.contains("save in the editor"))
		assertEquals(1, persisted.lines().size)
	}

	@Test
	fun `an update draft targets the resolved row`() {
		val result = execute(
			ConfigProposeAssistantTool { listOf(toolAgent()) },
			buildJsonObject {
				put("assistant", "Aria")
				put("name", "Ria")
			},
		)

		val proposal = decodeAssistantProposal(result)
		assertEquals("a1", proposal.agentId)
		assertTrue(proposal.changeName)
		assertEquals("Ria", proposal.name)
	}

	@Test
	fun `a proposal for an unknown assistant is an error`() {
		val result = execute(
			ConfigProposeAssistantTool { listOf(toolAgent()) },
			buildJsonObject {
				put("assistant", "Nobody")
				put("name", "Ria")
			},
		)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("no assistant is named"))
		assertNull(result.persistedContent)
	}

	@Test
	fun `a duplicate new name is an error, not a second row`() {
		val result = execute(
			ConfigProposeAssistantTool { listOf(toolAgent()) },
			buildJsonObject {
				put("name", "Aria")
				put("prompt", "Another one.")
			},
		)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("already exists"))
	}

	@Test
	fun `malformed proposal arguments are errors`() {
		val nameAsNumber = execute(
			ConfigProposeAssistantTool { emptyList() },
			buildJsonObject { put("name", 7) },
		)
		val cardAsArray = execute(
			ConfigProposeAssistantTool { emptyList() },
			buildJsonObject {
				put("name", "Helper")
				put("card", kotlinx.serialization.json.buildJsonArray {})
			},
		)

		assertTrue(nameAsNumber.isError)
		assertTrue(nameAsNumber.content, nameAsNumber.content.contains("name"))
		assertTrue(cardAsArray.isError)
		assertTrue(cardAsArray.content, cardAsArray.content.contains("card"))
	}

	// ------------------------------------------------------------- instructions

	@Test
	fun `the instruction list covers permanent workspaces and names their kinds`() {
		val result = execute(
			ConfigListInstructionsTool(
				loadWorkspaces = { listOf(notes, workshop, temporary) },
				readInstructions = { workspace ->
					if (workspace.id == workshop.id) {
						WorkspaceInstructions(body = "# rules", truncated = false, bytes = 7)
					} else {
						null
					}
				},
			),
			buildJsonObject {},
		)

		val rows = array(result)
		// The temporary workspace is excluded by construction.
		assertEquals(2, rows.size)
		val byName = rows.associateBy { it.jsonObject["workspace"]?.jsonPrimitive?.content }
		assertEquals(
			"ordinary",
			byName["Notes"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
		)
		assertEquals(
			"workshop",
			byName["Workshop"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
		)
		assertEquals(true, byName["Workshop"]?.jsonObject?.get("hasInstructions")?.jsonPrimitive?.content?.toBoolean())
		assertEquals(false, byName["Notes"]?.jsonObject?.get("hasInstructions")?.jsonPrimitive?.content?.toBoolean())
	}

	@Test
	fun `reading an instruction file returns text, or says there is none`() {
		val tool = ConfigReadInstructionTool(
			loadWorkspaces = { listOf(notes) },
			readInstructions = { WorkspaceInstructions(body = "# Notes", truncated = false, bytes = 7) },
		)
		val present = execute(tool, buildJsonObject { put("workspace", "Notes") })
		val absent = execute(
			ConfigReadInstructionTool({ listOf(notes) }, { _: WorkspaceEntity -> null }),
			buildJsonObject { put("workspace", "Notes") },
		)

		val row = Json.parseToJsonElement(present.content).jsonObject
		assertEquals(true, row["hasInstructions"]?.jsonPrimitive?.content?.toBoolean())
		assertEquals("# Notes", row["text"]?.jsonPrimitive?.content)
		val missing = Json.parseToJsonElement(absent.content).jsonObject
		assertEquals(false, missing["hasInstructions"]?.jsonPrimitive?.content?.toBoolean())
	}

	@Test
	fun `an instruction proposal names the workspace and never its id`() {
		val result = execute(
			ConfigProposeInstructionTool(
				loadWorkspaces = { listOf(notes) },
				currentWorkspace = { null },
				readInstructions = { _: WorkspaceEntity -> null },
			),
			buildJsonObject {
				put("workspace", "Notes")
				put("text", "# Instructions\n")
			},
		)

		val proposal = decodeInstructionProposal(result)
		assertEquals("Notes", proposal.workspaceName)
		assertEquals("# Instructions", proposal.text)
		assertFalse(result.content, result.content.contains(notes.id))
		val persisted = result.persistedContent ?: error("persistedContent is required")
		assertTrue(persisted, persisted.contains("nothing has been written"))
	}

	@Test
	fun `omitting the workspace drafts against this conversation's own`() {
		val result = execute(
			ConfigProposeInstructionTool(
				loadWorkspaces = { listOf(notes, workshop) },
				currentWorkspace = { workshop },
				readInstructions = { _: WorkspaceEntity -> null },
			),
			buildJsonObject { put("text", "# Workshop\n") },
		)

		assertEquals("Workshop", decodeInstructionProposal(result).workspaceName)
	}

	@Test
	fun `an instruction proposal for an unknown workspace is an error`() {
		val result = execute(
			ConfigProposeInstructionTool(
				loadWorkspaces = { listOf(notes) },
				currentWorkspace = { null },
				readInstructions = { _: WorkspaceEntity -> null },
			),
			buildJsonObject {
				put("workspace", "Missing")
				put("text", "# hi")
			},
		)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("no permanent workspace is named"))
	}

	@Test
	fun `an instruction proposal must carry text`() {
		val result = execute(
			ConfigProposeInstructionTool(
				loadWorkspaces = { listOf(notes) },
				currentWorkspace = { null },
				readInstructions = { _: WorkspaceEntity -> null },
			),
			buildJsonObject { put("workspace", "Notes") },
		)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("text"))
	}

	@Test
	fun `an instruction read failure becomes text`() {
		val failing: suspend (WorkspaceEntity) -> WorkspaceInstructions? = { _ -> throw IllegalStateException("disk gone") }
		val list = execute(
			ConfigListInstructionsTool({ listOf(notes) }, failing),
			buildJsonObject {},
		)
		val propose = execute(
			ConfigProposeInstructionTool({ listOf(notes) }, { null }, failing),
			buildJsonObject {
				put("workspace", "Notes")
				put("text", "# hi")
			},
		)

		assertTrue(list.isError)
		assertTrue(propose.isError)
		assertTrue(list.content, list.content.contains("could not be read"))
		assertTrue(propose.content, propose.content.contains("could not be prepared"))
	}

	// ------------------------------------------------- saved prompt library

	@Test
	fun `the saved prompt list is content, not an editor`() {
		val result = execute(
			ConfigListPromptsTool {
				listOf(
					toolPrompt("Terse", "Be brief."),
					toolPrompt("Notebook", "Keep notes tidy."),
				)
			},
			buildJsonObject {},
		)

		val rows = array(result)
		assertEquals(2, rows.size)
		val byTitle = rows.associateBy { it.jsonObject["title"]?.jsonPrimitive?.content }
		assertEquals(9, byTitle["Terse"]?.jsonObject?.get("chars")?.jsonPrimitive?.content?.toInt())
		assertTrue(
			byTitle["Notebook"]?.jsonObject?.get("preview")?.jsonPrimitive?.content
				?.contains("Keep notes tidy") == true,
		)
		assertTrue(result.persistedContent, result.persistedContent?.contains("Listed 2") == true)
	}

	@Test
	fun `reading a saved prompt returns its text and flags truncation`() {
		val long = "x".repeat(MAX_PROPOSED_PROMPT_CHARS + 5)
		val read = execute(
			ConfigReadPromptTool { listOf(toolPrompt("Long", long)) },
			buildJsonObject { put("title", "Long") },
		)

		assertFalse(read.isError)
		val row = Json.parseToJsonElement(read.content).jsonObject
		assertEquals(MAX_PROPOSED_PROMPT_CHARS, row["text"]?.jsonPrimitive?.content?.length)
		assertEquals(true, row["truncated"]?.jsonPrimitive?.content?.toBoolean())
		assertEquals(long.length, row["chars"]?.jsonPrimitive?.content?.toInt())
	}

	@Test
	fun `an unknown saved prompt is an error that names the list tool`() {
		val result = execute(
			ConfigReadPromptTool { listOf(toolPrompt("Terse", "Be brief.")) },
			buildJsonObject { put("title", "Missing") },
		)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("app_list_prompts"))
	}

	@Test
	fun `a new prompt proposal is a card, not a write`() {
		val result = execute(
			ConfigProposePromptTool { emptyList() },
			buildJsonObject {
				put("title", "Terse")
				put("text", "Be brief.")
			},
		)

		assertFalse(result.isError)
		val proposal = PromptProposal.fromJson(result.content) ?: error("proposal did not decode")
		assertEquals("Terse", proposal.title)
		assertEquals("Be brief.", proposal.text)
		assertNull(proposal.existingChars)
		assertTrue(result.persistedContent, result.persistedContent?.contains("nothing has been saved") == true)
	}

	@Test
	fun `a prompt proposal with an existing title says it will update it`() {
		val existing = "The old text."
		val result = execute(
			ConfigProposePromptTool { listOf(toolPrompt("Terse", existing)) },
			buildJsonObject {
				put("title", "Terse")
				put("text", "The new text.")
			},
		)

		val proposal = PromptProposal.fromJson(result.content) ?: error("proposal did not decode")
		assertEquals(existing.length, proposal.existingChars)
	}

	@Test
	fun `a no-op prompt proposal is refused`() {
		val text = "Same text."
		val result = execute(
			ConfigProposePromptTool { listOf(toolPrompt("Terse", text)) },
			buildJsonObject {
				put("title", "Terse")
				put("text", text)
			},
		)

		assertTrue(result.isError)
		assertTrue(result.content, result.content.contains("exact text"))
	}

	@Test
	fun `a prompt proposal needs a title and non-blank text`() {
		val tool = ConfigProposePromptTool { emptyList() }
		val noTitle = execute(tool, buildJsonObject { put("text", "x") })
		val blankText = execute(tool, buildJsonObject { put("title", "T"); put("text", "  ") })

		assertTrue(noTitle.isError)
		assertTrue(noTitle.content, noTitle.content.contains("title"))
		assertTrue(blankText.isError)
		assertTrue(blankText.content, blankText.content.contains("blank"))
	}
}
