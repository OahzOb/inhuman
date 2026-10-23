package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshEntryType
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.ai.ssh.SshRemoteEntry
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * List one remote directory over SFTP.
 *
 * ## Why this exists without `ssh_exec`
 *
 * The read-only SSH profile is list/read/download; it deliberately has no shell.
 * `ls` through exec would reintroduce command execution for a task that SFTP
 * already answers, so this is `sftp.ls`, not a command.
 *
 * No recursion: `find` over a whole tree is an unbounded amount of output and
 * exactly the kind of remote work that should need `ssh_exec`. One directory,
 * at most [maxEntries] rows.
 */
class SshListTool(
	private val transport: SshFileTransport,
	private val readWorkspaceFile: (String) -> ByteArray? = { null },
	private val maxEntries: Int = 500,
) : Tool {

	override val name: String = "ssh_list"

	override val description: String = """
		List one directory on a remote machine over SFTP.
		Use it to find a file before calling ssh_read_file or ssh_download; it is not recursive and does not run a shell command.
		The app has no host inventory or credential store: pass host, username and a way to authenticate, exactly as for ssh_exec. Prefer identityFile over a password or raw key.
		At most $maxEntries entries are returned, directories first, then by name. Each row shows type, size, modification time and name.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to SshArguments.connectionParameters() + mapOf(
			"remotePath" to mapOf(
				"type" to "string",
				"description" to "Directory to list, for example /var/log or /home/td/project",
			),
			"maxEntries" to mapOf(
				"type" to "integer",
				"description" to "Optional row limit, at most $maxEntries; larger values are clamped",
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
		if (remotePath.isBlank()) return ToolResult.error("ssh_list: remotePath is required")
		if (remotePath.indexOf('\u0000') >= 0) {
			return ToolResult.error("ssh_list: paths cannot contain a null byte")
		}
		val requested = SshArguments.int(args, "maxEntries", 0).takeIf { it > 0 }
		val ceiling = maxEntries.coerceAtLeast(1)
		val effective = (requested ?: minOf(SshLimits.DEFAULT_LIST_ENTRIES, ceiling))
			.coerceIn(1, ceiling)
		val note = if (requested != null && requested != effective) {
			"maxEntries clamped to $effective (user ceiling $ceiling)"
		} else {
			null
		}

		return try {
			val entries = transport.list(
				host = connection.host,
				port = connection.port,
				username = connection.username,
				remotePath = remotePath,
				auth = connection.auth,
				hostKeyFingerprint = connection.hostKeyFingerprint,
				maxEntries = effective,
				connectTimeoutMs = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
			)
			ToolResult.ok(format(remotePath, entries, effective, note))
		} catch (e: CancellationException) {
			throw e
		} catch (e: SshException) {
			ToolResult.error("ssh_list: ${e.message}")
		} catch (e: Exception) {
			ToolResult.error("ssh_list: ${e.message ?: e.javaClass.simpleName}")
		}
	}

	private fun format(
		remotePath: String,
		entries: List<SshRemoteEntry>,
		maxEntries: Int,
		note: String?,
	): String {
		val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
			.withZone(ZoneId.systemDefault())
		// Sort here rather than trusting the transport: SFTP servers do not
		// promise an order, and a deterministic listing is easier for the model
		// and the user to scan.
		val sorted = entries.sortedWith(
			compareBy({ it.type != SshEntryType.DIRECTORY }, { it.name }),
		)
		return buildString {
			if (note != null) append("limits: ").append(note).append('\n')
			append(remotePath).append(":\n")
			if (sorted.isEmpty()) {
				append("(empty)\n")
			} else {
				sorted.forEach { entry ->
					append(
						when (entry.type) {
							SshEntryType.DIRECTORY -> "d"
							SshEntryType.SYMLINK -> "l"
							SshEntryType.FILE -> "-"
							SshEntryType.OTHER -> "?"
						},
					)
					append("  ")
					append(entry.size.toString().padStart(10))
					append("  ")
					append(
						entry.modifiedAt?.let {
							formatter.format(Instant.ofEpochSecond(it))
						} ?: "-",
					)
					append("  ")
					append(entry.name)
					append('\n')
				}
			}
			if (sorted.size >= maxEntries) {
				append("\n[at most $maxEntries entries shown; narrow the directory]\n")
			}
		}
	}
}
