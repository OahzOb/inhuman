package com.inhuman.desktop.secret

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Locale

/**
 * A desktop secret store, split by OS.
 *
 * Windows uses DPAPI through JNA's `Crypt32Util`; Linux is intentionally not
 * faked here. A plain AES key next to the database would look encrypted and
 * protect nothing, so the Linux branch must be a real Secret Service binding
 * before it is wired into the app.
 *
 * This spike code is not yet connected to `SecureStore`: the Android Keystore
 * API is Android-only and the desktop path has to be a separate implementation
 * with the same key names.
 */
interface DesktopSecretStore {
	fun protect(plain: ByteArray): ByteArray
	fun unprotect(cipher: ByteArray): ByteArray
}

class WindowsDpapiSecretStore : DesktopSecretStore {
	override fun protect(plain: ByteArray): ByteArray =
		Crypt32Util.cryptProtectData(plain)

	override fun unprotect(cipher: ByteArray): ByteArray =
		Crypt32Util.cryptUnprotectData(cipher)
}

class UnsupportedDesktopSecretStore(private val reason: String) : DesktopSecretStore {
	override fun protect(plain: ByteArray): ByteArray = throw UnsupportedOperationException(reason)
	override fun unprotect(cipher: ByteArray): ByteArray = throw UnsupportedOperationException(reason)
}

fun defaultDesktopSecretStore(): DesktopSecretStore {
	val os = System.getProperty("os.name").lowercase(Locale.ROOT)
	return if (os.contains("windows")) {
		WindowsDpapiSecretStore()
	} else {
		UnsupportedDesktopSecretStore(
			"Linux secret storage needs a Secret Service (libsecret) binding; " +
				"DPAPI is Windows-only and the app must not fall back to plaintext.",
		)
	}
}
