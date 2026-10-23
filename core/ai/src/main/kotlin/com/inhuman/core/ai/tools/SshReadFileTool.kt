package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Read one remote text file into the conversation.
 *
 * The read-only profile needs an answer to "what does this config say" without
 * making the user download the file, open Files, and read it. The cap is much
 * smaller than `ssh_download`'s because this text goes into the prompt: 64 KiB
 * of log is already a lot of context, and a larger file should be downloaded
 * (and read with `read_file`) rather than pasted into every later request.
 *
 * Binary files are refused rather than decoded into replacement characters;
 * `ssh_download` is the way to fetch those.
 */
class SshReadFileTool(
	private val transport: SshFileTransport,
	private val readWorkspaceFile: (String) -> ByteArray? = { null },
	private val maxBytes: Long = 64L * 1024,
) : Tool {

	override val name: String = "ssh_read_file"

	override val description: String = """
		Read one text file from a remote machine over SFTP.
		Use it for a config, a log tail or a small source file; the content is returned directly and becomes part of the conversation.
		The app has no host inventory or credential store: pass host, username and a way to authenticate, exactly as for ssh_exec. Prefer identityFile over a password or raw key.
		Limit: $maxBytes bytes; a larger file is refused, so download it with ssh_download and read it locally. Binary files are refused.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to SshArguments.connectionParameters() + mapOf(
			"remotePath" to mapOf(
				"type" to "string",
				"description" to "Text file to read",
			),
		),
		"required" to listOf("host", "username", "remotePath"),
	)

	override val mutates: Boolean = false
	override val reversible: Boolean = true

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val args = SshArguments.normalize(arguments)
		val connection = when (val parsed = SshArguments.parse(name, args, readWorkspaceFile)) {
			is SshArguments.Parsed.Error -> return ToolResult.error(parsed.message)
			is SshArguments.Parsed.Ok -> parsed.value
		}
		val remotePath = SshArguments.string(args, "remotePath").orEmpty()
		if (remotePath.isBlank()) return ToolResult.error("ssh_read_file: remotePath is required")
		if (remotePath.indexOf('\u0000') >= 0) {
			return ToolResult.error("ssh_read_file: paths cannot contain a null byte")
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
			if (result.bytes.any { it == 0.toByte() }) {
				return ToolResult.error(
					"ssh_read_file: $remotePath looks binary; use ssh_download instead",
				)
			}
			val text = String(result.bytes, Charsets.UTF_8)
			ToolResult.ok(
				buildString {
					append(remotePath).append(" (").append(result.bytes.size).append(" bytes)\n")
					append("host key: ").append(result.hostFingerprint).append('\n')
					append("content:\n")
					append(text)
				},
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: SshException) {
			ToolResult.error("ssh_read_file: ${e.message}")
		} catch (e: Exception) {
			ToolResult.error("ssh_read_file: ${e.message ?: e.javaClass.simpleName}")
		}
	}
}
