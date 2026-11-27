package com.inhuman.ui.chat

import com.inhuman.core.engine.roleplay.RoleplayLimits

/**
 * The JSON schemas the role-play-shaped tools share.
 *
 * `rp_create_scene` and the configuration workshop's `app_propose_assistant`
 * both accept a character card, and the assistant editor writes the same object
 * through [com.inhuman.core.engine.roleplay.RoleplayCodec]. Two copies of this
 * schema would be two answers to "what is a card"; the numeric ranges are read
 * from [RoleplayLimits] rather than restated so the schema the model sees cannot
 * drift from the limits the validator enforces.
 */
internal object RoleplayToolSchema {

	/** The `card` argument's schema. */
	fun cardProperties(): Map<String, Any?> = mapOf(
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
	fun sceneProperties(): Map<String, Any?> = mapOf(
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
}
