package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.provider.qwen.QwenTokenPlanClient
import com.inhuman.core.files.SessionWorkspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Text to speech as an agent tool; the audio file is the result. */
class QwenSpeechTool(
	private val client: QwenTokenPlanClient,
	private val workspace: SessionWorkspace,
) : Tool {

	override val name = "speak"

	override val mutates = true

	override val description = """
		Convert text to an audio file in the workspace.

		Use this when the user asks for something to be read aloud or wants an
		audio version. The result is an MP3 path; it does not play automatically.
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
			val result = client.synthesize(text)
			val path = "generated/audio-${System.currentTimeMillis()}.mp3"
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
