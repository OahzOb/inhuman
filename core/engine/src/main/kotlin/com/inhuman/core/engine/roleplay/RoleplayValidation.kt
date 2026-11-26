package com.inhuman.core.engine.roleplay

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Check a role-play file against the same codec the app uses.
 *
 * The point of this being in the engine, next to the codec and the limits, is
 * that a model writing a card gets the same answer the app would give: one
 * parser, one set of limits, two callers. A validator that reimplemented the
 * schema would be a second source of truth, and the message the model acted on
 * could disagree with what a later scene creation refused.
 *
 * ## It reports structure, not prose
 *
 * The result names fields, lengths and JSON types; it never echoes field
 * values. The agent is allowed to run this exactly where it already has
 * workspace file access, but a validator that quoted the file would become a
 * second read path that the file-tool permission rules do not see.
 */
object RoleplayValidator {

	enum class Kind { Card, Scene }

	/**
	 * One finding.
	 *
	 * [warning] entries are informational (an unknown field is ignored, not
	 * fatal); every non-warning entry blocks the file from being used as
	 * role-play material.
	 */
	data class Issue(
		val field: String,
		val message: String,
		val warning: Boolean = false,
	) {
		/** Stable one-line form for the tool output. */
		fun render(): String = "${if (warning) "warning" else "error"}: " +
			(if (field.isEmpty()) "" else "$field: ") + message
	}

	/**
	 * What one file looked like.
	 *
	 * [summary] is safe to persist in the transcript: counts and sizes, no
	 * content. The full [issues] text is for the current turn only.
	 */
	data class Result(
		val kind: Kind,
		val issues: List<Issue>,
		val summary: String,
	) {
		val ok: Boolean get() = issues.none { !it.warning }

		fun render(path: String): String = buildString {
			appendLine(if (ok) "VALID $path" else "PROBLEMS $path")
			appendLine(summary)
			for (issue in issues) appendLine("- ${issue.render()}")
		}.trimEnd()
	}

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = false
		explicitNulls = false
	}

	private val cardFieldNames: Set<String> =
		RoleplayCard.serializer().descriptor.elementNames.toSet()

	private val sceneFieldNames: Set<String> =
		RoleplayScene.serializer().descriptor.elementNames.toSet()

	fun validateCard(raw: String): Result {
		val text = raw.trim()
		if (text.isEmpty()) return empty(Kind.Card)
		val issues = mutableListOf<Issue>()
		val element = parse(text, issues) ?: return Result(
			Kind.Card,
			issues,
			"roleplay card: not parsable",
		)
		unknownFields(element, cardFieldNames, issues)
		val card = runCatching { json.decodeFromString(RoleplayCard.serializer(), text) }
			.getOrElse { failure ->
				issues += Issue("", shapeProblem(failure))
				null
			}
		if (card == null) {
			return Result(Kind.Card, issues, "roleplay card: JSON shape does not match the format")
		}
		if (card.isBlank) {
			issues += Issue("", "no field is set; this file would be read as 'not a roleplay card'")
		}
		RoleplayLimits.cardErrors(card).forEach { error ->
			issues += Issue(fieldName(error.field), lengthProblem(error))
		}
		samplingIssues(card, issues)
		return Result(Kind.Card, issues, cardSummary(card))
	}

	fun validateScene(raw: String): Result {
		val text = raw.trim()
		if (text.isEmpty()) return empty(Kind.Scene)
		val issues = mutableListOf<Issue>()
		val element = parse(text, issues) ?: return Result(
			Kind.Scene,
			issues,
			"roleplay scene: not parsable",
		)
		unknownFields(element, sceneFieldNames, issues)
		val scene = runCatching { json.decodeFromString(RoleplayScene.serializer(), text) }
			.getOrElse { failure ->
				issues += Issue("", shapeProblem(failure))
				null
			}
		if (scene == null) {
			return Result(Kind.Scene, issues, "roleplay scene: JSON shape does not match the format")
		}
		if (scene.isBlank) {
			issues += Issue(
				"",
				"no field is set; this is equivalent to starting without a scene",
				warning = true,
			)
		}
		RoleplayLimits.sceneErrors(scene).forEach { error ->
			issues += Issue(fieldName(error.field), lengthProblem(error))
		}
		return Result(Kind.Scene, issues, sceneSummary(scene))
	}

	private fun empty(kind: Kind): Result = Result(
		kind,
		listOf(Issue("", "the file is empty")),
		"${kindName(kind)}: empty",
	)

	/**
	 * Parse the text into an object.
	 *
	 * The exception is reduced to a position: kotlinx's message can quote the
	 * input, and the validator promises structural findings only.
	 */
	private fun parse(text: String, issues: MutableList<Issue>): JsonObject? {
		val element = runCatching { json.parseToJsonElement(text) }.getOrElse { failure ->
			issues += Issue("", "not valid JSON${positionOf(failure)?.let { " (at offset $it)" }.orEmpty()}")
			return null
		}
		if (element !is JsonObject) {
			issues += Issue("", "the top level must be a JSON object")
			return null
		}
		return element
	}

	private fun unknownFields(
		element: JsonObject,
		known: Set<String>,
		issues: MutableList<Issue>,
	) {
		for (key in element.keys) {
			if (key !in known) issues += Issue(key, "unknown field is ignored", warning = true)
		}
	}

	/**
	 * A one-line description of a type/shape failure.
	 *
	 * Quoted spans are masked: the message is shown to the model as a validation
	 * result, and echoing the file's values would make this a read tool with a
	 * different name.
	 */
	private fun shapeProblem(failure: Throwable): String {
		val raw = (failure as? SerializationException)?.message
			?: failure.message
			?: "the JSON shape does not match the format"
		val masked = QUOTED.replace(raw.replace('\n', ' '), "\"…\"")
		val trimmed = masked.take(180).trim()
		return "value does not match the field's JSON type" +
			if (trimmed.isEmpty()) "" else " ($trimmed)"
	}

	private fun positionOf(failure: Throwable): Int? =
		OFFSET.find(failure.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

	private fun lengthProblem(error: RoleplayLimitError): String =
		"${error.actual} characters, over the ${error.max}-character limit"

	private fun samplingIssues(card: RoleplayCard, issues: MutableList<Issue>) {
		card.temperature?.let { value ->
			if (value < 0.0 || value > RoleplayLimits.TEMPERATURE_MAX) {
				issues += Issue(
					"temperature",
					"$value is outside 0.0 – ${RoleplayLimits.TEMPERATURE_MAX}",
				)
			}
		}
		card.topP?.let { value ->
			if (value < 0.0 || value > RoleplayLimits.TOP_P_MAX) {
				issues += Issue("topP", "$value is outside 0.0 – ${RoleplayLimits.TOP_P_MAX}")
			}
		}
		card.maxTokens?.let { value ->
			if (value < RoleplayLimits.MAX_TOKENS_MIN || value > RoleplayLimits.MAX_TOKENS_MAX) {
				issues += Issue(
					"maxTokens",
					"$value is outside ${RoleplayLimits.MAX_TOKENS_MIN} – " +
						"${RoleplayLimits.MAX_TOKENS_MAX}",
				)
			}
		}
	}

	private fun cardSummary(card: RoleplayCard): String = buildString {
		append("roleplay card: ")
		append(RoleplayLimits.cardChars(card))
		append(" / ")
		append(RoleplayLimits.MAX_CARD_CHARS)
		append(" characters; ")
		val set = setFields(
			RoleplaySpec.cardFields,
			listOf(
				card.description,
				card.personality,
				card.scenario,
				card.greeting,
				card.exampleDialogue,
				card.boundaries,
				card.authorNote,
			),
		)
		append(if (set.isEmpty()) "no fields set" else "fields set: ${set.size} (${set.joinToString(", ")})")
		append("; greeting: ")
		append(if (card.greeting.isNotBlank()) "yes" else "no")
	}

	private fun sceneSummary(scene: RoleplayScene): String = buildString {
		append("roleplay scene: ")
		append(RoleplayLimits.sceneChars(scene))
		append(" / ")
		append(RoleplayLimits.MAX_SCENE_CHARS)
		append(" characters; ")
		val set = setFields(
			RoleplaySpec.sceneFields,
			listOf(scene.world, scene.premise, scene.userPersona, scene.format, scene.notes),
		)
		append(if (set.isEmpty()) "no fields set" else "fields set: ${set.size} (${set.joinToString(", ")})")
	}

	/**
	 * The JSON names of the text fields that are non-empty, in format order.
	 *
	 * The names come from [RoleplaySpec], so a summary cannot call a field one
	 * thing while `rp_format` calls it another; numeric sampling fields are
	 * skipped because they have no "set" state in a text-length sense.
	 */
	private fun setFields(fields: List<RoleplaySpec.Field>, values: List<String>): List<String> =
		fields.filter { it.limit != null }.mapIndexedNotNull { index, field ->
			if (values.getOrNull(index)?.isNotBlank() == true) field.jsonName else null
		}

	/** The JSON name of a limit error's field, for the report. */
	private fun fieldName(field: RoleplayField): String = when (field) {
		RoleplayField.Description -> "description"
		RoleplayField.Personality -> "personality"
		RoleplayField.Scenario -> "scenario"
		RoleplayField.Greeting -> "greeting"
		RoleplayField.ExampleDialogue -> "exampleDialogue"
		RoleplayField.Boundaries -> "boundaries"
		RoleplayField.AuthorNote -> "authorNote"
		RoleplayField.World -> "world"
		RoleplayField.Premise -> "premise"
		RoleplayField.UserPersona -> "userPersona"
		RoleplayField.Format -> "format"
		RoleplayField.Notes -> "notes"
		// The totals are reported under a name that cannot be mistaken for a
		// field the model could shorten in isolation.
		RoleplayField.CardTotal -> "total card text"
		RoleplayField.SceneTotal -> "total scene text"
	}

	private fun kindName(kind: Kind): String = when (kind) {
		Kind.Card -> "roleplay card"
		Kind.Scene -> "roleplay scene"
	}

	private val QUOTED = Regex("\"[^\"]*\"")
	private val OFFSET = Regex("offset (\\d+)")
}
