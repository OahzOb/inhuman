package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.core.engine.roleplay.RoleplayCodec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The host-appended helpers of the built-in workshop.
 *
 * ## What these tools can and cannot do
 *
 * They can **read the app's own configuration** -- the assistant rows (names,
 * prompts, cards) and each permanent workspace's root `AGENTS.md` -- and they can
 * **prepare a draft**. They cannot write: a "propose" tool returns a
 * [AssistantProposal] or [InstructionProposal], the chat renders it as a card,
 * and the card's action opens the user's existing editor prefilled. The user
 * saves it there, or does not.
 *
 * That is the whole safety story of this workshop, and it is structural rather
 * than a sentence in a prompt: no row, file or approval rule is written by a tool
 * result. There is also no capability here -- no network, no execution, no
 * arbitrary file path. The instruction reader reaches exactly one known file
 * name in a workspace the app itself names; a proposal carries only display
 * names, never a workspace directory id.
 *
 * ## Why they are not catalogue entries
 *
 * Like `recall` and the `rp_*` helpers they are appended after the allow-list by
 * `ChatViewModel.buildTools`, so they never appear in the assistant editor, the
 * conversation tool sheet, the approval tables or the capability gap report. A
 * stored tool selection cannot acquire them, and a story cannot either: the plan
 * hides them when the conversation is a role-play scene.
 */
internal object ConfigTools {
	const val LIST_ASSISTANTS = "app_list_assistants"
	const val READ_ASSISTANT = "app_read_assistant"
	const val PROPOSE_ASSISTANT = "app_propose_assistant"
	const val LIST_INSTRUCTIONS = "app_list_instructions"
	const val READ_INSTRUCTION = "app_read_instruction"
	const val PROPOSE_INSTRUCTION = "app_propose_instruction"
}

/** The argument schema shared by the parameterless list tools. */
private fun emptyParameters(): Map<String, Any?> = objectSchema(emptyMap())

private fun objectSchema(
	properties: Map<String, Any?>,
	required: List<String> = emptyList(),
): Map<String, Any?> = mapOf(
	"type" to "object",
	"properties" to properties,
	"required" to required,
	"additionalProperties" to false,
)

private fun stringProperty(description: String): Map<String, Any?> =
	mapOf("type" to "string", "description" to description)

/**
 * The assistants this app has, as content.
 *
 * No tool names, no approval rules and no provider credentials: this exists so
 * the workshop can help configure an assistant, not so a request can become a
 * report on the app's capability surface. The preview is capped like every other
 * proposal preview, and the full text is a separate read.
 */
internal class ConfigListAssistantsTool(
	private val loadAgents: suspend () -> List<AgentEntity>,
) : Tool {

	override val name: String = ConfigTools.LIST_ASSISTANTS

	override val description: String = """
		List the user's assistants. This workshop is the one place
		the app lets an assistant see this list; use it before proposing a change
		so you edit the real thing instead of guessing. Each entry has the display
		name, whether the assistant has a usable character card, the prompt length
		and a short preview. Use app_read_assistant for the full prompt and card.
		Changing an assistant needs app_propose_assistant, and nothing is saved
		until the user saves it in the assistant editor.
	""".trimIndent()

	override val parameters: Map<String, Any?> = emptyParameters()

	override val mutates: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult {
		// A host read that fails is text the model can react to, not an exception
		// that ends the turn -- the same contract every other tool keeps.
		val agents = try {
			loadAgents()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			return ToolResult.error("$name: the assistant list could not be read.")
		}
		val content = buildJsonArray {
			agents.forEach { agent ->
				val card = RoleplayCodec.decodeCard(agent.rpJson)?.takeIf { !it.isBlank }
				add(
					buildJsonObject {
						put("name", agent.name)
						put("default", agent.isDefault)
						put("promptChars", agent.systemPrompt.length)
						put("promptPreview", previewLine(agent.systemPrompt))
						put("hasCard", card != null)
						put("cardName", card?.name?.takeIf { it.isNotBlank() })
						put("model", agent.model)
					},
				)
			}
		}.toString()
		return ToolResult.ok(
			content = content,
			persistedContent = "Listed ${agents.size} assistant(s).",
		)
	}
}

/**
 * One assistant's content: the prompt and the card.
 *
 * Deliberately not the tool selection, provider or approval rules. Those are the
 * editor's fields and the user's alone; a helper that could read them would be a
 * directory of the app's capability surface, and one that could propose them
 * would be asking the model to widen its own ceiling.
 */
internal class ConfigReadAssistantTool(
	private val loadAgents: suspend () -> List<AgentEntity>,
) : Tool {

	override val name: String = ConfigTools.READ_ASSISTANT

	override val description: String = """
		Read one assistant's system prompt and character card. Use it before
		app_propose_assistant when you need to rewrite existing text: without
		reading first you would be replacing instructions the user wrote. The
		prompt is cut at $MAX_PROPOSED_PROMPT_CHARS characters; the full text
		stays in the assistant editor. Reading changes nothing.
	""".trimIndent()

	override val parameters: Map<String, Any?> = objectSchema(
		properties = mapOf(
			"assistant" to stringProperty("The assistant's display name, as app_list_assistants shows it."),
		),
		required = listOf("assistant"),
	)

	override val mutates: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult = try {
		read(arguments)
	} catch (e: CancellationException) {
		throw e
	} catch (e: IllegalArgumentException) {
		ToolResult.error("$name: ${e.message ?: "invalid arguments"}")
	} catch (e: Exception) {
		ToolResult.error("$name: that read failed; nothing was changed.")
	}

	private suspend fun read(arguments: JsonObject): ToolResult {
		val wanted = arguments.stringArgument("assistant")?.trim().orEmpty()
		if (wanted.isEmpty()) {
			return ToolResult.error("$name: pass `assistant`, the display name.")
		}
		val matches = loadAgents().filter { it.name.trim() == wanted }
		val agent = when (matches.size) {
			0 -> return ToolResult.error(
				"$name: no assistant is named \"$wanted\"; use app_list_assistants " +
					"to see the names.",
			)
			1 -> matches.single()
			else -> return ToolResult.error(
				"$name: more than one assistant is named \"$wanted\"; rename one so " +
					"the name is unique.",
			)
		}
		val prompt = agent.systemPrompt
		val truncated = prompt.length > MAX_PROPOSED_PROMPT_CHARS
		val card = RoleplayCodec.decodeCard(agent.rpJson)?.takeIf { !it.isBlank }
		val content = buildJsonObject {
			put("name", agent.name)
			put("systemPrompt", if (truncated) prompt.take(MAX_PROPOSED_PROMPT_CHARS) else prompt)
			put("promptTruncated", truncated)
			put("cardJson", if (card == null) null else agent.rpJson)
			put("model", agent.model)
		}.toString()
		return ToolResult.ok(
			content = content,
			persistedContent = "Read assistant \"${agent.name}\" (${prompt.length} prompt chars).",
		)
	}
}

/**
 * Prepare a new assistant, or an edit to one, for the user to review.
 *
 * Only content fields are accepted: name, system prompt, character card. A create
 * opens the assistant editor with the app's new-assistant defaults (tools, model,
 * approval rules), so the user still chooses those; an update leaves every field
 * the proposal did not name exactly as it was.
 */
internal class ConfigProposeAssistantTool(
	private val loadAgents: suspend () -> List<AgentEntity>,
) : Tool {

	override val name: String = ConfigTools.PROPOSE_ASSISTANT

	override val description: String = """
		Prepare a draft assistant, or a draft edit to one, for the user to save.
		This changes nothing: it returns a proposal that opens the assistant editor
		prefilled, and the user presses Save there. Never say or imply that the
		assistant was created or updated. Read the current values with
		app_read_assistant before proposing an update, and pass only the fields
		that should change. To create, omit `assistant` and pass a `name`, plus a
		`prompt` and/or a `card`. A draft cannot carry tools, a provider, a model
		or approval rules: the user chooses those in the editor.
	""".trimIndent()

	override val parameters: Map<String, Any?> = objectSchema(
		properties = mapOf(
			"assistant" to stringProperty(
				"The existing assistant to edit, by display name. Omit to create a new one.",
			),
			"name" to stringProperty(
				"The assistant's display name. Required when creating; optional when " +
					"renaming an existing one.",
			),
			"prompt" to stringProperty(
				"The full new system prompt. For an update, omit it to keep the existing " +
					"prompt, or pass an empty string to clear it (only possible when the " +
					"assistant keeps a character card).",
			),
			"card" to mapOf(
				"type" to "object",
				"description" to "Optional character card with the same fields as " +
					"rp_format. On an update, omit it to keep the existing card; pass " +
					"an empty object to clear it.",
				"properties" to RoleplayToolSchema.cardProperties(),
			),
		),
		required = emptyList(),
	)

	override val mutates: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult = try {
		prepare(arguments)
	} catch (e: CancellationException) {
		throw e
	} catch (e: IllegalArgumentException) {
		// Wrong JSON types are ordinary model mistakes; the tool contract is that
		// they come back as text the model can read, not an exception that ends
		// the turn.
		ToolResult.error("$name: ${e.message ?: "invalid arguments"}")
	} catch (e: Exception) {
		ToolResult.error("$name: the draft could not be prepared; nothing was changed.")
	}

	private suspend fun prepare(arguments: JsonObject): ToolResult {
		val assistantName = arguments.stringArgument("assistant")?.trim()?.takeIf { it.isNotEmpty() }
		val newName = arguments.stringArgument("name")
		val prompt = arguments.stringArgument("prompt")
		val card = arguments.objectArgument("card")
		val agents = loadAgents()
		val outcome = if (assistantName == null) {
			AssistantProposals.create(name = newName, prompt = prompt, card = card, agents = agents)
		} else {
			val matches = agents.filter { it.name.trim() == assistantName }
			when (matches.size) {
				0 -> return ToolResult.error(
					"$name: no assistant is named \"$assistantName\"; use " +
						"app_list_assistants to see the names.",
				)
				1 -> AssistantProposals.update(matches.single(), newName, prompt, card)
				else -> return ToolResult.error(
					"$name: more than one assistant is named \"$assistantName\"; rename " +
						"one so the name is unique.",
				)
			}
		}
		return when (outcome) {
			is DraftResult.Rejected -> ToolResult.error("$name: ${outcome.message}")
			is DraftResult.Ok -> ToolResult.ok(
				content = outcome.value.toJson(),
				persistedContent = "Assistant proposal ready for ${outcome.value.name}; " +
					"nothing has changed — the user must open it and save in the editor.",
			)
		}
	}
}

/**
 * The permanent workspaces and whether each has a root `AGENTS.md`.
 *
 * Names, not ids: a workspace's directory name is the opaque id and never enters
 * a request. The kind is included because "this is the workshop" tells an
 * assistant that the file can cover role-play authoring as well as app
 * configuration.
 */
internal class ConfigListInstructionsTool(
	private val loadWorkspaces: suspend () -> List<WorkspaceEntity>,
	private val readInstructions: suspend (WorkspaceEntity) -> WorkspaceInstructions?,
) : Tool {

	override val name: String = ConfigTools.LIST_INSTRUCTIONS

	override val description: String = """
		List the permanent workspaces and their root AGENTS.md files. A workspace's
		AGENTS.md is the one file the app turns into standing instructions for new
		conversations there, and for new scenes created in it, so this is where to
		look before proposing an edit. Each entry has the display name, what the
		workspace is (ordinary or workshop), whether an AGENTS.md exists and its
		size. Use app_read_instruction for the text and app_propose_instruction to
		draft a change.
	""".trimIndent()

	override val parameters: Map<String, Any?> = emptyParameters()

	override val mutates: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val workspaces = try {
			loadWorkspaces().filter { it.isPermanent }.sortedBy { it.name.lowercase() }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			return ToolResult.error("$name: the workspace list could not be read.")
		}
		val rows = try {
			workspaces.map { workspace -> workspace to readInstructions(workspace) }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			return ToolResult.error("$name: an instruction file could not be read.")
		}
		val content = buildJsonArray {
			rows.forEach { (workspace, current) ->
				add(
					buildJsonObject {
						put("workspace", workspace.name)
						put("kind", workspaceKindLabel(workspace.kind))
						put("hasInstructions", current != null)
						put("bytes", current?.bytes)
					},
				)
			}
		}.toString()
		return ToolResult.ok(
			content = content,
			persistedContent = "Listed ${workspaces.size} permanent workspace(s).",
		)
	}
}

/**
 * Read one permanent workspace's root `AGENTS.md`.
 *
 * The path is not an argument: this reads exactly the app's instruction file in a
 * workspace named by its display name. It cannot be turned into a way to read a
 * workspace's other files, and it never opens a temporary workspace (whose
 * instructions cannot outlive its one conversation anyway).
 */
internal class ConfigReadInstructionTool(
	private val loadWorkspaces: suspend () -> List<WorkspaceEntity>,
	private val readInstructions: suspend (WorkspaceEntity) -> WorkspaceInstructions?,
) : Tool {

	override val name: String = ConfigTools.READ_INSTRUCTION

	override val description: String = """
		Read one permanent workspace's AGENTS.md. Use it before proposing a rewrite;
		a missing file is a normal answer. The text is capped at the same
		${MAX_WORKSPACE_INSTRUCTIONS_BYTES / 1024} KiB the app reads into a prompt.
		Reading changes nothing.
	""".trimIndent()

	override val parameters: Map<String, Any?> = objectSchema(
		properties = mapOf(
			"workspace" to stringProperty(
				"The permanent workspace's display name, as app_list_instructions shows it.",
			),
		),
		required = listOf("workspace"),
	)

	override val mutates: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult = try {
		read(arguments)
	} catch (e: CancellationException) {
		throw e
	} catch (e: IllegalArgumentException) {
		ToolResult.error("$name: ${e.message ?: "invalid arguments"}")
	} catch (e: Exception) {
		ToolResult.error("$name: that read failed; nothing was changed.")
	}

	private suspend fun read(arguments: JsonObject): ToolResult {
		val wanted = arguments.stringArgument("workspace")?.trim().orEmpty()
		if (wanted.isEmpty()) {
			return ToolResult.error("$name: pass `workspace`, the display name.")
		}
		val target = when (val lookup = resolveInstructionWorkspace(permanent(), wanted)) {
			is WorkspaceLookup.Missing -> return ToolResult.error(
				"$name: no permanent workspace is named \"$wanted\"; use " +
					"app_list_instructions to see the workspaces.",
			)
			is WorkspaceLookup.Ambiguous -> return ToolResult.error(
				"$name: more than one permanent workspace is named \"$wanted\"; rename " +
					"one so the name is unique.",
			)
			is WorkspaceLookup.Ok -> lookup.workspace
		}
		val current = readInstructions(target)
		val content = buildJsonObject {
			put("workspace", target.name)
			put("hasInstructions", current != null)
			put("text", current?.body)
			put("bytes", current?.bytes)
			put("truncated", current?.truncated ?: false)
		}.toString()
		return ToolResult.ok(
			content = content,
			persistedContent = if (current == null) {
				"Read AGENTS.md for \"${target.name}\": no file."
			} else {
				"Read AGENTS.md for \"${target.name}\" (${current.bytes} bytes)."
			},
		)
	}

	private suspend fun permanent(): List<WorkspaceEntity> =
		loadWorkspaces().filter { it.isPermanent }
}

/**
 * Prepare a root `AGENTS.md` for one permanent workspace.
 *
 * The write still belongs to the user: the proposal opens the Files text editor
 * prefilled, and Save goes through the ordinary `WorkspaceTransfer`, so the
 * workspace quota, per-file ceiling and atomic replace all still apply. An empty
 * `text` drafts a clear; saving it leaves an empty file, which the instruction
 * reader treats as "no instructions".
 */
internal class ConfigProposeInstructionTool(
	private val loadWorkspaces: suspend () -> List<WorkspaceEntity>,
	private val currentWorkspace: suspend () -> WorkspaceEntity?,
	private val readInstructions: suspend (WorkspaceEntity) -> WorkspaceInstructions?,
) : Tool {

	override val name: String = ConfigTools.PROPOSE_INSTRUCTION

	override val description: String = """
		Prepare an AGENTS.md for a permanent workspace, for the user to save. This
		writes nothing: it returns a proposal that opens the file editor prefilled,
		and the user saves there. Never say or imply that the file was written.
		Read the current text with app_read_instruction before rewriting it. Omit
		`workspace` to target this conversation's own workspace; pass the display
		name to target another one. Pass the full new text; an empty string drafts
		clearing the file. The file becomes standing instructions for new
		conversations in that workspace; existing conversations only see it after
		the user reloads it.
	""".trimIndent()

	override val parameters: Map<String, Any?> = objectSchema(
		properties = mapOf(
			"workspace" to stringProperty(
				"Display name of a permanent workspace. Omit to use this conversation's " +
					"own workspace.",
			),
			"text" to stringProperty(
				"The full new AGENTS.md. Pass an empty string to draft clearing it.",
			),
		),
		required = listOf("text"),
	)

	override val mutates: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult = try {
		prepare(arguments)
	} catch (e: CancellationException) {
		throw e
	} catch (e: IllegalArgumentException) {
		ToolResult.error("$name: ${e.message ?: "invalid arguments"}")
	} catch (e: Exception) {
		ToolResult.error("$name: the draft could not be prepared; nothing was changed.")
	}

	private suspend fun prepare(arguments: JsonObject): ToolResult {
		val requested = arguments.stringArgument("workspace")?.trim()?.takeIf { it.isNotEmpty() }
		val text = arguments.stringArgument("text")
		if (text == null) {
			return ToolResult.error(
				"$name: pass `text`, the full AGENTS.md content " +
					"(an empty string drafts clearing the file); nothing was prepared.",
			)
		}
		val permanent = loadWorkspaces().filter { it.isPermanent }
		val target = if (requested == null) {
			val own = currentWorkspace()
			if (own == null || !own.isPermanent) {
				return ToolResult.error(
					"$name: this conversation has no permanent workspace; pass " +
						"`workspace` by display name.",
				)
			}
			own
		} else {
			when (val lookup = resolveInstructionWorkspace(permanent, requested)) {
				is WorkspaceLookup.Missing -> return ToolResult.error(
					"$name: no permanent workspace is named \"$requested\"; use " +
						"app_list_instructions to see the workspaces.",
				)
				is WorkspaceLookup.Ambiguous -> return ToolResult.error(
					"$name: more than one permanent workspace is named \"$requested\"; " +
						"rename one so the name is unique.",
				)
				is WorkspaceLookup.Ok -> lookup.workspace
			}
		}
		return when (val outcome = InstructionProposals.prepare(target, text, readInstructions(target))) {
			is DraftResult.Rejected -> ToolResult.error("$name: ${outcome.message}")
			is DraftResult.Ok -> ToolResult.ok(
				content = outcome.value.toJson(),
				persistedContent = "AGENTS.md proposal ready for " +
					"${outcome.value.workspaceName}; nothing has been written — the user " +
					"must open it and save in the file editor.",
			)
		}
	}
}
