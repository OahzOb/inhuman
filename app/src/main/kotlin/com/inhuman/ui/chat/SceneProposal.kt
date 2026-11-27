package com.inhuman.ui.chat

import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.engine.roleplay.RoleplayCodec
import com.inhuman.core.engine.roleplay.RoleplayScene
import com.inhuman.core.engine.roleplay.RoleplayValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One role-play scene the agent has prepared for the user to start.
 *
 * ## A proposal, not a conversation
 *
 * `rp_create_scene` returns this as its tool-result content. Nothing has been
 * created: no conversation row, no workspace, no request. The chat renders the
 * proposal as a card, and the user's tap on **Create** is the app action that
 * calls the ordinary scene-creation path. That split is why the tool can be
 * attached without a new capability: a prompt injection can make the model
 * *propose* a scene, never create one, and a proposal the user ignores leaves
 * no trace.
 *
 * ## Why the JSON is written by hand
 *
 * This document survives in the transcript, and the app parses it back after a
 * reload, so its key names are a wire contract rather than an implementation
 * detail. The serializer is built explicitly (like [ConversationBundle]) for two
 * reasons: the app module does not apply the kotlinx-serialization compiler
 * plugin, and a hand-built tree makes "unknown keys are ignored" a property of
 * the reader instead of a setting someone can flip. Extra keys are tolerated on
 * read so a newer writer cannot break an older reader.
 *
 * ## Why the two sources keep different card text
 *
 * An inline card has no owner beyond this proposal, so it is re-encoded through
 * [RoleplayCodec]: what is stored is exactly what the codec reads back. An
 * assistant's card already lives in `agents.rpJson`, and the scene snapshot
 * keeps that copy **verbatim**: re-encoding would drop fields this release does
 * not know, which is the one thing the snapshot's verbatim promise exists to
 * prevent.
 */
data class SceneProposal(
	/** Where the character came from. */
	val source: Source,
	/** The assistant whose card and prompt were used; null for [Source.Card]. */
	val agentId: String?,
	/** The assistant prompt to freeze into the scene snapshot; "" for inline cards. */
	val systemPrompt: String,
	/** The card JSON to freeze: an assistant's verbatim, or the canonical inline card. */
	val cardJson: String,
	/** Display name of the character, and the basis for the default scene title. */
	val characterName: String,
	/** One-line preview for the proposal card: collapsed whitespace, capped length. */
	val description: String,
	/** Normalised scene JSON; `"{}"` when there is no scene. */
	val sceneJson: String,
	/** Scene title. */
	val title: String,
) {
	/**
	 * Which side prepared the character, and how the frozen persona is built.
	 *
	 * The wire string is explicit rather than derived from the enum constant
	 * name: the value is stored in the transcript, so renaming a constant must
	 * not silently stop old proposals from parsing.
	 */
	enum class Source(val wire: String) {
		/** An existing assistant: its card is reused verbatim and its prompt frozen. */
		Character("character"),

		/** A card the model wrote in the call: no assistant, and no frozen prompt. */
		Card("card"),
	}

	/** The exact text the tool returns and the app later parses back. */
	fun toJson(): String = buildJsonObject {
		put("source", source.wire)
		put("agentId", agentId)
		put("systemPrompt", systemPrompt)
		put("cardJson", cardJson)
		put("characterName", characterName)
		put("description", description)
		put("sceneJson", sceneJson)
		put("title", title)
	}.toString()

	companion object {
		/**
		 * Decode a proposal, or null when [text] is not one.
		 *
		 * Total on purpose: the tool result comes back from the stored
		 * transcript, and a truncated or hand-edited row must render as "no
		 * proposal", not crash the chat. The source/agentId pairing is checked
		 * because a [Source.Character] proposal without an assistant id has no
		 * prompt to freeze and no card to own, which is not a state scene
		 * creation could use.
		 */
		fun fromJson(text: String): SceneProposal? {
			val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
				?: return null
			val source = when (root.text("source")?.trim()?.lowercase()) {
				Source.Character.wire -> Source.Character
				Source.Card.wire -> Source.Card
				else -> return null
			}
			val agentId = when {
				root["agentId"] == null || root["agentId"] is JsonNull -> null
				else -> root.text("agentId")?.takeIf { it.isNotBlank() } ?: return null
			}
			val systemPrompt = root.text("systemPrompt") ?: return null
			val cardJson = root.text("cardJson") ?: return null
			val characterName = root.text("characterName") ?: return null
			val description = root.text("description") ?: return null
			val sceneJson = root.text("sceneJson") ?: return null
			val title = root.text("title") ?: return null
			if (source == Source.Character && agentId == null) return null
			if (source == Source.Card && agentId != null) return null
			return SceneProposal(
				source = source,
				agentId = agentId,
				systemPrompt = systemPrompt,
				cardJson = cardJson,
				characterName = characterName,
				description = description,
				sceneJson = sceneJson,
				title = title,
			)
		}
	}
}

/**
 * What preparing a proposal produced.
 *
 * A value rather than an exception because a rejection is an ordinary outcome
 * of a model writing a card: the tool copies it into a `ToolResult.error` so
 * the model can fix the named field, and no exception crosses the tool
 * boundary. The message is field-level and never quotes field values -- the
 * same rule the `rp_validate` tool follows, so a proposal attempt cannot become
 * a read path for text the user did not hand over.
 */
internal sealed interface ProposalResult {
	data class Ok(val proposal: SceneProposal) : ProposalResult

	data class Rejected(val message: String) : ProposalResult
}

/**
 * Validates the two character sources and builds a [SceneProposal].
 *
 * ## Why validation reuses the engine
 *
 * A card is accepted only if [RoleplayValidator] and [RoleplayCodec] accept it,
 * exactly as the editor and `rp_validate` do. A second schema here would be a
 * second answer to "is this card usable", and the scene creation that follows
 * the user's tap would disagree with whichever one the model saw first.
 *
 * ## What is deliberately not done
 *
 * Nothing is written, executed, fetched or rendered. The result is data for the
 * chat's proposal card; the conversation is created later, by the app, on the
 * user's gesture.
 */
internal object SceneProposals {

	/**
	 * Build a proposal from a card object the model wrote in the call.
	 *
	 * The card is normalised through the codec, so the stored JSON is the
	 * canonical form rather than the model's formatting. A nameless card is
	 * legal in the file format, but a proposal needs a display name, so [title]
	 * is the fallback; with neither there is nothing to title the scene with.
	 */
	fun fromInlineCard(card: JsonObject, scene: JsonObject?, title: String?): ProposalResult {
		val raw = card.toString()
		cardProblem(raw, "the card")?.let { return ProposalResult.Rejected(it) }
		val decoded = RoleplayCodec.decodeCard(raw)
			?: return ProposalResult.Rejected("the card has no fields a scene could use")
		val requestedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
		val name = decoded.name.trim().ifEmpty { requestedTitle.orEmpty() }
		if (name.isEmpty()) {
			return ProposalResult.Rejected("the card needs a name, or pass title")
		}
		val sceneJson = when (val normalised = normaliseScene(scene)) {
			is SceneCheck.Ok -> normalised.json
			is SceneCheck.Rejected -> return ProposalResult.Rejected(normalised.message)
		}
		return ProposalResult.Ok(
			SceneProposal(
				source = SceneProposal.Source.Card,
				agentId = null,
				systemPrompt = "",
				cardJson = RoleplayCodec.encodeCard(decoded),
				characterName = name,
				description = preview(decoded.description),
				sceneJson = sceneJson,
				title = requestedTitle ?: name,
			),
		)
	}

	/**
	 * Build a proposal from an existing assistant's card and prompt.
	 *
	 * The card JSON and the prompt are copied **verbatim**, because the scene
	 * they create is a snapshot of that assistant: an edit made later must not
	 * rewrite a story already in progress, and a field this release does not
	 * understand must survive the copy.
	 */
	fun fromAssistant(agent: AgentEntity, scene: JsonObject?, title: String?): ProposalResult {
		val card = RoleplayCodec.decodeCard(agent.rpJson)
			?: return ProposalResult.Rejected("that assistant has no usable character card")
		cardProblem(agent.rpJson, "that assistant's card")
			?.let { return ProposalResult.Rejected(it) }
		val name = card.name.trim().ifEmpty { agent.name.trim() }
		if (name.isEmpty()) {
			return ProposalResult.Rejected(
				"that assistant and its card both have no name, so the scene has no title",
			)
		}
		val requestedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
		val sceneJson = when (val normalised = normaliseScene(scene)) {
			is SceneCheck.Ok -> normalised.json
			is SceneCheck.Rejected -> return ProposalResult.Rejected(normalised.message)
		}
		return ProposalResult.Ok(
			SceneProposal(
				source = SceneProposal.Source.Character,
				agentId = agent.id,
				systemPrompt = agent.systemPrompt,
				cardJson = agent.rpJson,
				characterName = name,
				description = preview(card.description),
				sceneJson = sceneJson,
				title = requestedTitle ?: name,
			),
		)
	}

	/**
	 * The blocking findings for a card, or null when it is usable.
	 *
	 * Warnings are ignored here for the same reason `rp_validate` allows them:
	 * an unknown field is a field a newer release wrote, not a reason to
	 * refuse a card the codec can read.
	 */
	private fun cardProblem(cardJson: String, subject: String): String? {
		val result = RoleplayValidator.validateCard(cardJson)
		return if (result.ok) null else problems(subject, result.issues)
	}

	/** The normalised scene JSON, or the field-level reason it cannot be used. */
	private fun normaliseScene(scene: JsonObject?): SceneCheck {
		if (scene == null) return SceneCheck.Ok(EMPTY_SCENE)
		val raw = scene.toString()
		val result = RoleplayValidator.validateScene(raw)
		if (!result.ok) return SceneCheck.Rejected(problems("the scene", result.issues))
		// The validator has already applied RoleplayLimits.sceneErrors; decoding
		// again only turns the text into the value that is re-encoded below. An
		// all-blank scene is a warning there and `"{}"` here, so the stored form
		// never depends on whether the model sent an empty object or nothing.
		val decoded = RoleplayCodec.decodeScene(raw) ?: RoleplayScene()
		return SceneCheck.Ok(RoleplayCodec.encodeScene(decoded).ifEmpty { EMPTY_SCENE })
	}

	/** Every blocking finding, one per line; no field value is ever quoted. */
	private fun problems(subject: String, issues: List<RoleplayValidator.Issue>): String =
		buildString {
			append(subject).append(" cannot be used as role-play material:")
			for (issue in issues) {
				if (issue.warning) continue
				append("\n- ").append(issue.render())
			}
		}
}

/** A one-line preview: the description collapsed and capped for the card UI. */
private fun preview(description: String): String =
	description.trim().replace(WHITESPACE, " ").take(DESCRIPTION_PREVIEW_MAX)

/** A string value, or null for a missing/null/non-string value. */
private fun JsonObject.text(key: String): String? {
	val primitive = this[key] as? JsonPrimitive ?: return null
	if (primitive is JsonNull || !primitive.isString) return null
	return primitive.content
}

/** The one normalised spelling of "no scene", shared by both sources. */
private const val EMPTY_SCENE = "{}"

/**
 * The proposal card's preview length.
 *
 * The card is one line in a list of turns, not a transcript view: 160
 * characters is enough to recognise the character and short enough that it
 * cannot turn the tool result into a second copy of the card.
 */
private const val DESCRIPTION_PREVIEW_MAX = 160

private val WHITESPACE = Regex("\\s+")

/** The outcome of normalising the optional scene object. */
private sealed interface SceneCheck {
	data class Ok(val json: String) : SceneCheck

	data class Rejected(val message: String) : SceneCheck
}
