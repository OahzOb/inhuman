package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.ai.ssh.SshProfile
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
	private val savedConnection: () -> SshProfile? = { null },
	private val maxBytes: Long = 64L * 1024,
) : SshTool {

	override val name: String = "ssh_read_file"

	override val description: String = """
		Read one text file from a remote machine over SFTP.
		Use it for a config, a log tail or a small source file; the content is returned directly and becomes part of the conversation.
		The app can use a connection saved in Settings with useSavedHost=true; otherwise pass host, username and authentication. Prefer identityFile over a password or raw key.
		Without tailLines, the first $maxBytes bytes are returned and a larger file is refused; use ssh_download for a large file. With tailLines=N, the last N lines are returned from a bounded tail window. Binary files are refused.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to SshArguments.connectionParameters() + mapOf(
			"remotePath" to mapOf(
				"type" to "string",
				"description" to "Text file to read",
			),
			"tailLines" to mapOf(
				"type" to "integer",
				"description" to "Return the last N lines instead of the file head; 0 or absent reads from the start",
			),
			"maxBytes" to mapOf(
				"type" to "integer",
				"description" to "Optional byte limit, at most $maxBytes; larger values are clamped",
			),
		),
		"required" to listOf("remotePath"),
	)

	override val mutates: Boolean = false
	override val reversible: Boolean = true

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val args = SshArguments.normalize(arguments)
		val connection = when (val parsed = SshArguments.parse(name, args, readWorkspaceFile, savedConnection)) {
			is SshArguments.Parsed.Error -> return ToolResult.error(parsed.message)
			is SshArguments.Parsed.Ok -> parsed.value
		}
		val remotePath = SshArguments.string(args, "remotePath").orEmpty()
		if (remotePath.isBlank()) return ToolResult.error("ssh_read_file: remotePath is required")
		if (remotePath.indexOf('\u0000') >= 0) {
			return ToolResult.error("ssh_read_file: paths cannot contain a null byte")
		}
		val tailLines = SshArguments.int(args, "tailLines", 0)
		if (tailLines < 0 || tailLines > MAX_TAIL_LINES) {
			return ToolResult.error(
				"ssh_read_file: tailLines must be between 0 and $MAX_TAIL_LINES",
			)
		}
		val requestedBytes = SshArguments.int(args, "maxBytes", 0).takeIf { it > 0 }
		val effectiveBytes = (
			requestedBytes?.toLong()
				?: minOf(SshLimits.DEFAULT_READ_BYTES, maxBytes)
			).coerceIn(1L, maxBytes)
		val limitNote = if (requestedBytes != null && requestedBytes.toLong() != effectiveBytes) {
			"maxBytes clamped to $effectiveBytes (user ceiling $maxBytes)"
		} else {
			null
		}

		return try {
			if (tailLines > 0) {
				readTail(connection, remotePath, tailLines, effectiveBytes, limitNote)
			} else {
				val result = transport.download(
					host = connection.host,
					port = connection.port,
					username = connection.username,
					remotePath = remotePath,
					auth = connection.auth,
					hostKeyFingerprint = connection.hostKeyFingerprint,
					maxBytes = effectiveBytes,
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
						if (limitNote != null) append("limits: ").append(limitNote).append('\n')
						append(remotePath).append(" (").append(result.bytes.size).append(" bytes)\n")
						append("host key: ").append(result.hostFingerprint).append('\n')
						append("content:\n")
						append(text)
					},
				)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: SshException) {
			ToolResult.error("ssh_read_file: ${e.message}")
		} catch (e: Exception) {
			ToolResult.error("ssh_read_file: ${e.message ?: e.javaClass.simpleName}")
		}
	}

	private suspend fun readTail(
		connection: SshConnectionArgs,
		remotePath: String,
		tailLines: Int,
		maxBytes: Long,
		limitNote: String?,
	): ToolResult {
		val result = transport.downloadTail(
			host = connection.host,
			port = connection.port,
			username = connection.username,
			remotePath = remotePath,
			auth = connection.auth,
			hostKeyFingerprint = connection.hostKeyFingerprint,
			// The user's read ceiling is also a ceiling on how much of the file
			// this call pulls over the network; 256 KiB is only the upper bound.
			maxBytes = minOf(TAIL_WINDOW_BYTES, maxBytes),
			connectTimeoutMs = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
		)
		if (result.bytes.any { it == 0.toByte() }) {
			return ToolResult.error(
				"ssh_read_file: $remotePath looks binary; use ssh_download instead",
			)
		}
		var text = String(result.bytes, Charsets.UTF_8)
		// The tail window begins at an arbitrary byte, so its first line is
		// usually partial. Drop it rather than presenting half a log line as a
		// complete one.
		if (result.startOffset > 0) {
			val firstNewline = text.indexOf('\n')
			text = if (firstNewline >= 0) text.substring(firstNewline + 1) else ""
		}
		val rawLines = text.split('\n')
		val completeLines = if (rawLines.lastOrNull()?.isEmpty() == true) {
			rawLines.dropLast(1)
		} else {
			rawLines
		}
		val selected = completeLines.takeLast(tailLines)
		val joined = selected.joinToString("\n")
		if (joined.toByteArray(Charsets.UTF_8).size.toLong() > maxBytes) {
			return ToolResult.error(
				"ssh_read_file: last $tailLines lines are larger than $maxBytes bytes; reduce tailLines",
			)
		}
		return ToolResult.ok(
			buildString {
				if (limitNote != null) append("limits: ").append(limitNote).append('\n')
				append(remotePath)
					.append(" (last ").append(selected.size).append(" lines of ")
					.append(result.totalBytes).append(" bytes)\n")
				append("host key: ").append(result.hostFingerprint).append('\n')
				append("content:\n")
				append(joined)
			},
		)
	}

	private companion object {
		const val MAX_TAIL_LINES = 2000
		const val TAIL_WINDOW_BYTES = 256L * 1024
	}
}
