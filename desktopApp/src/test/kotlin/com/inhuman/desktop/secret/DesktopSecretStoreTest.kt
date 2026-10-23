package com.inhuman.desktop.secret

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale

class DesktopSecretStoreTest {

	@Test
	fun `DPAPI round-trips without storing the plaintext`() {
		assumeTrue(System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows"))
		val store = WindowsDpapiSecretStore()
		val plain = "sk-desktop-spike-secret".toByteArray()
		val cipher = store.protect(plain)

		assertFalse("ciphertext must not equal plaintext", cipher.contentEquals(plain))
		assertArrayEquals(plain, store.unprotect(cipher))
		assertTrue(cipher.isNotEmpty())
	}

	@Test
	fun `DPAPI rejects ciphertext from another user or machine`() {
		assumeTrue(System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows"))
		val store = WindowsDpapiSecretStore()
		val cipher = store.protect("a".toByteArray())
		// A corrupted blob is the closest local proxy for "wrong DPAPI scope".
		cipher[cipher.lastIndex] = (cipher.last().toInt() xor 0xFF).toByte()
		var failed = false
		try {
			store.unprotect(cipher)
		} catch (e: Exception) {
			failed = true
		}
		assertTrue("corrupted DPAPI blob should not decrypt", failed)
	}
}
