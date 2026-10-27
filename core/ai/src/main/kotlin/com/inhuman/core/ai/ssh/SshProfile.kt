package com.inhuman.core.ai.ssh

/**
 * A saved SSH endpoint, used only when a tool call names `profile = "saved"`.
 *
 * The model receives the name, never the secret: [password], [privateKey] and
 * [passphrase] are resolved on the host and redacted from the transcript like
 * inline credentials. This is convenience, not a security promise -- a device
 * that is compromised can use the saved connection just as the app can.
 */
data class SshProfile(
	val host: String,
	val port: Int = 22,
	val username: String,
	val password: String? = null,
	val privateKey: String? = null,
	val passphrase: String? = null,
	val hostKeyFingerprint: String? = null,
) {
	val hasAuthentication: Boolean
		get() = !password.isNullOrBlank() || !privateKey.isNullOrBlank()

	val summary: String
		get() = "$username@$host" + if (port == 22) "" else ":$port"
}