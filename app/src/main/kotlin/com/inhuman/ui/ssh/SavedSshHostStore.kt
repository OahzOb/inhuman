package com.inhuman.ui.ssh

import com.inhuman.core.ai.ssh.SshProfile
import com.inhuman.core.data.secret.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The one SSH connection saved in Settings.
 *
 * It lives in [SecretStore], not Room: a backup must not carry a remote
 * credential, and the secret store already has that contract. It is convenience
 * only -- no security or privacy promise -- and the model sees the word
 * `saved`, never the password or key. A new save replaces the previous host;
 * this is deliberately not a fleet inventory.
 */
class SavedSshHostStore(private val secure: SecretStore) {

	fun load(): SshProfile? {
		val raw = secure.get(KEY) ?: return null
		return runCatching {
			val root = JSON.parseToJsonElement(raw).jsonObject
			SshProfile(
				host = root["host"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				port = root["port"]?.jsonPrimitive?.intOrNull ?: 22,
				username = root["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				password = root["password"]?.jsonPrimitive?.contentOrNull,
				privateKey = root["privateKey"]?.jsonPrimitive?.contentOrNull,
				passphrase = root["passphrase"]?.jsonPrimitive?.contentOrNull,
				hostKeyFingerprint = root["hostKeyFingerprint"]?.jsonPrimitive?.contentOrNull,
			).takeIf { it.host.isNotBlank() && it.username.isNotBlank() }
		}.getOrNull()
	}

	fun save(profile: SshProfile) {
		require(profile.host.isNotBlank()) { "host is required" }
		require(profile.username.isNotBlank()) { "username is required" }
		secure.put(
			KEY,
			buildJsonObject {
				put("host", profile.host)
				put("port", profile.port)
				put("username", profile.username)
				profile.password?.let { put("password", it) }
				profile.privateKey?.let { put("privateKey", it) }
				profile.passphrase?.let { put("passphrase", it) }
				profile.hostKeyFingerprint?.let { put("hostKeyFingerprint", it) }
			}.toString(),
		)
	}

	fun clear() {
		secure.remove(KEY)
	}

	private companion object {
		const val KEY = "ssh.saved_connection"
		val JSON = Json { ignoreUnknownKeys = true }
	}
}