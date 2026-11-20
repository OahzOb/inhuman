package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Copy one remote file into the conversation workspace over SFTP.
 *
 * ## Why not just `cat`
 *
 * `ssh_exec` can print a file, but that path is text-only, capped at 20,000
 * characters per stream, and puts the whole file through the transcript. A
 * download puts it in Files instead, where the user can open it, and a text
 * file can then be read with `read_file` under the normal workspace limits.
 *
 * ## Consent
 *
 * No per-call approval: the per-conversation SSH grant covers it. This
 * direction is `reversible = true` because the bytes land in a workspace copy,
 * which the conversation's rollback checkpoints can restore.
 */
class SshDownloadTool(
	private val transport: SshFileTransport,
	private val readWorkspaceFile: (String) -> ByteArray? = { null },

	private val saveWorkspaceFile: (remotePath: String, bytes: ByteArray, overwrite: Boolean) -> String,
	private val maxBytes: Long = 4L shl 20,
) : SshTool {

	override val name: String = "ssh_download"

	override val description: String = """
		Download one file from a remote machine into this conversation's workspace over SSH (SFTP).
		Use it for logs, build artefacts and generated files that are easier to open or read locally than to print with cat. A downloaded text file can then be read with read_file.
		${SshArguments.connectionHelp()}
		The user is asked once per conversation, before the first SSH call, that the agent may reach the machines it is given. After that, normal tool policy applies.
		Limit: $maxBytes bytes per call. An existing workspace file is refused unless overwrite=true.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to SshArguments.connectionParameters() + mapOf(
			"remotePath" to mapOf(
				"type" to "string",
				"description" to "File to read on the remote machine",
			),
			"localPath" to mapOf(
				"type" to "string",
				"description" to "Workspace-relative path to write; parent folders are created",
			),
			"overwrite" to mapOf(
				"type" to "boolean",
				"description" to "Replace an existing workspace file; default false",
			),
		),
		"required" to listOf("remotePath", "localPath"),
	)

	override val mutates: Boolean = true
	override val reversible: Boolean = true

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val args = SshArguments.normalize(arguments)
		val connection = when (val parsed = SshArguments.parse(name, args, readWorkspaceFile)) {
			is SshArguments.Parsed.Error -> return ToolResult.error(parsed.message)
			is SshArguments.Parsed.Ok -> parsed.value
		}
		val remotePath = SshArguments.string(args, "remotePath").orEmpty()
		val localPath = SshArguments.string(args, "localPath").orEmpty()
		if (remotePath.isBlank()) return ToolResult.error("ssh_download: remotePath is required")
		if (localPath.isBlank()) return ToolResult.error("ssh_download: localPath is required")
		if (remotePath.indexOf('\u0000') >= 0 || localPath.indexOf('\u0000') >= 0) {
			return ToolResult.error("ssh_download: paths cannot contain a null byte")
		}

		return try {
			val result = transport.download(
				host = connection.host,
				port = connection.port,
				username = connection.username,
				remotePath = remotePath,
				auth = connection.auth,
				hostKeyFingerprint = connection.hostKeyFingerprint,
				maxBytes = maxBytes,
				connectTimeoutMs = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
			)
			val saved = saveWorkspaceFile(
				localPath,
				result.bytes,
				SshArguments.boolean(args, "overwrite"),
			)
			ToolResult.ok(
				"downloaded ${result.bytes.size} bytes from " +
					"${connection.host}:$remotePath to $saved\n" +
					"host key: ${result.hostFingerprint}",
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: SshException) {
			ToolResult.error("ssh_download: ${e.message}")
		} catch (e: IllegalArgumentException) {
			ToolResult.error("ssh_download: ${e.message}")
		} catch (e: Exception) {
			ToolResult.error("ssh_download: ${e.message ?: e.javaClass.simpleName}")
		}
	}
}
