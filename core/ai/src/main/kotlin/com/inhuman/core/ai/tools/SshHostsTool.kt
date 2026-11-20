package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.ai.ssh.SshHost
import kotlinx.serialization.json.JsonObject

/**
 * The machines the user has shared with this conversation, as information.
 *
 * ## Why this is a tool and not a line in the system message
 *
 * The list is a note the user keeps about their own infrastructure, and the
 * per-conversation toggle decides what is visible. Putting it in the prompt
 * made every toggle rewrite the system prefix (a cold request) and made the
 * list look like part of the agent's instructions. As a tool it is fetched when
 * it is needed, it reflects the current selection at call time, and it reads
 * as what it is: information.
 *
 * ## What it cannot do
 *
 * It does not connect, does not read a credential (there are none stored), and
 * does not resolve anything a later tool call could use. The agent still has to
 * name the host and its authentication in `ssh_exec` and friends.
 */
class SshHostsTool(
	private val hosts: () -> List<SshHost>,
) : Tool {

	override val name: String = "ssh_hosts"

	override val description: String = """
		List the remote machines the user has shared with this conversation: an alias, a host, a port and a username.
		Read-only: it does not connect and it returns no credential.
		To use one, pass its host and username to an SSH tool with authentication you were given (an identityFile in the workspace, or a password).
		""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to emptyMap<String, Any?>(),
		"required" to emptyList<String>(),
	)

	override val mutates: Boolean = false
	override val reversible: Boolean = true

	override suspend fun execute(arguments: JsonObject): ToolResult {
		val shared = hosts()
		if (shared.isEmpty()) {
			return ToolResult.ok("No host is shared with this conversation.")
		}
		return ToolResult.ok(
			buildString {
				append("Hosts shared with this conversation (alias, user@host):\n")
				append(SshHost.roster(shared))
				append("\n\nNo credential is included. Ask the user for a key file or password if you need one.")
			},
		)
	}
}
