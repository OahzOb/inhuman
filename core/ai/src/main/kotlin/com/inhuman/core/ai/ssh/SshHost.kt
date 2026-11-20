package com.inhuman.core.ai.ssh

/**
 * One saved remote machine: the user's address book entry for it.
 *
 * ## What this is, and what it is deliberately not
 *
 * It holds an alias, an address, a port, a username and an optional pinned
 * host-key fingerprint. It holds **no credential**, and no tool resolves it into
 * a connection: the model is shown these entries by `ssh_hosts` and must name
 * the host and its authentication itself in a call. A tool that quietly turned
 * an alias into a stored password would make "which machines may the agent see"
 * a credential grant, which is exactly what this shape avoids.
 *
 * The list lives in the platform secret store rather than the database, so a
 * backup carries neither the addresses nor (there being none) any secret.
 */
data class SshHost(
	/** Stable identity; what a conversation's selection stores. */
	val id: String,
	/** What the user and the model call it. Blank falls back to `user@host`. */
	val name: String,
	val host: String,
	val port: Int = 22,
	val username: String,
	/** Optional pin; otherwise first use is trust-on-first-use. */
	val hostKeyFingerprint: String? = null,
) {
	/** `user@host`, with the port only when it is not the default. */
	val summary: String
		get() = "$username@$host" + if (port == 22) "" else ":$port"

	/** What the model is shown. Never a credential -- there is none to show. */
	val label: String
		get() = if (name.isBlank()) summary else "$name ($summary)"

	companion object {
		/**
		 * Resolve a name from a selection against the saved entries.
		 *
		 * Id first, then the name the model was shown. Used to decide **what to
		 * show** (`ssh_hosts`), never to decide what to connect to.
		 */
		fun resolve(hosts: List<SshHost>, reference: String): SshHost? {
			val wanted = reference.trim()
			if (wanted.isEmpty()) return null
			return hosts.firstOrNull { it.id == wanted }
				?: hosts.firstOrNull { it.name.isNotBlank() && it.name.equals(wanted, ignoreCase = true) }
		}

		/** One line per host, for the tool result. */
		fun roster(hosts: List<SshHost>): String =
			hosts.joinToString("\n") { "- ${it.label}" }
	}
}
