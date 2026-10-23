package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Write a small text file on the remote machine over SFTP.
 *
 * The exec tool can write files only through a heredoc, which is where most of
 * the quoting accidents in the real session came from. This is the same content
 * path as `ssh_upload`, without needing a local file first: one call, no shell,
 * and the bytes are exactly what the model wrote.
 *
 * The cap is deliberately much smaller than upload's. A config, unit file or
 * deploy script fits; an artifact belongs in the workspace and goes through
 * `ssh_upload`.
 */
class SshWriteFileTool(
	private val transport: SshFileTransport,
	private val readWorkspaceFile: (String) -> ByteArray? = { null },
	private val maxBytes: Int = 64 * 1024,
) : Tool {

	override val name: String = "ssh_write_file"

	override val description: String = """
		Write a text file on a remote machine over SFTP.
		Use it for a config, a systemd unit or a small script instead of a shell heredoc; the content is written verbatim and is binary-safe.
		The app has no host inventory or credential store: pass host, username and a way to authenticate, exactly as for ssh_exec. Prefer identityFile over a password or raw key.
		Limit: $maxBytes bytes. An existing file is refused unless overwrite=true; that replacement cannot be undone. Set createParents=true to create a missing parent directory.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to SshArguments.connectionParameters() + mapOf(
			"remotePath" to mapOf(
				"type" to "string",
				"description" to "File to write on the remote machine",
			),
			"content" to mapOf(
				"type" to "string",
				"description" to "UTF-8 text content",
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
		"required" to listOf("host", "username", "remotePath", "content"),
	)

	override val mutates: Boolean = true
	override val reversible: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val args = SshArguments.normalize(arguments)
		val connection = when (val parsed = SshArguments.parse(name, args, readWorkspaceFile)) {
			is SshArguments.Parsed.Error -> return ToolResult.error(parsed.message)
			is SshArguments.Parsed.Ok -> parsed.value
		}
		val remotePath = SshArguments.string(args, "remotePath").orEmpty()
		if (remotePath.isBlank()) return ToolResult.error("ssh_write_file: remotePath is required")
		if (remotePath.indexOf('\u0000') >= 0) {
			return ToolResult.error("ssh_write_file: paths cannot contain a null byte")
		}
		val content = SshArguments.rawString(args, "content")
			?: return ToolResult.error("ssh_write_file: content is required")
		val bytes = content.toByteArray(Charsets.UTF_8)
		if (bytes.size > maxBytes) {
			return ToolResult.error(
				"ssh_write_file: content is ${bytes.size} bytes; the limit is $maxBytes",
			)
		}
		if (bytes.any { it == 0.toByte() }) {
			return ToolResult.error("ssh_write_file: content contains a null byte")
		}

		return try {
			val result = transport.upload(
				host = connection.host,
				port = connection.port,
				username = connection.username,
				remotePath = remotePath,
				content = bytes,
				auth = connection.auth,
				hostKeyFingerprint = connection.hostKeyFingerprint,
				overwrite = SshArguments.boolean(args, "overwrite"),
				createParents = SshArguments.boolean(args, "createParents"),
				connectTimeoutMs = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
			)
			ToolResult.ok(
				"wrote ${result.bytes} bytes to ${connection.host}:$remotePath\n" +
					"host key: ${result.hostFingerprint}",
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: SshException) {
			ToolResult.error("ssh_write_file: ${e.message}")
		} catch (e: Exception) {
			ToolResult.error("ssh_write_file: ${e.message ?: e.javaClass.simpleName}")
		}
	}
}
