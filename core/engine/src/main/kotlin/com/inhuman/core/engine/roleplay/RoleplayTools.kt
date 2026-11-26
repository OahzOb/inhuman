package com.inhuman.core.engine.roleplay

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The two role-play authoring helpers: read the format, check a file.
 *
 * ## Why tools rather than a workspace document
 *
 * The format has to reach *whatever* agent the user happens to be talking to,
 * in *whatever* workspace, without a setup step and without a workspace
 * `AGENTS.md` that may be pinned too late to be seen. A tool is the app's
 * existing way of telling a model what it can do: it travels with the request,
 * it is tested, and the version the model reads is the version the validator
 * uses. The description also means the model learns the affordance from the
 * tool list rather than from a prompt paragraph repeated into every
 * conversation.
 *
 * ## Why they are not always attached
 *
 * `rp_validate` reads a workspace file. Attaching it to a conversation with no
 * file access would hand the model a read path the user never granted, so the
 * host attaches both helpers only where the conversation already has
 * workspace-file tools (see `ChatViewModel.buildTools`). A role-play scene with
 * no tools therefore keeps an empty tool set; its static persona is already in
 * the prompt and needs no authoring helpers.
 */
class RoleplayFormatTool : Tool {

	override val name = NAME

	override val description =
		"Return the role-play materials format: the character-card and scene file " +
			"fields, their text limits, the folder convention and a valid example. " +
			"Call this before writing role-play card/scene files, then check the " +
			"result with ${RoleplayValidateTool.NAME}."

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to emptyMap<String, Any?>(),
	)

	/** Reads a constant; replaying it changes nothing. */
	override val mutates = false

	override suspend fun execute(arguments: JsonObject): ToolResult = ToolResult.ok(
		content = RoleplaySpec.text,
		persistedContent = "Role-play format ${RoleplaySpec.FORMAT} returned (card and scene schema).",
	)

	companion object {
		const val NAME = "rp_format"
	}
}

/**
 * Validate one role-play card, scene, or a whole story folder.
 *
 * Paths are workspace-relative and resolved through [SessionWorkspace], the
 * same single path-to-`File` gate every file tool uses. The finding text names
 * fields, lengths and JSON types; it never echoes field values, so this cannot
 * become a read tool under another name.
 *
 * An invalid file is a **successful validation**, not a tool error: the model
 * gets the list and fixes it. Only a refused path or an unreadable file is an
 * error, because there is nothing to validate.
 */
class RoleplayValidateTool(workspace: SessionWorkspace) : WorkspaceTool(workspace), Tool {

	override val name = NAME

	override val description =
		"Validate a role-play card or scene file in the conversation workspace. " +
			"kind=card checks one card file, kind=scene one scene file, kind=story " +
			"checks a story folder (card.json required, scene.json optional). " +
			"Returns field-level problems and a size summary; it does not return " +
			"file contents. Call $FORMAT_NAME first if the format is not known."

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"path" to mapOf(
				"type" to "string",
				"description" to "Workspace-relative path: a JSON file for card/scene, " +
					"or the story folder for kind=story.",
			),
			"kind" to mapOf(
				"type" to "string",
				"enum" to listOf("card", "scene", "story"),
				"description" to "What to validate. Defaults to card.",
			),
		),
		"required" to listOf("path"),
	)

	/** Reads files; replaying it changes nothing. */
	override val mutates = false

	override suspend fun execute(arguments: JsonObject): ToolResult = try {
		when (val kind = arguments.optional("kind")?.lowercase() ?: "card") {
			"card" -> validateFile(arguments.require("path"), RoleplayValidator.Kind.Card)
			"scene" -> validateFile(arguments.require("path"), RoleplayValidator.Kind.Scene)
			"story" -> validateStory(arguments.require("path"))
			else -> ToolResult.error("rp_validate: kind must be card, scene or story (got \"$kind\")")
		}
	} catch (e: Refused) {
		ToolResult.error(e.message ?: "refused")
	} catch (e: IllegalArgumentException) {
		ToolResult.error(e.message ?: "invalid arguments")
	} catch (e: java.io.IOException) {
		ToolResult.error("filesystem error: ${e.message}")
	}

	private fun validateFile(path: String, kind: RoleplayValidator.Kind): ToolResult {
		val file = resolve(path).getOrThrow()
		val text = readText(file)
		val result = when (kind) {
			RoleplayValidator.Kind.Card -> RoleplayValidator.validateCard(text)
			RoleplayValidator.Kind.Scene -> RoleplayValidator.validateScene(text)
		}
		val shown = workspace.relativize(file).ifEmpty { path }
		return ToolResult.ok(
			content = result.render(shown),
			persistedContent = "rp_validate $shown: ${verdict(result)}",
		)
	}

	private fun validateStory(path: String): ToolResult {
		val dir = resolve(path).getOrThrow()
		val entries = listEntries(dir).first
		val byName = entries.associateBy { it.name.lowercase() }
		val cardFile = byName["card.json"]
		if (cardFile == null) {
			val shown = workspace.relativize(dir).ifEmpty { "." }
			return ToolResult.ok(
				content = "PROBLEMS $shown\n" +
					"missing card.json: a story folder needs one character card; " +
					"call $FORMAT_NAME for the format.",
				persistedContent = "rp_validate $shown: story missing card.json.",
			)
		}

		val shown = workspace.relativize(dir).ifEmpty { "." }
		val card = RoleplayValidator.validateCard(readText(cardFile))
		val sceneFile = byName["scene.json"]
		val scene = sceneFile?.let { RoleplayValidator.validateScene(readText(it)) }
		val ok = card.ok && (scene?.ok ?: true)
		val body = buildString {
			appendLine(if (ok) "VALID $shown (story)" else "PROBLEMS $shown (story)")
			appendLine()
			appendLine(card.render("card.json"))
			if (scene != null) {
				appendLine()
				append(scene.render("scene.json"))
			} else {
				appendLine()
				append("scene.json absent: allowed; the scene will start from the dialog and the card alone.")
			}
		}.trimEnd()
		val counts = listOfNotNull(card, scene).sumOf { result ->
			result.issues.count { !it.warning }
		}
		return ToolResult.ok(
			content = body,
			persistedContent = if (ok) {
				"rp_validate $shown: story OK (${if (scene != null) "card+scene" else "card"})."
			} else {
				"rp_validate $shown: story has $counts blocking problem(s)."
			},
		)
	}

	private fun verdict(result: RoleplayValidator.Result): String =
		if (result.ok) "valid" else "${result.issues.count { !it.warning }} blocking problem(s)"

	private fun JsonObject.require(field: String): String =
		this[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
			?: throw IllegalArgumentException("missing required string argument '$field'")

	private fun JsonObject.optional(field: String): String? =
		this[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

	companion object {
		const val NAME = "rp_validate"
		const val FORMAT_NAME = RoleplayFormatTool.NAME
	}
}
