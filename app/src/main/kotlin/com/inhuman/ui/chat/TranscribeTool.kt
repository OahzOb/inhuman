package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.speech.SpeechBackend
import com.inhuman.core.files.SessionWorkspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Transcribe an audio file already in the workspace.
 *
 * The backend decides which extensions it accepts: DeepSeek's ASR takes WAV and
 * Ogg Opus but not MP3, so an MP3 must be refused with a readable reason rather
 * than sent to an endpoint that would reject it.
 */
class TranscribeTool(
	private val backend: SpeechBackend,
	private val workspace: SessionWorkspace,
) : Tool {

	override val name = "transcribe"

	override val mutates = false

	override val description = """
		Transcribe an audio file from the workspace.

		Use this when the user refers to a recording or asks what an audio file
		says. The result is text; no audio is written.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"path" to mapOf("type" to "string", "description" to "Workspace audio path."),
		),
		"required" to listOf("path"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val path = arguments["path"]?.jsonPrimitive?.contentOrNull
			?.takeIf { it.isNotBlank() }
			?: return ToolResult("path is required", isError = true)
		val resolution = workspace.resolve(path)
		if (resolution !is SessionWorkspace.Resolution.Ok) {
			return ToolResult("file not found: $path", isError = true)
		}
		val file = resolution.file
		if (!file.isFile) return ToolResult("not a file: $path", isError = true)
		val format = file.extension.lowercase()
		if (format !in setOf("mp3", "wav", "opus")) {
			return ToolResult("unsupported audio format: .$format", isError = true)
		}
		if (!backend.acceptsFormat(format)) {
			return ToolResult(
				"the selected transcription backend does not accept .$format",
				isError = true,
			)
		}
		return runCatching {
			ToolResult.ok(backend.transcribe(file.readBytes(), format))
		}.getOrElse { ToolResult("transcription failed: ${it.message}", isError = true) }
	}
}
