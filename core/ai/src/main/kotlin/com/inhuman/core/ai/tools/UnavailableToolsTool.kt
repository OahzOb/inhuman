package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * The tools this app offers that this conversation cannot use, and why.
 *
 * ## Why the agent is allowed to ask
 *
 * Without it, a model that cannot read a file says "I cannot read files" and
 * the user is left to work out whether the tool is missing, the conversation's
 * selection excludes it, or a provider key is absent. The answer is
 * information the user already has in Settings; the agent simply could not see
 * it.
 *
 * ## What it cannot do
 *
 * It does not enable anything, and it does not report capabilities the app has
 * switched off -- [ToolGaps] explains why that line is where it is. Only the
 * user can change the selection, and `ToolCatalog.permits` still decides what
 * runs.
 */
class UnavailableToolsTool(
	private val gaps: () -> List<ToolGaps.Gap>,
) : Tool {

	override val name: String = "unavailable_tools"

	override val description: String = """
		List the tools this app offers that this conversation cannot use, with the reason.
		Use it when a request seems to need a capability you do not have, so you can tell the user what is missing instead of guessing.
		It only reports; it cannot enable anything, and capabilities the app has switched off entirely are not listed.
		""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to emptyMap<String, Any?>(),
		"required" to emptyList<String>(),
	)

	override val mutates: Boolean = false
	override val reversible: Boolean = true

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val missing = gaps()
		if (missing.isEmpty()) {
			return ToolResult.ok("Every tool this app offers is available to you in this conversation.")
		}
		return ToolResult.ok(
			buildString {
				append("Tools this app offers that this conversation cannot use:\n")
				missing.forEach { gap ->
					append("- ").append(gap.name).append(" — ").append(gap.blurb)
					append(" (").append(gap.reason.wire).append(")\n")
				}
				append("\nOnly the user can change this. Tell them what is missing; do not claim to have used it.")
			},
		)
	}
}
