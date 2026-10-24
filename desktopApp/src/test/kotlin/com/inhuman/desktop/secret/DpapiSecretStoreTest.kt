package com.inhuman.desktop.secret

import com.inhuman.core.data.secret.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * DPAPI round-trips, and the failure that would otherwise lose the app's keys.
 *
 * Windows-only by nature; the assumption keeps the suite honest on a machine
 * without DPAPI rather than passing a test that never ran.
 */
class DpapiSecretStoreTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Before
	fun windowsOnly() {
		assumeTrue(
			"DPAPI is Windows-only",
			System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows"),
		)
	}

	@Test
	fun `values survive a reopen and never appear in the file`() {
		val file = File(tmp.newFolder("secrets"), "secrets.bin")
		DpapiSecretStore(file).apply {
			put(SecretStore.KEY_DEEPSEEK_API_KEY, "sk-a-real-looking-secret")
			put("web.exa.api_key", "exa-secret")
		}

		val reopened = DpapiSecretStore(file)
		assertEquals("sk-a-real-looking-secret", reopened.get(SecretStore.KEY_DEEPSEEK_API_KEY))
		assertEquals("exa-secret", reopened.get("web.exa.api_key"))
		assertTrue(reopened.hasUsable(SecretStore.KEY_DEEPSEEK_API_KEY))

		val raw = file.readBytes().toString(Charsets.ISO_8859_1)
		assertFalse("the ciphertext must not contain the value", raw.contains("sk-a-real-looking-secret"))
		assertFalse("the ciphertext must not contain the key name", raw.contains("web.exa.api_key"))

		reopened.remove(SecretStore.KEY_DEEPSEEK_API_KEY)
		assertNull(DpapiSecretStore(file).get(SecretStore.KEY_DEEPSEEK_API_KEY))
	}

	@Test
	fun `a corrupt file reads as empty and can be replaced`() {
		val file = File(tmp.newFolder("corrupt"), "secrets.bin")
		file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

		val store = DpapiSecretStore(file)
		assertNull("a file that will not decrypt is not a crash", store.get("anything"))
		assertFalse(store.hasUsable("anything"))

		// The app must still be able to save a new key over the unreadable file.
		store.put("new.key", "new-value")
		assertEquals("new-value", DpapiSecretStore(file).get("new.key"))
	}
}
