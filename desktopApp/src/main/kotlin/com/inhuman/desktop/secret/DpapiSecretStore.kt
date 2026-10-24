package com.inhuman.desktop.secret

import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.secret.SecretStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

/**
 * The desktop `SecretStore`: DPAPI-encrypted values in one file.
 *
 * Windows DPAPI is per user, so the ciphertext cannot be moved to another
 * account and still decrypt; that is the desktop analogue of the Android
 * Keystore's app-identity binding. It is at-rest protection, not a vault: code
 * running as this user can ask DPAPI to decrypt, exactly as code running as the
 * app can ask the Keystore.
 *
 * The whole map is encrypted rather than each value, so the key names do not
 * leak either. Values are Base64 inside `Properties` because that file format
 * is ISO-8859-1 and a key is arbitrary user text.
 *
 * A file that will not decrypt (different user, restored elsewhere, corrupt)
 * reads as empty and logs only the fact, never the content. The alternative --
 * throwing at start-up -- turns a recovered profile into an app that cannot
 * open.
 */
class DpapiSecretStore(
	private val file: File,
	private val crypto: DesktopSecretStore = defaultDesktopSecretStore(),
) : SecretStore {

	private val lock = Any()
	private val values = LinkedHashMap<String, String>()
	private var loaded = false

	override fun put(key: String, value: String) = synchronized(lock) {
		ensureLoaded()
		values[key] = value
		persist()
	}

	override fun get(key: String): String? = synchronized(lock) {
		ensureLoaded()
		values[key]
	}

	override fun remove(key: String) = synchronized(lock) {
		ensureLoaded()
		if (values.remove(key) != null) persist()
	}

	override fun has(key: String): Boolean = synchronized(lock) {
		ensureLoaded()
		values.containsKey(key)
	}

	override fun hasUsable(key: String): Boolean = !get(key).isNullOrBlank()

	private fun ensureLoaded() {
		if (loaded) return
		loaded = true
		if (!file.isFile) return
		runCatching {
			val plain = crypto.unprotect(file.readBytes())
			val properties = Properties()
			properties.load(ByteArrayInputStream(plain))
			for (name in properties.stringPropertyNames()) {
				val encoded = properties.getProperty(name)
				val decoded = runCatching {
					String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
				}.getOrNull() ?: continue
				values[name] = decoded
			}
		}.onFailure { failure ->
			// The file name and the failure class, not the ciphertext or values.
			Diag.w("secret", "could not read ${file.name}; starting empty", failure)
		}
	}

	private fun persist() {
		val properties = Properties()
		for ((key, value) in values) {
			properties.setProperty(
				key,
				Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)),
			)
		}
		val plain = ByteArrayOutputStream().use { stream ->
			properties.store(stream, null)
			stream.toByteArray()
		}
		val cipher = crypto.protect(plain)
		file.parentFile?.mkdirs()
		val temporary = File(file.parentFile, "${file.name}.tmp")
		temporary.writeBytes(cipher)
		val source = temporary.toPath()
		val target = file.toPath()
		try {
			Files.move(
				source,
				target,
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE,
			)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
		}
	}
}
