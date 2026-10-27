package com.inhuman.ui.chat

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.provider.qwen.QwenTokenPlanClient
import com.inhuman.core.files.SessionWorkspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Submit a video task; the agent polls it with [QwenVideoStatusTool]. */
class QwenVideoTool(
	private val client: QwenTokenPlanClient,
	private val model: String = QwenTokenPlanClient.DEFAULT_VIDEO_MODEL,
) : Tool {

	override val name = "generate_video"

	override val mutates = true

	override val description = """
		Start generating a video from a text prompt.

		This is asynchronous and can take minutes. It returns a task id; check it
		with video_status. Do not block the conversation waiting for the video.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"prompt" to mapOf("type" to "string", "description" to "What the video should show."),
			"resolution" to mapOf("type" to "string", "description" to "480P, 720P or 1080P."),
			"ratio" to mapOf("type" to "string", "description" to "16:9, 9:16, 1:1, ..."),
			"duration" to mapOf("type" to "integer", "description" to "2–30 seconds."),
		),
		"required" to listOf("prompt"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val prompt = arguments["prompt"]?.jsonPrimitive?.contentOrNull
			?.takeIf { it.isNotBlank() }
			?: return ToolResult("prompt is required", isError = true)
		val resolution = arguments["resolution"]?.jsonPrimitive?.contentOrNull ?: "720P"
		val ratio = arguments["ratio"]?.jsonPrimitive?.contentOrNull ?: "16:9"
		val duration = arguments["duration"]?.jsonPrimitive?.intOrNull?.coerceIn(2, 30) ?: 5
		return runCatching {
			val id = client.submitVideo(
				prompt,
				model = model,
				resolution = resolution,
				ratio = ratio,
				duration = duration,
			)
			ToolResult.ok(
				"Video task submitted: $id. Use video_status with task_id=$id to check progress.",
			)
		}.getOrElse { ToolResult("video submission failed: ${it.message}", isError = true) }
	}
}

/** Poll one video task and download the result into the workspace when done. */
class QwenVideoStatusTool(
	private val client: QwenTokenPlanClient,
	private val workspace: SessionWorkspace,
) : Tool {

	override val name = "video_status"

	override val mutates = true

	override val description = """
		Check a video generation task.

		Returns the task status. When the status is SUCCEEDED, downloads the video
		into the workspace and returns its path. Call this after generate_video,
		not in a tight loop.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"task_id" to mapOf("type" to "string", "description" to "Task id from generate_video."),
		),
		"required" to listOf("task_id"),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val taskId = arguments["task_id"]?.jsonPrimitive?.contentOrNull
			?.takeIf { it.isNotBlank() }
			?: return ToolResult("task_id is required", isError = true)
		return runCatching {
			val task = client.videoTask(taskId)
			if (task.status == "SUCCEEDED") {
				val url = task.videoUrl ?: error("task succeeded without a video URL")
				val bytes = client.downloadVideo(url)
				val path = "generated/video-${System.currentTimeMillis()}.mp4"
				val resolution = workspace.resolve(path)
				if (resolution !is SessionWorkspace.Resolution.Ok) {
					error("workspace refused the generated video path")
				}
				workspace.quotaRefusal(
					stagingBytes = 0,
					stagingFiles = 0,
					replacedBytes = 0,
					addsFiles = 1,
					newBytes = bytes.size.toLong(),
				)?.let { error(it) }
				resolution.file.parentFile?.mkdirs()
				resolution.file.writeBytes(bytes)
				ToolResult.ok("Video saved at $path (${bytes.size} bytes).")
			} else {
				ToolResult.ok("Video task $taskId status: ${task.status}" + (task.message?.let { " — $it" } ?: ""))
			}
		}.getOrElse { ToolResult("video status failed: ${it.message}", isError = true) }
	}
}
