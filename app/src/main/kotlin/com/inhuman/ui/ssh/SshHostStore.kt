package com.inhuman.ui.ssh

import com.inhuman.core.ai.ssh.SshHost
import com.inhuman.core.data.secret.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The remote machines saved in Settings: an address book, not a credential
 * store.
 *
 * ## Why it holds no password or key
 *
 * A tool call must not turn an alias into a stored secret. That rule is only
 * true if there is no stored secret to turn it into: the agent connects with
 * what it was handed -- a key file in the workspace, or a password typed into
 * the conversation -- and this list is the note the user keeps about which
 * machines exist. `ssh_hosts` shows the selected entries to the model; nothing
 * resolves them into a connection.
 *
 * ## Why the secret store anyway
 *
 * The addresses are private, and the same contract that keeps API keys out of
 * a backup keeps them out of one: the whole list is one document under
 * `ssh.hosts`, absent from the database, a backup and the diagnostics log.
 *
 * The single host an earlier release saved (`ssh.saved_connection`) is
 * converted once on first read -- its address and user are kept, its password
 * and key are dropped -- and the old key removed.
 */
class SshHostStore(private val secure: SecretStore) {

	/** Every saved host, in the order the user sees them. */
	fun load(): List<SshHost> {
		val raw = secure.get(KEY) ?: return migrateLegacy()
		return runCatching { decode(raw) }.getOrDefault(emptyList())
	}

	fun save(hosts: List<SshHost>) {
		secure.put(KEY, encode(hosts))
	}

	/** Replace one entry by id, or append it. */
	fun upsert(host: SshHost) {
		val current = load()
		val replaced = current.map { if (it.id == host.id) host else it }
		save(if (current.any { it.id == host.id }) replaced else current + host)
	}

	fun remove(id: String) {
		save(load().filterNot { it.id == id })
	}

	fun find(id: String): SshHost? = load().firstOrNull { it.id == id }

	/** A fresh id; the name is not one, because renaming must not orphan a grant. */
	fun newId(): String = UUID.randomUUID().toString()

	private fun migrateLegacy(): List<SshHost> {
		val legacy = secure.get(LEGACY_KEY) ?: return emptyList()
		val host = runCatching {
			val root = Json.parseToJsonElement(legacy).jsonObject
			SshHost(
				id = newId(),
				// No name: the label falls back to user@host, which is what the
				// single-host release showed.
				name = "",
				host = root["host"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				port = root["port"]?.jsonPrimitive?.intOrNull ?: 22,
				username = root["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				hostKeyFingerprint = root["hostKeyFingerprint"]?.jsonPrimitive?.contentOrNull,
			)
		}.getOrNull()?.takeIf { it.host.isNotBlank() && it.username.isNotBlank() } ?: return emptyList()
		val hosts = listOf(host)
		save(hosts)
		// Removed only after the new document is written: a failure above must
		// leave the old connection readable rather than losing it. The password
		// and key in it are dropped, not carried over -- nothing may use them.
		secure.remove(LEGACY_KEY)
		return hosts
	}

	private fun encode(hosts: List<SshHost>): String = buildJsonObject {
		put(
			"hosts",
			buildJsonArray {
				hosts.forEach { host ->
					add(
						buildJsonObject {
							put("id", host.id)
							put("name", host.name)
							put("host", host.host)
							put("port", host.port)
							put("username", host.username)
							host.hostKeyFingerprint?.let { put("hostKeyFingerprint", it) }
						},
					)
				}
			},
		)
	}.toString()

	private fun decode(raw: String): List<SshHost> {
		val root = Json.parseToJsonElement(raw).jsonObject
		val array = root["hosts"] as? JsonArray ?: return emptyList()
		return array.mapNotNull { element ->
			val entry = element as? JsonObject ?: return@mapNotNull null
			val host = entry["host"]?.jsonPrimitive?.contentOrNull.orEmpty()
			val username = entry["username"]?.jsonPrimitive?.contentOrNull.orEmpty()
			if (host.isBlank() || username.isBlank()) return@mapNotNull null
			SshHost(
				id = entry["id"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { newId() },
				name = entry["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				host = host,
				port = entry["port"]?.jsonPrimitive?.intOrNull ?: 22,
				username = username,
				// A password or key written by an earlier release is ignored and
				// dropped on the next save: there is no field for it any more.
				hostKeyFingerprint = entry["hostKeyFingerprint"]?.jsonPrimitive?.contentOrNull,
			)
		}
	}

	private companion object {
		const val KEY = "ssh.hosts"

		/** The single-host document written by releases before 0.9.6. */
		const val LEGACY_KEY = "ssh.saved_connection"
	}
}
