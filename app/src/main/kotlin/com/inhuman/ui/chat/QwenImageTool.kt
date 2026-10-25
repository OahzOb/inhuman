package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.provider.qwen.QwenTokenPlanClient
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.files.SessionWorkspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generate an image and leave it in the conversation workspace.
 *
 * The result is a path and metadata, never base64: the transcript must not carry
 * binary, and a model that receives a path can pass it to a later file tool.
 */
class QwenImageTool(
	private val client: QwenTokenPlanClient,
	private val workspace: SessionWorkspace,
) : Tool {

	override val name = "generate_image"

	override val mutates = true

	override val description = """
		Generate a new image from a text prompt.

		Use this when the user asks for a picture, illustration, diagram-like
		visual, or a visual variation that does not already exist in the
		workspace. The image is downloaded and saved in the workspace; the result
		is a file path, not the image bytes.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"prompt" to mapOf(
				"type" to "string",
				"description" to "What the image should show.",
			),
			"size" to mapOf(
				"type" to "string",
				"description" to "Output size such as 1024*1024.",
			),
		),
		"required" to listOf("prompt"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val prompt = arguments["prompt"]?.jsonPrimitive?.contentOrNull
			?.takeIf { it.isNotBlank() }
			?: return ToolResult("prompt is required", isError = true)
		val size = arguments["size"]?.jsonPrimitive?.contentOrNull ?: "1024*1024"
		return runCatching {
			val result = client.generateImage(prompt, size = size)
			val extension = if (result.mimeType.contains("webp")) "webp" else "png"
			val path = "generated/image-${System.currentTimeMillis()}.$extension"
			val resolution = workspace.resolve(path)
			if (resolution !is SessionWorkspace.Resolution.Ok) {
				error("workspace refused the generated image path")
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
			Diag.i("qwen", "tool image path=$path bytes=${result.bytes.size}")
			ToolResult.ok(
				"Generated image saved at $path (${result.mimeType}, ${result.bytes.size} bytes, " +
					"model ${result.model}).",
			)
		}.getOrElse { ToolResult("image generation failed: ${it.message}", isError = true) }
	}
}

