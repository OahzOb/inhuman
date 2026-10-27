package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshProfile
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Copy one workspace file to a remote machine over SFTP.
 *
 * ## Why this exists next to `ssh_exec`
 *
 * A command can already write a remote file with a heredoc, and the usage data
 * from a real conversation showed that is exactly what happens: hundreds of
 * multiline commands, some near 9 KB, each carrying a script that exists only
 * inside one tool call. Uploading a file that already lives in the workspace is
 * binary-safe, reviewable in Files, reusable across turns, and does not depend
 * on shell quoting.
 *
 * ## Consent
 *
 * No per-call approval. The once-per-conversation SSH capability grant covers
 * this tool exactly as it covers `ssh_exec`; the extra disclosure is
 * `reversible = false`, because a remote file that is replaced cannot be
 * restored by deleting the conversation.
 */
class SshUploadTool(
	private val transport: SshFileTransport,
	private val readWorkspaceFile: (String) -> ByteArray? = { null },
	private val savedConnection: () -> SshProfile? = { null },
	private val maxBytes: Long = 1L shl 20,
) : SshTool {

	override val name: String = "ssh_upload"

	override val description: String = """
		Upload one file from this conversation's workspace to a remote machine over SSH (SFTP).
		Use it to deploy a script or config you already wrote in the workspace instead of pasting the whole file into a command; it is binary-safe and avoids shell quoting.
		The app can use a connection saved in Settings with useSavedHost=true; otherwise pass host, username and authentication. Prefer identityFile over a password or raw key.
		The user is asked once per conversation, before the first SSH call, that the agent may reach the machines it is given. After that, normal tool policy applies.
		Limit: $maxBytes bytes per call. An existing remote file is refused unless overwrite=true; overwriting it may be irreversible. Set createParents=true to create a missing parent directory.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to SshArguments.connectionParameters() + mapOf(
			"localPath" to mapOf(
				"type" to "string",
				"description" to "Workspace-relative path of the file to send",
			),
			"remotePath" to mapOf(
				"type" to "string",
				"description" to "Destination path on the remote machine; its parent must exist unless createParents=true",
			),
			"overwrite" to mapOf(
				"type" to "boolean",
				"description" to "Replace an existing remote file; default false",
			),
			"createParents" to mapOf(
				"type" to "boolean",
				"description" to "Create missing remote parent directories; default false",
			),
		),
		"required" to listOf("localPath", "remotePath"),
	)

	override val mutates: Boolean = true
	override val reversible: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val args = SshArguments.normalize(arguments)
		val connection = when (val parsed = SshArguments.parse(name, args, readWorkspaceFile, savedConnection)) {
			is SshArguments.Parsed.Error -> return ToolResult.error(parsed.message)
			is SshArguments.Parsed.Ok -> parsed.value
		}
		val localPath = SshArguments.string(args, "localPath").orEmpty()
		val remotePath = SshArguments.string(args, "remotePath").orEmpty()
		if (localPath.isBlank()) return ToolResult.error("ssh_upload: localPath is required")
		if (remotePath.isBlank()) return ToolResult.error("ssh_upload: remotePath is required")
		if (remotePath.indexOf('\u0000') >= 0 || localPath.indexOf('\u0000') >= 0) {
			return ToolResult.error("ssh_upload: paths cannot contain a null byte")
		}

		val content = readWorkspaceFile(localPath)
			?: return ToolResult.error("ssh_upload: local file not found: $localPath")
		if (content.size > maxBytes) {
			return ToolResult.error(
				"ssh_upload: $localPath is ${content.size} bytes; the limit is $maxBytes",
			)
		}

		return try {
			val result = transport.upload(
				host = connection.host,
				port = connection.port,
				username = connection.username,
				remotePath = remotePath,
				content = content,
				auth = connection.auth,
				hostKeyFingerprint = connection.hostKeyFingerprint,
				overwrite = SshArguments.boolean(args, "overwrite"),
				createParents = SshArguments.boolean(args, "createParents"),
				connectTimeoutMs = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
			)
			ToolResult.ok(
				"uploaded ${result.bytes} bytes from $localPath to " +
					"${connection.host}:$remotePath\n" +
					"host key: ${result.hostFingerprint}",
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: SshException) {
			ToolResult.error("ssh_upload: ${e.message}")
		} catch (e: Exception) {
			ToolResult.error("ssh_upload: ${e.message ?: e.javaClass.simpleName}")
		}
	}
}
