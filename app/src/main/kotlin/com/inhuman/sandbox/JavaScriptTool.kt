package com.inhuman.sandbox

import android.content.Context
import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `run_javascript`: a calculation and data-shaping tool, not a shell.
 *
 * It is registered only when [SandboxRuntime] has passed its device probe. The
 * tool itself has no workspace, no network and no approval exemption: selecting
 * it is a normal per-turn capability choice, and the sandbox's own boundaries
 * are the reason its result cannot become a file or a request on its own.
 */
class JavaScriptTool(private val context: Context) : Tool {

	override val name = "run_javascript"
	override val mutates = false
	override val description =
		"Run a short JavaScript function in an isolated no-network sandbox and " +
			"return its result. The script runs inside a function and must return " +
			"a value; `fetch`, sockets, `require`, `process` and the filesystem are " +
			"not available. 16 MiB heap, 10 second limit."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"script" to mapOf(
				"type" to "string",
				"description" to "JavaScript source. Use `return` for the result.",
			),
		),
		"required" to listOf("script"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val script = arguments["script"]?.jsonPrimitive?.contentOrNull
			?: return ToolResult.error("script is required")
		return SandboxRuntime.run(context, script).fold(
			onSuccess = { ToolResult.ok(it) },
			onFailure = { ToolResult.error(it.message ?: "JavaScript failed") },
		)
	}
}