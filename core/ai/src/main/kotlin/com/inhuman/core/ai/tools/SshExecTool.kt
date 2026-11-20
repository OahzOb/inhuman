package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Run one command on a remote machine over SSH.
 *
 * ## What this tool does not do
 *
 * It does not know any hosts. The app has no remote-device configuration; the
 * user tells the model how to connect, or places a key/password file in the
 * conversation workspace and tells it the path. That is a deliberate product
 * decision: the app is an SSH transport, not an inventory of machines.
 *
 * ## Consent
 *
 * This tool reports `needsApproval = false`. The capability gate is separate
 * and stronger: `ChatViewModel` requires a once-per-conversation SSH
 * acknowledgement before the first call. After that, ordinary [ToolPolicy]
 * applies, which defaults to Free. The risk is remote, not local: the command
 * can destroy the machine at the other end.
 */
class SshExecTool(
	private val transport: SshTransport,
	/** User ceilings; the model can only ask for smaller values. */
	private val limits: SshLimits = SshLimits(),
	/** Reads a workspace-relative private-key file. */
	private val readWorkspaceFile: (String) -> ByteArray? = { null },
) : SshTool {

	override val name: String = "ssh_exec"

	override val description: String = """
		Run one non-interactive command on a remote machine over SSH.
		${SshArguments.connectionHelp()}
		There is no PTY: commands that wait for input will time out. There is no port forwarding.
		The command may run for at most ${limits.commandTimeoutCeilingSeconds}s; timeoutSeconds may lower that, never raise it. Output is capped at ${limits.outputCeilingBytes} bytes per stream; maxOutputBytes may lower it.
		The user is asked once per conversation, before the first SSH call, that the agent may run any command on the hosts selected for this conversation. After that, normal tool policy applies.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to SshArguments.connectionParameters() + mapOf(
			"command" to mapOf("type" to "string", "description" to "Command to run"),
			"timeoutSeconds" to mapOf(
				"type" to "integer",
				"description" to "Optional command timeout, at most ${limits.commandTimeoutCeilingSeconds}s; larger values are clamped",
			),
			"maxOutputBytes" to mapOf(
				"type" to "integer",
				"description" to "Optional stdout/stderr cap, at most ${limits.outputCeilingBytes} bytes; larger values are clamped",
			),
		),
		"required" to listOf("command"),
	)

	override val mutates: Boolean = true
	override val reversible: Boolean = false

	override suspend fun execute(arguments: JsonObject): ToolResult {
		// Some models wrap the whole argument object in a string field named
		// `arguments` (the shape of the API's own tool-call envelope) and use
		// `hostname` for `host`. Unwrap one level rather than refusing a call
		// whose payload is otherwise complete.
		val args = SshArguments.normalize(arguments)
		val command = args.string("command")?.trim().orEmpty()
		if (command.isBlank()) return ToolResult.error("ssh_exec: command is required")
		val connection = when (
			val parsed = SshArguments.parse(name, args, readWorkspaceFile)
		) {
			is SshArguments.Parsed.Error -> return ToolResult.error(parsed.message)
			is SshArguments.Parsed.Ok -> parsed.value
		}

		val timeoutRequest = SshArguments.int(args, "timeoutSeconds", 0).takeIf { it > 0 }
		val outputRequest = SshArguments.int(args, "maxOutputBytes", 0).takeIf { it > 0 }
		val timeoutMs = limits.commandTimeoutMs(timeoutRequest)
		val outputBytes = limits.outputBytes(outputRequest)
		val limitNotes = buildList {
			if (timeoutRequest != null && timeoutRequest * 1000 != timeoutMs) {
				add("timeout clamped to ${timeoutMs / 1000}s")
			}
			if (outputRequest != null && outputRequest != outputBytes) {
				add("output clamped to $outputBytes bytes")
			}
		}

		return try {
			val result = transport.execute(
				host = connection.host,
				port = connection.port,
				username = connection.username,
				command = command,
				auth = connection.auth,
				hostKeyFingerprint = connection.hostKeyFingerprint,
				connectTimeoutMs = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
				commandTimeoutMs = timeoutMs,
				maxOutputBytes = outputBytes,
			)
			ToolResult.ok(format(result, outputBytes, limitNotes))
		} catch (e: CancellationException) {
			throw e
		} catch (e: SshException) {
			ToolResult.error("ssh_exec: ${e.message}")
		} catch (e: Exception) {
			ToolResult.error("ssh_exec: ${e.message ?: e.javaClass.simpleName}")
		}
	}

	private fun format(
		result: com.inhuman.core.ai.ssh.SshResult,
		outputBytes: Int,
		limitNotes: List<String>,
	): String = buildString {
		if (limitNotes.isNotEmpty()) {
			append("limits: ").append(limitNotes.joinToString("; ")).append('\n')
		}
		append("exit code: ").append(result.exitCode).append('\n')
		append("host key: ").append(result.hostFingerprint).append('\n')
		append("stdout:\n").append(result.stdout.ifEmpty { "(empty)" }).append('\n')
		append("stderr:\n").append(result.stderr.ifEmpty { "(empty)" })
		if (result.truncated) {
			append("\n\n[output truncated at $outputBytes bytes per stream]")
		}
	}

	private fun JsonObject.string(name: String): String? {
		val primitive = this[name] as? JsonPrimitive ?: return null
		return if (primitive.isString) primitive.content else null
	}
}
