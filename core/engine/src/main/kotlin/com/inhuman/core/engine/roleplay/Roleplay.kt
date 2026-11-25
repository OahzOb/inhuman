package com.inhuman.core.engine.roleplay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One role-play character, stored as JSON on the assistant that plays it.
 *
 * The card is **content, not capability**: every field is text that ends up in
 * the system message. Nothing here can add a tool, change an approval, grant
 * SSH or widen the workspace fence, and the renderer says so in the prompt. A
 * card imported from someone else is third-party text with the same trust
 * level as a pasted system prompt -- it can describe a character, not grant
 * anything.
 *
 * The greeting is part of the card rather than the conversation because it is
 * reusable: every scene started with this character opens with the same line.
 * The copy that a scene opens with is a message row, not a pointer -- editing
 * the card later must not rewrite what the character already said.
 *
 * All coordinates are optional: a card with only a description is legal, and
 * so is one with only a greeting.
 */
@Serializable
data class RoleplayCard(
	/** Identity, appearance, background. */
	val description: String = "",
	/** Character, motivation, manner of speaking. */
	val personality: String = "",
	/** The character's default situation; a scene may replace it. */
	val scenario: String = "",
	/** First assistant line when a scene starts. */
	val greeting: String = "",
	/**
	 * Example dialogue. The only reliable way to pin a speaking style; kept
	 * verbatim and labelled as a voice reference, not as instructions.
	 */
	val exampleDialogue: String = "",
	/** What the character must not do, and what the player must not be made to do. */
	val boundaries: String = "",
	/** The user's own note on how to play this character. */
	val authorNote: String = "",
	/** Sampling for this character; null means the provider/conversation default. */
	val temperature: Double? = null,
	val topP: Double? = null,
	val maxTokens: Int? = null,
) {
	/** True when nothing at all is set, i.e. the card is not worth storing. */
	val isBlank: Boolean
		get() = description.isBlank() && personality.isBlank() && scenario.isBlank() &&
			greeting.isBlank() && exampleDialogue.isBlank() && boundaries.isBlank() &&
			authorNote.isBlank() && temperature == null && topP == null && maxTokens == null
}

/**
 * One scene, stored as JSON on the conversation.
 *
 * A scene is additional setting for one story: the world, the premise, who the
 * user is playing, and how the narration should read. It belongs to the
 * conversation, not the character, because the same character can be dropped
 * into several unrelated stories.
 */
@Serializable
data class RoleplayScene(
	val world: String = "",
	val premise: String = "",
	/** Who the user plays, in their own words. */
	val userPersona: String = "",
	/** Narration person, length, style. */
	val format: String = "",
	/** The user's notes for this story. */
	val notes: String = "",
) {
	val isBlank: Boolean
		get() = world.isBlank() && premise.isBlank() && userPersona.isBlank() &&
			format.isBlank() && notes.isBlank()
}

/**
 * The stored representation of a card and a scene.
 *
 * An empty or malformed value decodes to null, and null means "not a role-play
 * character/scene": the feature turns off rather than the conversation failing
 * to open. That direction matters for an upgrade -- a database row written by a
 * newer release, or a hand-edited value, must not make an assistant unusable.
 * The editor and the importer validate before writing, so a stored value it
 * cannot read is already a bug rather than a normal state.
 *
 * Unknown keys are ignored so a card can carry fields this release does not
 * know yet without losing the ones it does.
 */
object RoleplayCodec {

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = false
		explicitNulls = false
	}

	fun decodeCard(raw: String?): RoleplayCard? {
		val text = raw?.trim().orEmpty()
		if (text.isEmpty()) return null
		val card = runCatching { json.decodeFromString(RoleplayCard.serializer(), text) }
			.getOrNull() ?: return null
		return card.takeUnless { it.isBlank }
	}

	/** Encodes a card, or `""` when there is nothing to store (turns the feature off). */
	fun encodeCard(card: RoleplayCard): String =
		if (card.isBlank) "" else json.encodeToString(RoleplayCard.serializer(), card)

	fun decodeScene(raw: String?): RoleplayScene? {
		val text = raw?.trim().orEmpty()
		if (text.isEmpty()) return null
		val scene = runCatching { json.decodeFromString(RoleplayScene.serializer(), text) }
			.getOrNull() ?: return null
		return scene.takeUnless { it.isBlank }
	}

	fun encodeScene(scene: RoleplayScene): String =
		if (scene.isBlank) "" else json.encodeToString(RoleplayScene.serializer(), scene)
}

/** A card or scene field, so the UI can name the one that is too long. */
enum class RoleplayField {
	Description,
	Personality,
	Scenario,
	Greeting,
	ExampleDialogue,
	Boundaries,
	AuthorNote,
	World,
	Premise,
	UserPersona,
	Format,
	Notes,
	CardTotal,
	SceneTotal,
}

/**
 * A length limit that was exceeded.
 *
 * `actual` and `max` are characters, not bytes: the editor's counter and the
 * text field both count characters, and a byte count would tell the user a
 * string is too long when the field is not. The app shows the message; this
 * module stays free of Android string resources.
 */
data class RoleplayLimitError(
	val field: RoleplayField,
	val actual: Int,
	val max: Int,
)

/**
 * Size limits for a card and a scene.
 *
 * They exist because a card is prepended to **every** request in the scene and
 * fills the same window as the conversation. A 60 KiB card would push the
 * recent turns out of the projection no matter how well the brief is
 * maintained, and the failure would look like "the character forgot the last
 * few messages". The limits are enforced when the card/scene is saved or
 * imported; the renderer never silently truncates, because a card that lost
 * half its description would change the character without saying so.
 *
 * The per-field limits sum to [MAX_CARD_CHARS], so passing them all is the same
 * as passing the total. The total is checked anyway: it is the one number a
 * future field must continue to respect.
 */
object RoleplayLimits {

	const val MAX_CARD_CHARS = 16 * 1024
	/**
	 * The scene limit is below the sum of its field limits on purpose: the total
	 * is the real ceiling, so a scene cannot reach "every field nearly full"
	 * and fill the window. The card's per-field limits happen to sum to its
	 * total today; the total is still checked there for the next field that is
	 * added.
	 */
	const val MAX_SCENE_CHARS = 10 * 1024

	const val DESCRIPTION_MAX = 4 * 1024
	const val PERSONALITY_MAX = 2 * 1024
	const val SCENARIO_MAX = 1 * 1024
	const val GREETING_MAX = 2 * 1024
	const val EXAMPLE_DIALOGUE_MAX = 4 * 1024
	const val BOUNDARIES_MAX = 1 * 1024
	const val AUTHOR_NOTE_MAX = 2 * 1024

	const val WORLD_MAX = 4 * 1024
	const val PREMISE_MAX = 2 * 1024
	const val USER_PERSONA_MAX = 2 * 1024
	const val FORMAT_MAX = 1 * 1024
	const val NOTES_MAX = 2 * 1024

	/** Sampling ranges the editor offers and the importer clamps to. */
	const val TEMPERATURE_MAX = 2.0
	const val TOP_P_MAX = 1.0
	const val MAX_TOKENS_MIN = 256
	const val MAX_TOKENS_MAX = 16_384

	fun cardChars(card: RoleplayCard): Int =
		card.description.length + card.personality.length + card.scenario.length +
			card.greeting.length + card.exampleDialogue.length + card.boundaries.length +
			card.authorNote.length

	fun sceneChars(scene: RoleplayScene): Int =
		scene.world.length + scene.premise.length + scene.userPersona.length +
			scene.format.length + scene.notes.length

	/** Every limit the card breaks, in field order, total last. */
	fun cardErrors(card: RoleplayCard): List<RoleplayLimitError> {
		val errors = mutableListOf<RoleplayLimitError>()
		check(errors, RoleplayField.Description, card.description, DESCRIPTION_MAX)
		check(errors, RoleplayField.Personality, card.personality, PERSONALITY_MAX)
		check(errors, RoleplayField.Scenario, card.scenario, SCENARIO_MAX)
		check(errors, RoleplayField.Greeting, card.greeting, GREETING_MAX)
		check(errors, RoleplayField.ExampleDialogue, card.exampleDialogue, EXAMPLE_DIALOGUE_MAX)
		check(errors, RoleplayField.Boundaries, card.boundaries, BOUNDARIES_MAX)
		check(errors, RoleplayField.AuthorNote, card.authorNote, AUTHOR_NOTE_MAX)
		check(errors, RoleplayField.CardTotal, cardChars(card), MAX_CARD_CHARS)
		return errors
	}

	/** Every limit the scene breaks, in field order, total last. */
	fun sceneErrors(scene: RoleplayScene): List<RoleplayLimitError> {
		val errors = mutableListOf<RoleplayLimitError>()
		check(errors, RoleplayField.World, scene.world, WORLD_MAX)
		check(errors, RoleplayField.Premise, scene.premise, PREMISE_MAX)
		check(errors, RoleplayField.UserPersona, scene.userPersona, USER_PERSONA_MAX)
		check(errors, RoleplayField.Format, scene.format, FORMAT_MAX)
		check(errors, RoleplayField.Notes, scene.notes, NOTES_MAX)
		check(errors, RoleplayField.SceneTotal, sceneChars(scene), MAX_SCENE_CHARS)
		return errors
	}

	private fun check(
		errors: MutableList<RoleplayLimitError>,
		field: RoleplayField,
		value: String,
		max: Int,
	) {
		if (value.length > max) errors += RoleplayLimitError(field, value.length, max)
	}

	private fun check(
		errors: MutableList<RoleplayLimitError>,
		field: RoleplayField,
		value: Int,
		max: Int,
	) {
		if (value > max) errors += RoleplayLimitError(field, value, max)
	}
}
