package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.provider.qwen.QwenTokenPlanClient
import com.inhuman.core.files.SessionWorkspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Transcribe an audio file already in the workspace. */
class QwenTranscribeTool(
	private val client: QwenTokenPlanClient,
	private val workspace: SessionWorkspace,
	private val model: String = QwenTokenPlanClient.DEFAULT_ASR_MODEL,
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
		val extension = file.extension.lowercase()
		val format = when (extension) {
			"mp3" -> "mp3"
			"wav" -> "wav"
			"opus" -> "opus"
			else -> return ToolResult("unsupported audio format: .$extension", isError = true)
		}
		val mime = when (format) {
			"mp3" -> "audio/mpeg"
			"wav" -> "audio/wav"
			else -> "audio/opus"
		}
		return runCatching {
			val result = client.transcribe(file.readBytes(), format = format, mimeType = mime, model = model)
			ToolResult.ok(result.text)
		}.getOrElse { ToolResult("transcription failed: ${it.message}", isError = true) }
	}
}
