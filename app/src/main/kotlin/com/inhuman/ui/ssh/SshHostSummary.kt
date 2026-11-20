package com.inhuman.ui.ssh

import com.inhuman.core.ai.ssh.SshHost

/**
 * A saved host as the UI may hold it.
 *
 * No credential, because [SshHost] has none: the address book holds an alias,
 * an address, a port, a user and an optional fingerprint, and nothing else.
 */
data class SshHostSummary(
	val id: String,
	val name: String,
	val host: String,
	val port: Int,
	val username: String,
	val hostKeyFingerprint: String?,
) {
	/** `user@host`, with the port only when it is not the default. */
	val summary: String
		get() = "$username@$host" + if (port == 22) "" else ":$port"

	val label: String
		get() = if (name.isBlank()) summary else "$name ($summary)"
}

fun SshHost.toSummary(): SshHostSummary = SshHostSummary(
	id = id,
	name = name,
	host = host,
	port = port,
	username = username,
	hostKeyFingerprint = hostKeyFingerprint,
)
