package com.inhuman.core.data.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.inhuman.core.common.diag.Diag
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores secrets encrypted with a key held in the Android Keystore.
 *
 * ## Why not `EncryptedSharedPreferences`
 *
 * `androidx.security:security-crypto` is the obvious choice and it is in
 * perpetual alpha, with the library's own documentation warning against
 * building on it. This is roughly a hundred lines and has no such caveat, so
 * the dependency is not worth taking.
 *
 * ## What this actually protects against
 *
 * The Keystore key is generated inside the secure element or TEE where present,
 * so **the key material never enters the app's memory or its files**. An
 * attacker who reads the app's data directory -- through a backup, an
 * `adb backup`, a filesystem image, or a rooted device -- finds ciphertext and
 * no key.
 *
 * What it does **not** protect against: code running as this app on a live,
 * unlocked device. Such code can ask the Keystore to decrypt, because the
 * Keystore authorises by app identity, not by intent. That is a real limit and
 * the honest reason this is described as protection for data at rest rather
 * than a vault.
 *
 * ## What is stored
 *
 * `prefs` is a plain `SharedPreferences` and holds only `iv:ciphertext`, both
 * Base64. Nothing about the scheme is secret except the Keystore key.
 */
class SecureStore(
	context: Context,
	private val prefsName: String = "inhuman.secure",
) {

	private val prefs = context.applicationContext
		.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

	/**
	 * The Keystore key, held once per process.
	 *
	 * ## Why this is cached
	 *
	 * [secretKey] used to do `KeyStore.load(null)` and `getEntry` on **every**
	 * access, and an access happens on the main thread at the points a user
	 * actually waits on: opening a chat screen (`refreshConfiguration` asks
	 * twice), and pressing Send, where the key is read before the spinner
	 * appears. Each of those is a round trip into the keystore daemon.
	 *
	 * Holding the `SecretKey` handle does not weaken the reason the key can
	 * become unusable. The ciphertext is still decrypted on every call, so a
	 * key that has been invalidated -- device credential removed, app data
	 * restored onto another device -- still fails inside `doFinal`, and [get]
	 * still answers null so the caller can prompt again. What is cached is the
	 * handle, not any plaintext.
	 */
	@Volatile
	private var cachedKey: SecretKey? = null

	/** The key, generated on first use and reused after. */
	private fun secretKey(): SecretKey {
		cachedKey?.let { return it }
		return synchronized(this) {
			cachedKey ?: loadOrCreateKey().also { cachedKey = it }
		}
	}

	private fun loadOrCreateKey(): SecretKey {
		val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
		(keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
			?.let { return it.secretKey }

		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
		generator.init(
			KeyGenParameterSpec.Builder(
				KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				// No setUserAuthenticationRequired: requiring a credential to
				// read an API key would mean a prompt every time the app
				// foregrounds, which is the wrong trade for this app. It also
				// means the key survives a screen lock, which is what a user
				// expects from "I entered it once".
				.build(),
		)
		return generator.generateKey()
	}

	fun put(key: String, value: String) {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, secretKey())

		val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
		// The IV is generated per encryption and must be stored alongside: GCM
		// is catastrophically broken by IV reuse under one key, so it can never
		// be a constant.
		val encoded = b64(cipher.iv) + ":" + b64(ciphertext)
		prefs.edit().putString(key, encoded).apply()
	}

	fun get(key: String): String? {
		val stored = prefs.getString(key, null) ?: return null
		val parts = stored.split(':', limit = 2)
		if (parts.size != 2) return null

		return try {
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(
				Cipher.DECRYPT_MODE,
				secretKey(),
				GCMParameterSpec(TAG_BITS, unb64(parts[0])),
			)
			String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
		} catch (e: Exception) {
			// A value that will not decrypt is a value we cannot use: the key
			// was invalidated (device credential removed, app data restored to
			// a different device) or the blob is corrupt. Returning null lets
			// the caller re-prompt, which is the only recovery there is. The
			// alternative -- throwing -- turns a recovered-from backup into an
			// app that will not start.
			//
			// Logged because the visible symptom is "the app forgot my key",
			// which is otherwise indistinguishable from never having saved it.
			// The value is not logged; only the name of the entry.
			Diag.w("secret", "cannot decrypt stored entry '$key'", e)
			null
		}
	}

	fun remove(key: String) {
		prefs.edit().remove(key).apply()
	}

	fun has(key: String): Boolean = prefs.contains(key)

	/**
	 * Whether a secret is present, decryptable, **and non-empty**.
	 *
	 * The name used to be the only thing accurate about it: it was
	 * `get(key) != null`, so a stored-but-empty value read as configured while
	 * `send()` rejected it -- the composer enabled, the turn refused, and
	 * nothing on screen explaining the disagreement.
	 *
	 * The settings screen cannot produce that state (`SettingsViewModel.save()`
	 * removes the key when the field is blank), so nothing in the app reaches it
	 * today. It is fixed anyway because `get` is public and the invariant it
	 * encodes is the one the callers already assume: `ChatViewModel` uses this
	 * answer to decide whether the composer is usable.
	 */
	fun hasUsable(key: String): Boolean = !get(key).isNullOrBlank()

	private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

	private fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

	companion object {
		private const val ANDROID_KEYSTORE = "AndroidKeyStore"
		private const val KEY_ALIAS = "inhuman.secret.v1"
		private const val TRANSFORMATION = "AES/GCM/NoPadding"
		private const val TAG_BITS = 128

		/** Preference key for the DeepSeek API key. */
		const val KEY_DEEPSEEK_API_KEY = "deepseek.api_key"

		/** Search/fetch credentials, deliberately independent of the chat key. */
		const val KEY_WEB_DEEPSEEK_API_KEY = "web.deepseek.api_key"
		const val KEY_WEB_EXA_API_KEY = "web.exa.api_key"
		const val KEY_WEB_SEARCH_BACKEND = "web.search_backend"
		const val KEY_WEB_FETCH_BACKEND = "web.fetch_backend"

		/**
		 * Preference key for demo mode.
		 *
		 * Not a secret, but it lives here because it appears nowhere else and
		 * giving it its own storage layer would be worse than the mild
		 * misnomer.
		 */
		const val KEY_DEMO_MODE = "demo.mode"

		/**
		 * Whether the SSH tool is available at all.
		 *
		 * Default off. This is the first of two gates: the assistant still has
		 * to opt in, and the first call in each conversation asks the user.
		 */
		const val KEY_SSH_TOOL_ENABLED = "ssh.tool_enabled"

		/**
		 * How many past turns' checkpoints a conversation keeps.
		 *
		 * Minimum 1: the current turn needs its own start checkpoint before a
		 * local mutation. The setting controls the history, not whether the
		 * current turn is safe to undo.
		 */
		const val KEY_SNAPSHOT_TURNS = "rollback.snapshot_turns"

		/**
		 * How many tool-call rounds one turn may run.
		 *
		 * A loop budget, not a capability: every tool still goes through the
		 * assistant's selection and the approval policy. Stored globally
		 * because the alternative -- another conversation column and a schema
		 * migration -- buys little for a setting a user changes rarely.
		 */
		const val KEY_TOOL_ROUNDS = "tools.max_rounds"
		const val KEY_TOOL_CALLS = "tools.max_calls"
		const val KEY_LAYOUT_PREFERENCE = "layout.preference"
	}
}
