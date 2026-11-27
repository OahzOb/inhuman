package com.inhuman.ui.chat

import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.SavedPromptEntity
import com.inhuman.core.data.db.WorkspaceEntity
import com.inhuman.core.engine.roleplay.RoleplayCodec
import com.inhuman.core.engine.roleplay.RoleplayLimits
import com.inhuman.core.engine.roleplay.RoleplayValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What a configuration-workshop helper prepared, and what the user then opens.
 *
 * ## Drafts, not edits
 *
 * In the workshop the host appends helpers that can list and read the app's own
 * configuration (assistants, each permanent workspace's root `AGENTS.md`, and the
 * saved-prompt library). A helper that "writes" returns one of these proposals
 * instead: the chat renders it as a card, and the card's action opens the user's
 * existing editor prefilled or, for a saved prompt, offers the one save tap. The
 * user saves it, or does not.
 *
 * That split is the whole design (`docs/proposals/config-workshop.md`):
 *
 * - **No tool changes configuration.** No row, no file and no approval rule is
 *   written by a tool result. The only writers are the two editors the user
 *   already had, and they still write only when the user presses Save.
 * - **No capability is added.** A proposal cannot carry a tool selection, a
 *   provider, a model, an approval rule, an SSH grant or a download switch; those
 *   are edit fields in the editor and the user's alone. The helpers reach no
 *   network and execute nothing.
 * - **The workspace directory id never enters a request.** An instruction
 *   proposal names a workspace by its display name; the app resolves that name
 *   when the user taps. The proposal itself is data in the transcript, so a raw
 *   id there would be an opaque path sent to the model.
 *
 * A proposal that is never opened leaves no trace: like the scene proposal, it
 * is a tool result and nothing else.
 */

/**
 * The longest proposed assistant prompt.
 *
 * A proposal is read twice (in the tool call and in its result) and kept in the
 * transcript. 64,000 characters is already larger than a comfortable context
 * window's worth of instructions; a longer assistant already exists, but a
 * helper rewriting it is not the tool that should decide to carry it.
 */
internal const val MAX_PROPOSED_PROMPT_CHARS = 64_000

/** What preparing a configuration draft produced. */
internal sealed interface DraftResult<out T> {
	data class Ok<T>(val value: T) : DraftResult<T>

	data class Rejected(val message: String) : DraftResult<Nothing>
}

/**
 * A suggested assistant, or a suggested edit to one.
 *
 * Only content fields are representable: the display name, the system prompt and
 * the role-play card. Tools, provider, model, reasoning, suggestion settings and
 * approval rules are deliberately absent -- the editor opens with the row's
 * current values (or the new-assistant defaults), and the user decides there.
 *
 * Null [prompt] and false [changePrompt] mean "leave the existing prompt alone";
 * [changeCard] false means the same for the card, and [changeCard] true with an
 * empty [cardJson] means "clear it". A create proposal always writes the name and
 * at least one of prompt/card.
 */
data class AssistantProposal(
	/** The assistant to edit, or null to create a new one. */
	val agentId: String?,
	/** Display name: the proposed name, or the existing one for an update. */
	val name: String,
	val changeName: Boolean,
	/** The full new prompt, when [changePrompt] is true. */
	val prompt: String?,
	val changePrompt: Boolean,
	/** The card to write when [changeCard] is true; "" clears it. */
	val cardJson: String,
	val changeCard: Boolean,
	/** One-line preview for the card. */
	val summary: String,
) {

	/** The exact text the tool returns and the app later parses back. */
	fun toJson(): String = buildJsonObject {
		put("kind", KIND)
		put("agentId", agentId)
		put("name", name)
		put("changeName", changeName)
		put("prompt", prompt)
		put("changePrompt", changePrompt)
		put("cardJson", cardJson)
		put("changeCard", changeCard)
		put("summary", summary)
	}.toString()

	companion object {
		const val KIND = "assistant"

		/**
		 * Decode a proposal, or null when [text] is not a usable one.
		 *
		 * Total on purpose: the tool result is stored in the transcript, and a
		 * truncated or hand-edited row must render as "no card", not crash the
		 * chat. The pairing rules (a create writes a name and some content; an
		 * update changes at least one field) are re-checked here because a
		 * proposal that violates them has no editor state to open.
		 */
		fun fromJson(text: String): AssistantProposal? {
			val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
				?: return null
			if (root.stringOrNull("kind") != KIND) return null
			val agentId = when {
				root["agentId"] == null || root["agentId"] is JsonNull -> null
				else -> root.stringOrNull("agentId")?.takeIf { it.isNotBlank() } ?: return null
			}
			val name = root.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
			val changeName = root.boolOrNull("changeName") ?: return null
			val prompt = root.stringOrNull("prompt")
			val changePrompt = root.boolOrNull("changePrompt") ?: return null
			val cardJson = root.stringOrNull("cardJson") ?: return null
			val changeCard = root.boolOrNull("changeCard") ?: return null
			val summary = root.stringOrNull("summary") ?: return null

			if (changePrompt != (prompt != null)) return null
			if (!changeCard && cardJson.isNotEmpty()) return null
			if (prompt != null && prompt.length > MAX_PROPOSED_PROMPT_CHARS) return null
			if (cardJson.length > RoleplayLimits.MAX_CARD_CHARS) return null
			// A proposed card must be one the editor can open; otherwise the
			// draft would silently lose it and look like a card-clearing edit.
			if (changeCard && cardJson.isNotEmpty() && RoleplayCodec.decodeCard(cardJson) == null) {
				return null
			}
			if (agentId == null) {
				if (!changeName) return null
				if (!changePrompt && !changeCard) return null
			} else if (!changeName && !changePrompt && !changeCard) {
				return null
			}
			return AssistantProposal(
				agentId = agentId,
				name = name,
				changeName = changeName,
				prompt = prompt,
				changePrompt = changePrompt,
				cardJson = cardJson,
				changeCard = changeCard,
				summary = summary,
			)
		}
	}
}

/**
 * A suggested root `AGENTS.md`, for one permanent workspace.
 *
 * The workspace is named, never identified: [workspaceName] is what the user
 * sees, and the app resolves it when the card's action opens the editor. An
 * empty [text] means "clear the file" -- the editor is still the writer, and an
 * empty file reads as "no workspace instructions".
 */
data class InstructionProposal(
	val workspaceName: String,
	/** Normalised text; empty clears the file. */
	val text: String,
	/** The current file's size, or null when the workspace has none. */
	val existingBytes: Int?,
	/** One-line preview for the card. */
	val summary: String,
) {

	fun toJson(): String = buildJsonObject {
		put("kind", KIND)
		put("workspaceName", workspaceName)
		put("text", text)
		put("existingBytes", existingBytes)
		put("summary", summary)
	}.toString()

	companion object {
		const val KIND = "instruction"

		fun fromJson(text: String): InstructionProposal? {
			val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
				?: return null
			if (root.stringOrNull("kind") != KIND) return null
			val workspaceName = root.stringOrNull("workspaceName")
				?.takeIf { it.isNotBlank() } ?: return null
			val body = root.stringOrNull("text") ?: return null
			val summary = root.stringOrNull("summary") ?: return null
			val existingBytes = root["existingBytes"].let { element ->
				when {
					element == null || element is JsonNull -> null
					else -> {
						val value = (element as? JsonPrimitive)?.intOrNull ?: return null
						value.takeIf { it >= 0 } ?: return null
					}
				}
			}
			if (body.contains('\u0000')) return null
			if (body.toByteArray(Charsets.UTF_8).size > MAX_WORKSPACE_INSTRUCTIONS_BYTES) return null
			return InstructionProposal(
				workspaceName = workspaceName,
				text = body,
				existingBytes = existingBytes,
				summary = summary,
			)
		}
	}
}

/** The longest title a prompt-library proposal may carry. */
internal const val MAX_PROMPT_TITLE_CHARS = 120

/**
 * A suggested entry in the user's saved-prompt library.
 *
 * Saves are not editor-mediated the way assistant and instruction proposals are
 * -- the library has no text editor, only a list and a "store current prompt"
 * dialog. The proposal therefore renders its own card with a **Save to prompt
 * library** button; that tap is the write, and the tool itself changes nothing.
 * [existingChars] non-null means the title already exists and saving updates that
 * entry (the library's title-unique rule), which the card must say.
 */
data class PromptProposal(
	val title: String,
	val text: String,
	/** Length of the existing entry with this title, or null for a new one. */
	val existingChars: Int?,
	/** One-line preview for the card. */
	val summary: String,
) {

	fun toJson(): String = buildJsonObject {
		put("kind", KIND)
		put("title", title)
		put("text", text)
		put("existingChars", existingChars)
		put("summary", summary)
	}.toString()

	companion object {
		const val KIND = "prompt"

		/**
		 * Decode a proposal, or null when [text] is not a usable one. Total for
		 * the same reason the other proposals are: a truncated transcript row
		 * must render as "no card", not crash the chat.
		 */
		fun fromJson(text: String): PromptProposal? {
			val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
				?: return null
			if (root.stringOrNull("kind") != KIND) return null
			val title = root.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: return null
			if (title.length > MAX_PROMPT_TITLE_CHARS) return null
			val body = root.stringOrNull("text")?.takeIf { it.isNotBlank() } ?: return null
			if (body.contains('\u0000')) return null
			if (body.length > MAX_PROPOSED_PROMPT_CHARS) return null
			val summary = root.stringOrNull("summary") ?: return null
			val existingChars = root["existingChars"].let { element ->
				when {
					element == null || element is JsonNull -> null
					else -> {
						val value = (element as? JsonPrimitive)?.intOrNull ?: return null
						value.takeIf { it >= 0 } ?: return null
					}
				}
			}
			return PromptProposal(title, body, existingChars, summary)
		}
	}
}

/**
 * Builds [PromptProposal] values from the helper's arguments.
 *
 * The validation is deliberately small: a title (the library's unique key) and
 * non-blank text within the same proposal cap the assistant prompt uses. An
 * update whose text equals the existing entry is refused, so a card never asks
 * the user to save a no-op.
 */
internal object PromptProposals {

	fun prepare(
		title: String,
		text: String,
		prompts: List<SavedPromptEntity>,
	): DraftResult<PromptProposal> {
		val cleanTitle = title.trim()
		if (cleanTitle.isEmpty()) {
			return DraftResult.Rejected("the saved prompt needs a title; nothing was prepared")
		}
		if (cleanTitle.length > MAX_PROMPT_TITLE_CHARS) {
			return DraftResult.Rejected(
				"the title is longer than $MAX_PROMPT_TITLE_CHARS characters; " +
					"shorten it; nothing was prepared",
			)
		}
		if (text.isBlank()) {
			return DraftResult.Rejected("the prompt text is blank; nothing was prepared")
		}
		if (text.contains('\u0000')) {
			return DraftResult.Rejected("the prompt text contains a NUL character; nothing was prepared")
		}
		if (text.length > MAX_PROPOSED_PROMPT_CHARS) {
			return DraftResult.Rejected(
				"the prompt is longer than $MAX_PROPOSED_PROMPT_CHARS characters; " +
					"shorten it; nothing was prepared",
			)
		}
		val existing = prompts.firstOrNull { it.title.trim() == cleanTitle }
		if (existing != null && existing.text == text) {
			return DraftResult.Rejected(
				"the library already has this exact text under \"$cleanTitle\"; " +
					"nothing was prepared",
			)
		}
		return DraftResult.Ok(
			PromptProposal(
				title = cleanTitle,
				text = text,
				existingChars = existing?.text?.length,
				summary = previewLine(text),
			),
		)
	}
}

/**
 * The editor to open for an assistant proposal.
 *
 * Null fields mean "leave this alone": the assistant editor starts from the
 * existing row and applies only what the proposal actually changes.
 */
data class AssistantEditRequest(
	/** The row to edit, or null to start a new assistant. */
	val agentId: String?,
	val name: String?,
	val systemPrompt: String?,
	val cardJson: String?,
)

/** The Files editor to open for an instruction proposal. */
data class InstructionEditRequest(
	val workspaceId: String,
	val workspaceName: String,
	val text: String,
	val path: String = WORKSPACE_INSTRUCTIONS_FILE,
)

/**
 * Builds [AssistantProposal] values from a helper's arguments.
 *
 * The validators are the editor's own rules, not a second schema: the editor
 * requires a name and (a prompt or a card), and a proposal that cannot be saved
 * would open an editor whose Save button is disabled with no explanation. A card
 * goes through [RoleplayValidator]/[RoleplayCodec] for the same reason the scene
 * proposal does -- there must be one answer to "is this card usable".
 */
internal object AssistantProposals {

	fun create(
		name: String?,
		prompt: String?,
		card: JsonObject?,
		agents: List<AgentEntity>,
	): DraftResult<AssistantProposal> {
		val cleanName = name?.trim().orEmpty()
		if (cleanName.isEmpty()) {
			return DraftResult.Rejected("the assistant needs a name; nothing was prepared")
		}
		// The editor allows duplicate names, but a proposal makes names the way
		// it names an existing row for update. Creating a second "Aria" would
		// make every later update-by-name ambiguous, so this helper refuses and
		// points at the update form instead.
		if (agents.any { it.name.trim() == cleanName }) {
			return DraftResult.Rejected(
				"an assistant named \"$cleanName\" already exists; propose an update " +
					"for it instead, or choose another name",
			)
		}

		val cardOutcome = when (card) {
			null -> DraftResult.Ok("")
			else -> normaliseCard(card)
		}
		val cardJson = when (cardOutcome) {
			is DraftResult.Rejected -> return cardOutcome
			is DraftResult.Ok -> cardOutcome.value
		}

		val cleanPrompt = prompt?.trim()?.takeIf { it.isNotEmpty() }
		if (cleanPrompt != null && cleanPrompt.length > MAX_PROPOSED_PROMPT_CHARS) {
			return DraftResult.Rejected(
				"the prompt is longer than $MAX_PROPOSED_PROMPT_CHARS characters; " +
					"shorten it or paste it in the editor; nothing was prepared",
			)
		}
		if (cleanPrompt == null && cardJson.isEmpty()) {
			return DraftResult.Rejected(
				"a new assistant needs a prompt or a character card; nothing was prepared",
			)
		}
		return DraftResult.Ok(
			AssistantProposal(
				agentId = null,
				name = cleanName,
				changeName = true,
				prompt = cleanPrompt,
				changePrompt = cleanPrompt != null,
				cardJson = cardJson,
				changeCard = true,
				summary = previewLine(cleanPrompt ?: cardDescription(cardJson)),
			),
		)
	}

	/**
	 * Collect the fields an update would actually change.
	 *
	 * A field equal to its current value is dropped rather than carried: the
	 * proposal describes a change, and an editor prefilled with "everything I
	 * already had" would save a no-op. At least one real change is required, so a
	 * card cannot open an editor with nothing to do.
	 */
	fun update(
		agent: AgentEntity,
		name: String?,
		prompt: String?,
		card: JsonObject?,
	): DraftResult<AssistantProposal> {
		val newName = when {
			name == null -> agent.name.trim()
			else -> name.trim().also {
				if (it.isEmpty()) {
					return DraftResult.Rejected(
						"the assistant name cannot be empty; nothing was prepared",
					)
				}
			}
		}
		val cardOutcome = when (card) {
			null -> DraftResult.Ok(agent.rpJson)
			else -> normaliseCard(card)
		}
		val resultCard = when (cardOutcome) {
			is DraftResult.Rejected -> return cardOutcome
			is DraftResult.Ok -> cardOutcome.value
		}
		val newPrompt = when {
			prompt == null -> agent.systemPrompt
			else -> prompt.trim()
		}
		if (newPrompt.isNotBlank() && newPrompt.length > MAX_PROPOSED_PROMPT_CHARS) {
			return DraftResult.Rejected(
				"the prompt is longer than $MAX_PROPOSED_PROMPT_CHARS characters; " +
					"shorten it or edit it in the editor; nothing was prepared",
			)
		}
		if (newPrompt.isBlank() && resultCard.isEmpty()) {
			return DraftResult.Rejected(
				"that would leave the assistant with neither instructions nor a card; " +
					"nothing was prepared",
			)
		}

		val changeName = newName != agent.name.trim()
		val changePrompt = prompt != null && newPrompt != agent.systemPrompt.trim()
		val changeCard = card != null && resultCard != agent.rpJson
		if (!changeName && !changePrompt && !changeCard) {
			return DraftResult.Rejected(
				"the proposal would not change anything; nothing was prepared",
			)
		}
		return DraftResult.Ok(
			AssistantProposal(
				agentId = agent.id,
				name = newName,
				changeName = changeName,
				prompt = if (changePrompt) newPrompt else null,
				changePrompt = changePrompt,
				cardJson = if (changeCard) resultCard else "",
				changeCard = changeCard,
				summary = previewLine(
					(if (prompt != null) newPrompt else agent.systemPrompt)
						.ifBlank { cardDescription(resultCard) },
				),
			),
		)
	}

	/**
	 * Canonicalise a card the model wrote, or explain why it cannot be used.
	 *
	 * An all-blank card is stored as `""` -- the codec's spelling of "no card" --
	 * because a proposal that opens the editor with an empty card is the same
	 * state, and the editor's own encoder agrees. Only an empty object is treated
	 * as an explicit clear; a card that has fields but leaves them blank is
	 * refused as a likely mistake rather than silently wiping the user's card.
	 */
	private fun normaliseCard(card: JsonObject): DraftResult<String> {
		// The deliberate "remove the card" gesture: `card: {}` is unambiguous,
		// while `card: {"name": ""}` is far more likely to be a model slip.
		if (card.isEmpty()) return DraftResult.Ok("")
		val raw = card.toString()
		val validation = RoleplayValidator.validateCard(raw)
		if (!validation.ok) {
			return DraftResult.Rejected(
				blockingProblems("the card", validation.issues) + "\nnothing was prepared",
			)
		}
		val decoded = RoleplayCodec.decodeCard(raw)
			?: return DraftResult.Rejected("the card has no fields to save; nothing was prepared")
		return DraftResult.Ok(if (decoded.isBlank) "" else RoleplayCodec.encodeCard(decoded))
	}
}

/**
 * Builds [InstructionProposal] values from a helper's arguments.
 *
 * The normalisation is the reader's: BOM removed, CRLF and lone CR turned into
 * LF, trailing whitespace trimmed. What the proposal stores is therefore what
 * `readWorkspaceInstructions` would produce from the same text, so "already the
 * same" is a real equality and not a formatting difference.
 */
internal object InstructionProposals {

	fun prepare(
		workspace: WorkspaceEntity,
		text: String?,
		current: WorkspaceInstructions?,
	): DraftResult<InstructionProposal> {
		if (!workspace.isPermanent) {
			return DraftResult.Rejected(
				"only a permanent workspace can own an instruction file; nothing was prepared",
			)
		}
		if (text == null) {
			return DraftResult.Rejected(
				"pass the full AGENTS.md text (an empty string clears the file); " +
					"nothing was prepared",
			)
		}
		if (text.contains('\u0000')) {
			return DraftResult.Rejected(
				"the text contains a NUL character, so it is not a text file; " +
					"nothing was prepared",
			)
		}
		val normalised = normaliseInstruction(text)
		if (normalised == current?.body) {
			return DraftResult.Rejected(
				"that workspace's AGENTS.md already has exactly this text; " +
					"nothing was prepared",
			)
		}
		val bytes = normalised.toByteArray(Charsets.UTF_8).size
		if (bytes > MAX_WORKSPACE_INSTRUCTIONS_BYTES) {
			return DraftResult.Rejected(
				"the text is ${bytes} bytes, over the " +
					"${MAX_WORKSPACE_INSTRUCTIONS_BYTES / 1024} KiB instruction limit; " +
					"nothing was prepared",
			)
		}
		return DraftResult.Ok(
			InstructionProposal(
				workspaceName = workspace.name,
				text = normalised,
				existingBytes = current?.bytes,
				summary = if (normalised.isEmpty()) "" else previewLine(normalised),
			),
		)
	}

	/** The reader's normalisation, applied to text a user is about to save. */
	fun normaliseInstruction(text: String): String = text
		.removePrefix("\uFEFF")
		.replace("\r\n", "\n")
		.replace('\r', '\n')
		.trimEnd()
}

/** The outcome of resolving a workspace by the display name a proposal named. */
internal sealed interface WorkspaceLookup {
	data class Ok(val workspace: WorkspaceEntity) : WorkspaceLookup

	data class Missing(val name: String) : WorkspaceLookup

	data class Ambiguous(val name: String) : WorkspaceLookup
}

/**
 * Resolve a permanent workspace by its display name.
 *
 * The display name is the only handle a proposal may carry, so this is the one
 * lookup both the helper (to validate) and the card's tap (to open the editor)
 * use. A missing or ambiguous name is an answer, not a guess: opening the editor
 * for the wrong workspace would write instructions somewhere the user did not
 * point at.
 */
internal fun resolveInstructionWorkspace(
	workspaces: List<WorkspaceEntity>,
	name: String,
): WorkspaceLookup {
	val wanted = name.trim()
	if (wanted.isEmpty()) return WorkspaceLookup.Missing(wanted)
	val matches = workspaces.filter { it.isPermanent && it.name.trim() == wanted }
	return when (matches.size) {
		0 -> WorkspaceLookup.Missing(wanted)
		1 -> WorkspaceLookup.Ok(matches.single())
		else -> WorkspaceLookup.Ambiguous(wanted)
	}
}

/** How a workspace kind is named to the model; display-only, never a path. */
internal fun workspaceKindLabel(kind: String): String = when (kind) {
	com.inhuman.core.data.db.WorkspaceKinds.WORKSHOP -> "workshop"
	else -> "ordinary"
}

/** The card's preview text, or "" when the card has no description. */
private fun cardDescription(cardJson: String): String =
	RoleplayCodec.decodeCard(cardJson)?.description?.takeIf { it.isNotBlank() }.orEmpty()

/** A string value, or null for a missing/null/non-string value. */
private fun JsonObject.stringOrNull(key: String): String? {
	val primitive = this[key] as? JsonPrimitive ?: return null
	if (primitive is JsonNull || !primitive.isString) return null
	return primitive.content
}

/** A boolean value, or null for a missing/null/non-boolean value. */
private fun JsonObject.boolOrNull(key: String): Boolean? {
	val element = this[key] ?: return null
	if (element is JsonNull) return null
	return (element as? JsonPrimitive)?.booleanOrNull
}
