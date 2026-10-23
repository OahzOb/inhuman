package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * A fixed, read-only snapshot of a remote Linux host.
 *
 * ## Why this is a tool and not five `ssh_exec` calls
 *
 * "What is this machine doing" is the first question in almost every debugging
 * session, and it was five round trips: os-release, uptime, free, df, failed
 * units, ports, containers. Each one had to be composed and parsed by the model.
 * This runs the same fixed script every time and returns the sections together.
 *
 * The model cannot pass a command, a path or an argument; only the connection
 * details vary. That is why it can be offered in the read-only profile without
 * making `ssh_exec` available. Nothing here writes, restarts or installs.
 */
class SshProbeTool(
	private val transport: SshTransport,
	private val limits: SshLimits = SshLimits(),
	private val readWorkspaceFile: (String) -> ByteArray? = { null },
) : Tool {

	override val name: String = "ssh_probe"

	override val description: String = """
		Read-only snapshot of a remote Linux machine.
		Runs one fixed script and returns OS, kernel, uptime, load, CPU count, memory, disk, inodes, failed systemd units, listening ports and Docker containers when those commands exist.
		It never uses sudo, never writes, and accepts no command or path: use ssh_exec for anything not covered.
		The app has no host inventory or credential store: pass host, username and a way to authenticate. Prefer identityFile over a password or raw key.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to SshArguments.connectionParameters(),
		"required" to listOf("host", "username"),
	)

	override val mutates: Boolean = false
	override val reversible: Boolean = true

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val args = SshArguments.normalize(arguments)
		val connection = when (val parsed = SshArguments.parse(name, args, readWorkspaceFile)) {
			is SshArguments.Parsed.Error -> return ToolResult.error(parsed.message)
			is SshArguments.Parsed.Ok -> parsed.value
		}

		return try {
			val result = transport.execute(
				host = connection.host,
				port = connection.port,
				username = connection.username,
				command = PROBE_SCRIPT,
				auth = connection.auth,
				hostKeyFingerprint = connection.hostKeyFingerprint,
				commandTimeoutMs = limits.commandTimeoutMs(null),
				maxOutputBytes = limits.outputBytes(null),
			)
			val body = if (result.stdout.isNotBlank()) {
				result.stdout
			} else {
				result.stderr.ifBlank { "(the probe returned no output)" }
			}
			ToolResult.ok(
				buildString {
					append("probe of ").append(connection.host).append("\n")
					append("exit: ").append(result.exitCode)
					if (result.truncated) append(" (output truncated)")
					append("\nhost key: ").append(result.hostFingerprint).append('\n')
					append(body)
				},
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: SshException) {
			ToolResult.error("ssh_probe: ${e.message}")
		} catch (e: Exception) {
			ToolResult.error("ssh_probe: ${e.message ?: e.javaClass.simpleName}")
		}
	}

	private companion object {
		/**
		 * No `$` variables: the string is a Kotlin raw literal, so a shell
		 * variable would either interpolate here or need escaping. Plain
		 * commands and fixed headings are easier to audit.
		 */
		val PROBE_SCRIPT: String = """
			set +e
			printf '\n== system ==\n'
			uname -srm 2>/dev/null || true
			printf '\n== os ==\n'
			sed -n '1,8p' /etc/os-release 2>/dev/null || echo unavailable
			printf '\n== uptime ==\n'
			uptime 2>/dev/null || echo unavailable
			printf '\n== load ==\n'
			cat /proc/loadavg 2>/dev/null || echo unavailable
			printf '\n== cpu ==\n'
			nproc 2>/dev/null || echo unavailable
			printf '\n== memory ==\n'
			free -h 2>/dev/null || echo unavailable
			printf '\n== disk ==\n'
			df -h / /tmp /var 2>/dev/null || df -h 2>/dev/null || echo unavailable
			printf '\n== inodes ==\n'
			df -i / /tmp /var 2>/dev/null || echo unavailable
			printf '\n== failed units ==\n'
			if command -v systemctl >/dev/null 2>&1; then
				systemctl --failed --no-pager 2>/dev/null | head -n 20
			else
				echo unavailable
			fi
			printf '\n== listening ==\n'
			if command -v ss >/dev/null 2>&1; then
				ss -lntup 2>/dev/null | head -n 30
			elif command -v netstat >/dev/null 2>&1; then
				netstat -lntup 2>/dev/null | head -n 30
			else
				echo unavailable
			fi
			printf '\n== containers ==\n'
			if command -v docker >/dev/null 2>&1; then
				docker ps --format '{{.Names}}	{{.Status}}	{{.Image}}' 2>/dev/null | head -n 20
			else
				echo unavailable
			fi
			printf '\n== logged in ==\n'
			who 2>/dev/null | head -n 10 || true
		""".trimIndent()
	}
}
