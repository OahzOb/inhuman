package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.engine.roleplay.RoleplayLimits
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Prepare a role-play scene proposal for the user to start.
 *
 * ## What this tool does not do
 *
 * It creates nothing. A call validates a character card (or an existing
 * assistant's card) and an optional scene, then returns the [SceneProposal] as
 * JSON; the chat renders that as a card, and the user starts the scene by
 * tapping **Create**. This is deliberate (`docs/proposals/rp-agent-studio.md`
 * §3.3): the user was going to tap once either way, and a proposal that is
 * never accepted leaves no conversation, no workspace and no row behind.
 * Creating silently in the tool would also mean an app state change nobody
 * confirmed, plus a rollback story for a turn that was only asked a question.
 *
 * ## Why it is a host-appended helper, not a catalogue entry
 *
 * It has no capability: it reads the assistant rows the host hands it, writes
 * nothing, reaches no network and executes nothing. It is attached by
 * `ChatViewModel.buildTools` only in an RP workshop conversation, the same way
 * `recall` and `rp_format` are, so it never appears in the assistant editor,
 * the conversation tool sheet, the approval table or the capability gap report.
 *
 * ## Why assistant names resolve here and not in the model
 *
 * `character` is a name, not a card the model retyped: copying an existing
 * card through the model's output would be a lossy re-encode of the very card
 * the user saved. The lookup is an exact trimmed match, and an ambiguous name
 * is an error rather than a guess -- picking one of two assistants named "Aria"
 * would silently start a story with the wrong character. A name that does not
 * exist is never answered with the list of names that do: that would turn the
 * resolver into a directory of the user's assistants, which the model had no
 * other way to read.
 */
internal class RoleplaySceneProposalTool(
	private val loadAgents: suspend () -> List<AgentEntity>,
) : Tool {

	override val name: String = NAME

	override val description: String = """
		Prepare a role-play scene the user can start. This does not create
		anything: it returns a proposal that the chat shows as a card, and the
		user starts the scene by tapping Create there. Never say or imply that
		the scene was created, and make at most one proposal per request.
		Pass exactly one character source: `character`, the name of an existing
		assistant whose card and prompt should be used, or `card`, a character
		card you write now with the same fields as rp_format. Use `character`
		when the user wants a character that already exists; write `card` only
		when they asked for a new one. Optionally add `scene` and a `title` (the
		title defaults to the character name). The card and scene are checked
		against the app's role-play limits; a rejection names the field to fix.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"character" to mapOf(
				"type" to "string",
				"description" to "Name of an existing assistant whose character card " +
					"to use. Alternative to `card`; pass exactly one of the two.",
			),
			"card" to mapOf(
				"type" to "object",
				"description" to "A character card written now, with the same fields " +
					"as rp_format. Alternative to `character`; pass exactly one of the two.",
				"properties" to cardProperties(),
			),
			"scene" to mapOf(
				"type" to "object",
				"description" to "Optional scene fields for this story. The same shape " +
					"as the scene part of rp_format.",
				"properties" to sceneProperties(),
			),
			"title" to mapOf(
				"type" to "string",
				"description" to "Optional scene title. Defaults to the character name.",
			),
		),
		"required" to emptyList<String>(),
		"additionalProperties" to false,
	)

	/** Reads and validates; replaying it changes nothing. */
	override val mutates: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult = try {
		prepare(arguments)
	} catch (e: CancellationException) {
		throw e
	} catch (e: IllegalArgumentException) {
		// Wrong JSON types and out-of-shape arguments are ordinary model
		// mistakes; the tool contract is that they come back as text the model
		// can read, not as an exception that ends the turn.
		ToolResult.error("$NAME: ${e.message ?: "invalid arguments"}")
	}

	private suspend fun prepare(arguments: JsonObject): ToolResult {
		val character = arguments.string("character")
		val card = arguments.objectValue("card")
		if ((character == null) == (card == null)) {
			return ToolResult.error(
				"$NAME: pass exactly one of `character` (an existing assistant's " +
					"name) or `card` (a character card object); nothing was prepared.",
			)
		}
		val scene = arguments.objectValue("scene")
		val title = arguments.string("title")?.trim()?.takeIf { it.isNotEmpty() }

		val outcome = if (card != null) {
			SceneProposals.fromInlineCard(card, scene, title)
		} else {
			proposeFromAssistant(character.orEmpty(), scene, title)
		}
		return when (outcome) {
			is ProposalResult.Rejected -> ToolResult.error(outcome.message)
			is ProposalResult.Ok -> ToolResult.ok(
				content = outcome.proposal.toJson(),
				// The transcript keeps one small line: the JSON is already in
				// the tool result, and a model re-reading a whole card on every
				// later turn is exactly the cost persistedContent exists to
				// avoid. The wording is the reminder that nothing exists yet.
				persistedContent = "Scene proposal ready for ${outcome.proposal.characterName}; " +
					"waiting for the user to tap Create — do not say it was created.",
			)
		}
	}

	/**
	 * Resolve the `character` argument and build the proposal from that row.
	 *
	 * The list is loaded here rather than captured at construction so an
	 * assistant edited between turns is the one the proposal freezes.
	 */
	private suspend fun proposeFromAssistant(
		name: String,
		scene: JsonObject?,
		title: String?,
	): ProposalResult {
		val wanted = name.trim()
		if (wanted.isEmpty()) {
			return ProposalResult.Rejected(
				"$NAME: `character` must be the name of an existing assistant",
			)
		}
		val agents = try {
			loadAgents()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			return ProposalResult.Rejected(
				"$NAME: the assistant list could not be read; nothing was prepared.",
			)
		}
		val matches = agents.filter { it.name.trim() == wanted }
		return when (matches.size) {
			0 -> ProposalResult.Rejected(
				"$NAME: no assistant is named \"$wanted\". Check the name, or pass " +
					"`card` to write the character here.",
			)
			1 -> SceneProposals.fromAssistant(matches.single(), scene, title)
			else -> ProposalResult.Rejected(
				"$NAME: more than one assistant is named \"$wanted\"; rename one so " +
					"the name is unique, or pass `card`.",
			)
		}
	}

	/**
	 * A string argument, or null when it is absent or JSON null.
	 *
	 * A present value of another JSON type is refused rather than coerced: the
	 * schema declares a string, and a number or object where a name belongs is
	 * a misread call, not a name to stringify.
	 */
	private fun JsonObject.string(name: String): String? {
		val element = this[name] ?: return null
		if (element is JsonNull) return null
		val primitive = element as? JsonPrimitive
		if (primitive == null || !primitive.isString) {
			throw IllegalArgumentException("`$name` must be a string")
		}
		return primitive.content
	}

	/** An object argument, or null when it is absent or JSON null. */
	private fun JsonObject.objectValue(name: String): JsonObject? {
		val element = this[name] ?: return null
		if (element is JsonNull) return null
		return element as? JsonObject
			?: throw IllegalArgumentException("`$name` must be a JSON object")
	}

	/**
	 * The `card` argument's schema.
	 *
	 * These are the `rp_format` card fields, and the numeric ranges come from
	 * [RoleplayLimits] rather than being restated, so the schema the model reads
	 * cannot drift from the limits the validator enforces.
	 */
	private fun cardProperties(): Map<String, Any?> = mapOf(
		"name" to mapOf(
			"type" to "string",
			"description" to "The character's display name. Optional if `title` is passed.",
		),
		"description" to mapOf(
			"type" to "string",
			"description" to "Identity, appearance and background.",
		),
		"personality" to mapOf(
			"type" to "string",
			"description" to "Character, motivation and manner of speaking.",
		),
		"scenario" to mapOf(
			"type" to "string",
			"description" to "The character's default situation.",
		),
		"greeting" to mapOf(
			"type" to "string",
			"description" to "The first line the character says when the scene opens.",
		),
		"exampleDialogue" to mapOf(
			"type" to "string",
			"description" to "A short verbatim example of the character's voice.",
		),
		"boundaries" to mapOf(
			"type" to "string",
			"description" to "What the character must not do, and protections for the player.",
		),
		"authorNote" to mapOf(
			"type" to "string",
			"description" to "The user's note on how to play this character.",
		),
		"temperature" to mapOf(
			"type" to "number",
			"description" to "Optional sampling temperature, 0.0 - ${RoleplayLimits.TEMPERATURE_MAX}.",
		),
		"topP" to mapOf(
			"type" to "number",
			"description" to "Optional nucleus sampling probability, 0.0 - ${RoleplayLimits.TOP_P_MAX}.",
		),
		"maxTokens" to mapOf(
			"type" to "integer",
			"description" to "Optional reply-length ceiling, " +
				"${RoleplayLimits.MAX_TOKENS_MIN} - ${RoleplayLimits.MAX_TOKENS_MAX} tokens.",
		),
	)

	/** The `scene` argument's schema: the same fields as `rp_format`'s scene. */
	private fun sceneProperties(): Map<String, Any?> = mapOf(
		"world" to mapOf(
			"type" to "string",
			"description" to "The world and its rules for this story.",
		),
		"premise" to mapOf(
			"type" to "string",
			"description" to "The situation the story opens in.",
		),
		"userPersona" to mapOf(
			"type" to "string",
			"description" to "Who the player is in this story, in their own words.",
		),
		"format" to mapOf(
			"type" to "string",
			"description" to "Narration person, length and style.",
		),
		"notes" to mapOf(
			"type" to "string",
			"description" to "The user's notes for this story.",
		),
	)

	companion object {
		/** The name the model calls; the chat matches tool results by this name. */
		const val NAME = "rp_create_scene"
	}
}
