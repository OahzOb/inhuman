package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.speech.SpeechBackend
import com.inhuman.core.files.SessionWorkspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Text to speech as an agent tool; the audio file is the result.
 *
 * The backend is whatever the user configured for the speech capability (Qwen
 * Token Plan or DeepSeek), so the file extension follows the service's output
 * container rather than a hardcoded MP3.
 */
class SpeechTool(
	private val backend: SpeechBackend,
	private val workspace: SessionWorkspace,
) : Tool {

	override val name = "speak"

	override val mutates = true

	override val description = """
		Convert text to an audio file in the workspace.

		Use this when the user asks for something to be read aloud or wants an
		audio version. The result is an audio path; it does not play automatically.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"text" to mapOf("type" to "string", "description" to "Text to speak."),
		),
		"required" to listOf("text"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val text = arguments["text"]?.jsonPrimitive?.contentOrNull
			?.takeIf { it.isNotBlank() }
			?: return ToolResult("text is required", isError = true)
		return runCatching {
			val result = backend.synthesize(text)
			val path = "generated/audio-${System.currentTimeMillis()}.${result.extension}"
			val resolution = workspace.resolve(path)
			if (resolution !is SessionWorkspace.Resolution.Ok) {
				error("workspace refused the generated audio path")
			}
			workspace.quotaRefusal(
				stagingBytes = 0,
				stagingFiles = 0,
				replacedBytes = 0,
				addsFiles = 1,
				newBytes = result.bytes.size.toLong(),
			)?.let { error(it) }
			resolution.file.parentFile?.mkdirs()
			resolution.file.writeBytes(result.bytes)
			ToolResult.ok("Audio saved at $path (${result.bytes.size} bytes).")
		}.getOrElse { ToolResult("speech failed: ${it.message}", isError = true) }
	}
}
