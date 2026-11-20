package com.inhuman.core.data.secret

/**
 * The keyed secret storage the data layer needs, without Android.
 *
 * `AgentRepository` reads and writes provider keys through this; it does not
 * care that Android stores them behind the Keystore. The interface exists so
 * the repository can live in a plain JVM module and be tested without a device:
 * Android passes its Keystore-backed `SecureStore`, and a test passes an
 * in-memory one with the same key names.
 *
 * [get] answers null for a value that exists but cannot be decrypted, rather
 * than throwing. That is what lets a key invalidated by a device-credential
 * change become "ask the user again" instead of an app that will not start.
 */
interface SecretStore {

	fun put(key: String, value: String)

	fun get(key: String): String?

	fun remove(key: String)

	fun has(key: String): Boolean

	/**
	 * Whether a secret is present, decryptable, **and non-empty**.
	 *
	 * A stored-but-empty value must not read as configured: the composer would
	 * enable, the turn would be refused, and nothing on screen would explain
	 * the disagreement.
	 */
	fun hasUsable(key: String): Boolean = !get(key).isNullOrBlank()

	companion object {
		/**
		 * The original DeepSeek key slot.
		 *
		 * Kept here because the repository needs it and the repository is
		 * platform-free. `SecureStore` re-exports the same constant so the app's
		 * existing call sites do not move.
		 */
		const val KEY_DEEPSEEK_API_KEY = "deepseek.api_key"
	}
}
