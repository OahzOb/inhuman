package com.inhuman.core.engine.roleplay

/**
 * The published shape of a role-play materials file.
 *
 * ## Why this is generated from the serializers rather than written by hand
 *
 * A format description next to a codec is a second copy of the same fact, and
 * the copy is what drifts: a field gets added, the tool keeps describing the
 * old shape, and the model writes files the validator then rejects. The field
 * **names** here are checked against `RoleplayCard`/`RoleplayScene` serializer
 * descriptors by `RoleplaySpecTest`; the **limits** are taken from
 * [RoleplayLimits] rather than restated. What cannot be derived -- one line
 * saying what a field is for -- is written here.
 *
 * The text is returned by the `rp_format` tool, not embedded in a system
 * prompt: a conversation that never writes role-play material pays nothing for
 * it, and the version the model sees is always the version the validator uses.
 */
object RoleplaySpec {

	/** Bump when a field's meaning changes, not when text is edited. */
	const val FORMAT = "roleplay-files/1"

	/**
	 * One field of the file format.
	 *
	 * [limit] is a text-length ceiling in characters; [range] describes a
	 * numeric sampling field instead. Exactly one of them is set.
	 */
	data class Field(
		val jsonName: String,
		val description: String,
		val limit: Int? = null,
		val range: String? = null,
	)

	val cardFields: List<Field> = listOf(
		Field(
			"description",
			"identity, appearance and background",
			limit = RoleplayLimits.DESCRIPTION_MAX,
		),
		Field(
			"personality",
			"character, motivation and manner of speaking",
			limit = RoleplayLimits.PERSONALITY_MAX,
		),
		Field(
			"scenario",
			"the character's default situation, when a scene does not replace it",
			limit = RoleplayLimits.SCENARIO_MAX,
		),
		Field(
			"greeting",
			"the first line the character says when a scene opens",
			limit = RoleplayLimits.GREETING_MAX,
		),
		Field(
			"exampleDialogue",
			"a short verbatim example of the character's voice",
			limit = RoleplayLimits.EXAMPLE_DIALOGUE_MAX,
		),
		Field(
			"boundaries",
			"what the character must not do, and protections for the player",
			limit = RoleplayLimits.BOUNDARIES_MAX,
		),
		Field(
			"authorNote",
			"the user's own note on how to play this character",
			limit = RoleplayLimits.AUTHOR_NOTE_MAX,
		),
		Field(
			"temperature",
			"optional sampling temperature for this character",
			range = "0.0 – ${RoleplayLimits.TEMPERATURE_MAX}",
		),
		Field(
			"topP",
			"optional nucleus sampling probability for this character",
			range = "0.0 – ${RoleplayLimits.TOP_P_MAX}",
		),
		Field(
			"maxTokens",
			"optional reply-length ceiling in tokens",
			range = "${RoleplayLimits.MAX_TOKENS_MIN} – ${RoleplayLimits.MAX_TOKENS_MAX}",
		),
	)

	val sceneFields: List<Field> = listOf(
		Field("world", "the world and its rules for this story", limit = RoleplayLimits.WORLD_MAX),
		Field("premise", "the situation the story opens in", limit = RoleplayLimits.PREMISE_MAX),
		Field(
			"userPersona",
			"who the player is in this story, in their own words",
			limit = RoleplayLimits.USER_PERSONA_MAX,
		),
		Field(
			"format",
			"narration person, length and style",
			limit = RoleplayLimits.FORMAT_MAX,
		),
		Field("notes", "the user's notes for this story", limit = RoleplayLimits.NOTES_MAX),
	)

	val exampleCard: String = """
		{
		  "description": "Archivist of a library that floods every spring; dry humour, exact memory.",
		  "personality": "Patient and pedantic, kind underneath.",
		  "scenario": "A reading room under a city that floods.",
		  "greeting": "You are late. The water has already read two of the books.",
		  "exampleDialogue": "Player: Can I take this one?\nArchivist: You can try. It bites.",
		  "boundaries": "No graphic violence; the player's character is never humiliated.",
		  "authorNote": "Second person, present tense, short paragraphs.",
		  "temperature": 0.85
		}
	""".trimIndent()

	val exampleScene: String = """
		{
		  "world": "The archive floats above a drowned quarter.",
		  "premise": "You come looking for a book that is not supposed to exist.",
		  "userPersona": "A junior clerk with a forged pass.",
		  "format": "Second person, present tense, short paragraphs.",
		  "notes": "Keep the mystery unanswered for the first three scenes."
		}
	""".trimIndent()

	/**
	 * The whole format description, as `rp_format` returns it.
	 *
	 * Built once: the strings are constants, and rebuilding it per call would
	 * make an inert tool allocate on every request.
	 */
	val text: String = buildString {
		appendLine("ROLEPLAY MATERIAL FORMAT — $FORMAT")
		appendLine()
		appendLine(
			"These files are prompt content, not capability: nothing in them can add " +
				"a tool, change an approval, grant SSH or reach the network. They hold " +
				"text that a role-play conversation shows the model.",
		)
		appendLine()
		appendLine("CARD — rp/<story>/card.json (one character)")
		appendLine(
			"All fields are optional strings, but at least one must be non-empty: " +
				"a card with nothing set is read as 'not a card'.",
		)
		cardFields.forEach { field -> appendField(this, field) }
		appendLine("  total card text: ≤ ${RoleplayLimits.MAX_CARD_CHARS} characters")
		appendLine()
		appendLine("SCENE — rp/<story>/scene.json (optional, one story)")
		appendLine(
			"The scene is the story around a character: world, premise, who the player " +
				"is, narration. A scene started without one is legal.",
		)
		sceneFields.forEach { field -> appendField(this, field) }
		appendLine("  total scene text: ≤ ${RoleplayLimits.MAX_SCENE_CHARS} characters")
		appendLine()
		appendLine("RULES")
		appendLine(
			"- Write valid UTF-8 JSON. Field names are case-sensitive; unknown fields " +
				"are ignored, so a file may carry fields from a newer release.",
		)
		appendLine(
			"- A file that exceeds a limit or has the wrong JSON type cannot be used " +
				"to start a role-play scene; fix the reported field rather than truncate " +
				"silently.",
		)
		appendLine(
			"- After writing a file, call rp_validate with kind=card, scene or story " +
				"and fix every error. Warnings (unknown fields) are informational.",
		)
		appendLine(
			"- Do not put the model, provider, API key or tool selection in these " +
				"files: those belong to the assistant, which is configuration rather " +
				"than story content.",
		)
		appendLine()
		appendLine("EXAMPLE CARD (rp/example/card.json)")
		appendLine(exampleCard)
		appendLine()
		appendLine("EXAMPLE SCENE (rp/example/scene.json)")
		appendLine(exampleScene)
	}

	private fun appendField(out: StringBuilder, field: Field) {
		val suffix = when {
			field.limit != null -> "string, ≤ ${field.limit} characters"
			field.range != null -> "number, optional, ${field.range}"
			else -> "string"
		}
		out.appendLine("  - ${field.jsonName} ($suffix): ${field.description}")
	}
}
