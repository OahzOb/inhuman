package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshAuth
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

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
	/** Reads a workspace-relative private-key file. */
	private val readWorkspaceFile: (String) -> ByteArray? = { null },
) : Tool {

	override val name: String = "ssh_exec"

	override val description: String = """
		Run one non-interactive command on a remote machine over SSH.
		Use it only when the user has given you a host, a username and a way to authenticate. The app has no host inventory or credential store; SSH tool arguments are redacted before storage.
		Prefer identityFile (a workspace-relative private-key path the user placed there) over pasting a private key; a password is accepted only if the user gave you one.
		There is no PTY: commands that wait for input will time out. There is no port forwarding.
		The user is asked once per conversation, before the first SSH call, that the agent may run any command on any host it is given. After that, normal tool policy applies.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"host" to mapOf("type" to "string", "description" to "Hostname or IP address"),
			"port" to mapOf("type" to "integer", "description" to "SSH port; default 22"),
			"username" to mapOf("type" to "string", "description" to "Remote user"),
			"command" to mapOf("type" to "string", "description" to "Command to run"),
			"password" to mapOf(
				"type" to "string",
				"description" to "Password, only if the user gave one. It becomes part of the conversation.",
			),
			"privateKey" to mapOf(
				"type" to "string",
				"description" to "OpenSSH private key text. Prefer identityFile so the key never enters the conversation.",
			),
			"identityFile" to mapOf(
				"type" to "string",
				"description" to "Workspace-relative path to an OpenSSH private key file",
			),
			"passphrase" to mapOf("type" to "string", "description" to "Passphrase for an encrypted private key"),
			"hostKeyFingerprint" to mapOf(
				"type" to "string",
				"description" to "Optional SHA256:... host-key fingerprint; first use is trust-on-first-use",
			),
		),
		"required" to listOf("host", "username", "command"),
	)

	override val mutates: Boolean = true

	override suspend fun execute(arguments: JsonObject): ToolResult {
		// Some models wrap the whole argument object in a string field named
		// `arguments` (the shape of the API's own tool-call envelope) and use
		// `hostname` for `host`. Unwrap one level rather than refusing a call
		// whose payload is otherwise complete.
		val args = normalizeArguments(arguments)
		val host = args.string("host")?.trim().orEmpty()
		val username = args.string("username")?.trim().orEmpty()
		val command = args.string("command")?.trim().orEmpty()
		if (host.isBlank()) return ToolResult.error("ssh_exec: host is required")
		if (username.isBlank()) return ToolResult.error("ssh_exec: username is required")
		if (command.isBlank()) return ToolResult.error("ssh_exec: command is required")
		if (host.any { it.isWhitespace() } || username.any { it.isWhitespace() }) {
			return ToolResult.error("ssh_exec: host and username cannot contain whitespace")
		}

		val port = args["port"]?.let { element ->
			val primitive = element as? JsonPrimitive
				?: return ToolResult.error("ssh_exec: port must be an integer")
			if (primitive.isString) {
				primitive.content.toIntOrNull()
					?: return ToolResult.error("ssh_exec: port must be an integer")
			} else {
				primitive.intOrNull
					?: return ToolResult.error("ssh_exec: port must be an integer")
			}
		} ?: 22
		if (port !in 1..65535) return ToolResult.error("ssh_exec: port must be between 1 and 65535")

		val identityFile = args.string("identityFile")?.trim()?.takeIf { it.isNotEmpty() }
		val rawPrivateKey = args.string("privateKey")?.takeIf { it.isNotEmpty() }
		if (identityFile != null && rawPrivateKey != null) {
			return ToolResult.error("ssh_exec: provide identityFile or privateKey, not both")
		}

		val privateKey = when {
			identityFile != null -> runCatching { readWorkspaceFile(identityFile) }
				.getOrElse { failure ->
					return ToolResult.error(
						"ssh_exec: could not read identityFile '$identityFile': ${failure.message}",
					)
				}
				?: return ToolResult.error("ssh_exec: identityFile not found: $identityFile")
			rawPrivateKey != null -> rawPrivateKey.toByteArray(Charsets.UTF_8)
			else -> null
		}
		val password = args.string("password")
		if (privateKey == null && password == null) {
			return ToolResult.error(
				"ssh_exec: authentication is required: identityFile, privateKey or password",
			)
		}

		return try {
			val result = transport.execute(
				host = host,
				port = port,
				username = username,
				command = command,
				auth = SshAuth(
					password = password,
					privateKey = privateKey,
					passphrase = args.string("passphrase")?.toByteArray(Charsets.UTF_8),
				),
				hostKeyFingerprint = args.string("hostKeyFingerprint")?.trim()?.takeIf { it.isNotEmpty() },
				connectTimeoutMs = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
				commandTimeoutMs = SshTransport.DEFAULT_COMMAND_TIMEOUT_MS,
			)
			ToolResult.ok(format(result))
		} catch (e: CancellationException) {
			throw e
		} catch (e: SshException) {
			ToolResult.error("ssh_exec: ${e.message}")
		} catch (e: Exception) {
			ToolResult.error("ssh_exec: ${e.message ?: e.javaClass.simpleName}")
		}
	}

	private fun format(result: com.inhuman.core.ai.ssh.SshResult): String = buildString {
		append("exit code: ").append(result.exitCode).append('\n')
		append("host key: ").append(result.hostFingerprint).append('\n')
		append("stdout:\n").append(result.stdout.ifEmpty { "(empty)" }).append('\n')
		append("stderr:\n").append(result.stderr.ifEmpty { "(empty)" })
		if (result.truncated) {
			append("\n\n[output truncated at 20000 characters per stream]")
		}
	}

	private fun JsonObject.string(name: String): String? {
		val primitive = this[name] as? JsonPrimitive ?: return null
		return if (primitive.isString) primitive.content else null
	}

	companion object {
		/**
		 * The arguments as the tool reads them, before the consent dialogue or
		 * the approval dialogue looks at them.
		 *
		 * DeepSeek sometimes emits the function-call envelope itself as the
		 * arguments object: `{"arguments":"{\"host\":...}"}`. The tool already
		 * accepts that shape, but the UI was reading the raw object and showed
		 * a consent dialogue for an empty host. One normalisation used by both
		 * paths keeps what the user is shown and what runs the same.
		 */
		fun normalizeArguments(arguments: JsonObject): JsonObject {
			val unwrapped = (arguments["arguments"] as? JsonPrimitive)
				?.takeIf { it.isString }
				?.content
				?.let { nested ->
					runCatching { Json.parseToJsonElement(nested).jsonObject }.getOrNull()
				}
				?: arguments
			if (unwrapped["host"] == null) {
				val hostname = unwrapped.stringOrNull("hostname")
				if (hostname != null) {
					return JsonObject(unwrapped + ("host" to JsonPrimitive(hostname)))
				}
			}
			return unwrapped
		}

		private fun JsonObject.stringOrNull(name: String): String? {
			val primitive = this[name] as? JsonPrimitive ?: return null
			return if (primitive.isString) primitive.content else null
		}
	}
}
