package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ssh.SshAuth
import com.inhuman.core.ai.ssh.SshProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The connection arguments shared by every SSH tool.
 *
 * `ssh_exec`, `ssh_upload` and `ssh_download` differ only in what they do once
 * connected; host, port, username and authentication must be read the same way
 * or the consent dialogue and the tool would disagree about what a call means.
 */
data class SshConnectionArgs(
	val host: String,
	val port: Int,
	val username: String,
	val auth: SshAuth,
	val hostKeyFingerprint: String?,
)

object SshArguments {

	sealed interface Parsed {
		data class Ok(val value: SshConnectionArgs) : Parsed
		data class Error(val message: String) : Parsed
	}

	/**
	 * Some models wrap the whole argument object in a string field named
	 * `arguments` and use `hostname` for `host`. Unwrap one level rather than
	 * refusing a call whose payload is otherwise complete.
	 */
	fun normalize(arguments: JsonObject): JsonObject {
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

	/**
	 * The first required field this call is missing, or null.
	 *
	 * The consent dialogue is the strongest warning the app shows, so it must
	 * not appear for a call the tool would reject as malformed before it ever
	 * touches the network. The legacy loop checked three tools and missed
	 * `ssh_write_file`; this checks every tool's documented required fields, in
	 * one place, against the same normalised object the tool will run with.
	 *
	 * Authentication is deliberately not checked here: `identityFile`,
	 * `privateKey` and `password` are alternatives, and the tool has to read
	 * the workspace key file to decide whether one of them works.
	 */
	fun missingRequired(
		toolName: String,
		arguments: JsonObject,
		savedProfile: () -> SshProfile? = { null },
	): String? {
		val args = normalize(arguments)
		val wantsSaved = boolean(args, "useSavedHost") || !args.stringOrNull("profile").isNullOrBlank()
		val saved = if (wantsSaved) savedProfile() else null
		if (wantsSaved && saved == null) return "a saved SSH host (none is configured)"
		if (args.stringOrNull("host").isNullOrBlank() && saved == null) return "host"
		if (args.stringOrNull("username").isNullOrBlank() && saved == null) return "username"
		return when (toolName) {
			"ssh_exec" -> "command".takeIf { args.stringOrNull(it).isNullOrBlank() }
			"ssh_probe" -> null
			"ssh_list", "ssh_read_file" ->
				"remotePath".takeIf { args.stringOrNull(it).isNullOrBlank() }
			"ssh_upload" -> missingPair(args, "localPath", "remotePath")
			"ssh_download" -> missingPair(args, "remotePath", "localPath")
			"ssh_write_file" -> when {
				args.stringOrNull("remotePath").isNullOrBlank() -> "remotePath"
				args.stringOrNull("content") == null -> "content"
				else -> null
			}
			else -> null
		}
	}

	private fun missingPair(args: JsonObject, first: String, second: String): String? {
		val missingFirst = args.stringOrNull(first).isNullOrBlank()
		val missingSecond = args.stringOrNull(second).isNullOrBlank()
		return when {
			missingFirst && missingSecond -> "$first and $second"
			missingFirst -> first
			missingSecond -> second
			else -> null
		}
	}

	/**
	 * Remove SSH credentials before the arguments are stored.
	 *
	 * The user may legitimately paste a password or a private key into the
	 * conversation; it has to reach the model and the host. It does not have to
	 * become a durable row, because a stored credential is one the user cannot
	 * revoke by rotating it at the other end. The returned object keeps host,
	 * port, user, command and `identityFile`, and drops only the three secret
	 * fields.
	 *
	 * Returns `{}` when the input will not parse. Falling back to the raw string
	 * is exactly where a secret would survive.
	 */
	fun redact(raw: String): String {
		if (raw.isBlank()) return raw
		return runCatching {
			val normalized = normalize(Json.parseToJsonElement(raw).jsonObject)
			val safe = normalized.toMutableMap()
			safe.remove("password")
			safe.remove("privateKey")
			safe.remove("passphrase")
			JsonObject(safe).toString()
		}.getOrElse { "{}" }
	}

	fun parse(
		toolName: String,
		arguments: JsonObject,
		readWorkspaceFile: (String) -> ByteArray?,
		savedProfile: () -> SshProfile? = { null },
	): Parsed {
		val args = normalize(arguments)
		val wantsSaved = boolean(args, "useSavedHost") || !args.stringOrNull("profile").isNullOrBlank()
		val saved = if (wantsSaved) savedProfile() else null
		if (wantsSaved && saved == null) {
			return Parsed.Error("$toolName: no saved SSH connection; add one in Settings")
		}
		val host = (args.stringOrNull("host") ?: saved?.host)?.trim().orEmpty()
		val username = (args.stringOrNull("username") ?: saved?.username)?.trim().orEmpty()
		if (host.isBlank()) return Parsed.Error("$toolName: host or a saved connection is required")
		if (username.isBlank()) {
			return Parsed.Error("$toolName: username or a saved connection is required")
		}
		if (host.any { it.isWhitespace() } || username.any { it.isWhitespace() }) {
			return Parsed.Error("$toolName: host and username cannot contain whitespace")
		}

		val port = args["port"]?.let { element ->
			val primitive = element as? JsonPrimitive
				?: return Parsed.Error("$toolName: port must be an integer")
			if (primitive.isString) {
				primitive.content.toIntOrNull()
					?: return Parsed.Error("$toolName: port must be an integer")
			} else {
				primitive.intOrNull
					?: return Parsed.Error("$toolName: port must be an integer")
			}
		} ?: saved?.port ?: 22
		if (port !in 1..65535) {
			return Parsed.Error("$toolName: port must be between 1 and 65535")
		}

		val identityFile = args.stringOrNull("identityFile")?.trim()?.takeIf { it.isNotEmpty() }
		val explicitPrivateKey = args.stringOrNull("privateKey")?.takeIf { it.isNotEmpty() }
		if (identityFile != null && explicitPrivateKey != null) {
			return Parsed.Error("$toolName: provide identityFile or privateKey, not both")
		}
		val rawPrivateKey = explicitPrivateKey
			?: if (identityFile == null) {
				saved?.privateKey?.takeIf { it.isNotEmpty() }
			} else {
				null
			}

		val privateKey = when {
			identityFile != null -> runCatching { readWorkspaceFile(identityFile) }
				.getOrElse { failure ->
					return Parsed.Error(
						"$toolName: could not read identityFile '$identityFile': ${failure.message}",
					)
				}
				?: return Parsed.Error("$toolName: identityFile not found: $identityFile")
			rawPrivateKey != null -> rawPrivateKey.toByteArray(Charsets.UTF_8)
			else -> null
		}
		val password = args.stringOrNull("password")
			?: if (privateKey == null) saved?.password else null
		if (privateKey == null && password == null) {
			return Parsed.Error(
				"$toolName: authentication is required: identityFile, privateKey, password, or a saved connection",
			)
		}

		return Parsed.Ok(
			SshConnectionArgs(
				host = host,
				port = port,
				username = username,
				auth = SshAuth(
					password = password,
					privateKey = privateKey,
					passphrase = (args.stringOrNull("passphrase") ?: saved?.passphrase)
						?.toByteArray(Charsets.UTF_8),
				),
				hostKeyFingerprint = (args.stringOrNull("hostKeyFingerprint") ?: saved?.hostKeyFingerprint)
					?.trim()
					?.takeIf { it.isNotEmpty() },
			),
		)
	}

	fun boolean(arguments: JsonObject, name: String): Boolean {
		val primitive = arguments[name] as? JsonPrimitive ?: return false
		return primitive.content == "true"
	}

	/** Trimmed string argument, or null when absent or not a string. */
	fun string(arguments: JsonObject, name: String): String? =
		arguments.stringOrNull(name)?.trim()

	/**
	 * String argument without trimming.
	 *
	 * File content must keep its leading/trailing whitespace and newlines; using
	 * [string] here silently wrote a config without its final newline.
	 */
	fun rawString(arguments: JsonObject, name: String): String? =
		arguments.stringOrNull(name)

	/** Integer argument, or [default] when absent or not a number. */
	fun int(arguments: JsonObject, name: String, default: Int = 0): Int =
		(arguments[name] as? JsonPrimitive)?.intOrNull ?: default

	/** The authentication block every SSH tool's schema repeats. */
	fun connectionParameters(): Map<String, Any?> = mapOf(
		"useSavedHost" to mapOf(
			"type" to "boolean",
			"description" to "Use the one connection saved in Settings; explicit host/username override it.",
		),
		"host" to mapOf("type" to "string", "description" to "Hostname or IP address"),
		"port" to mapOf("type" to "integer", "description" to "SSH port; default 22"),
		"username" to mapOf("type" to "string", "description" to "Remote user"),
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
		"passphrase" to mapOf(
			"type" to "string",
			"description" to "Passphrase for an encrypted private key",
		),
		"hostKeyFingerprint" to mapOf(
			"type" to "string",
			"description" to "Optional SHA256:... host-key fingerprint; first use is trust-on-first-use",
		),
	)

	private fun JsonObject.stringOrNull(name: String): String? {
		val primitive = this[name] as? JsonPrimitive ?: return null
		return if (primitive.isString) primitive.content else null
	}
}
