package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.provider.qwen.QwenTokenPlanClient
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Typed decisions for the agent (Jev/System One shape).
 *
 * This is not a second chat turn: it returns probabilities and confidence for
 * the questions the caller defines, and never generates prose. The tool is
 * cheap and fast, but it is still a paid remote call.
 */
class QwenDecisionTool(
	private val client: QwenTokenPlanClient,
	private val model: String = QwenTokenPlanClient.DEFAULT_DECISION_MODEL,
) : Tool {

	override val name = "decide"

	override val mutates = false

	override val description = """
		Make a bounded typed decision about a state.

		Use this when you need a classification, a yes/no probability, or a score
		against explicit criteria — routing, urgency, moderation, or grading many
		items. Do not use it for open-ended reasoning or prose; answer those
		yourself.

		`state` is the text or structured object to judge. `questions` is an
		object keyed by question id. Each question has:
		- type: "noul" (yes/no probability), "choice" (one of criteria), or
		  "score" (ordered criteria)
		- instructions: what to decide
		- criteria: for choice, an object of option -> description; for score,
		  an array from low to high.

		The result contains probabilities and confidence. Use a threshold
		appropriate to the consequence before acting on it.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"state" to mapOf(
				"type" to "object",
				"description" to "Text or structured state to judge.",
			),
			"questions" to mapOf(
				"type" to "object",
				"description" to "Question id -> {type, instructions, criteria}.",
				"additionalProperties" to mapOf("type" to "object"),
			),
		),
		"required" to listOf("state", "questions"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val questions = arguments["questions"]?.jsonObject
			?: return ToolResult("questions must be an object", isError = true)
		if (questions.isEmpty()) {
			return ToolResult("questions must not be empty", isError = true)
		}
		val state = arguments["state"] ?: JsonNull
		return runCatching { client.decide(state, questions, model = model) }.fold(
			onSuccess = { ToolResult.ok(it.toString()) },
			onFailure = { ToolResult(it.message ?: "decision failed", isError = true) },
		)
	}
}
