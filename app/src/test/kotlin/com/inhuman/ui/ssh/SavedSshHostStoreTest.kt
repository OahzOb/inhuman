package com.inhuman.ui.ssh

import com.inhuman.core.ai.ssh.SshProfile
import com.inhuman.core.data.secret.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved host is one encrypted value, not a database table.
 *
 * These pin the parts that would otherwise be discovered by a failed SSH call:
 * the secret fields round-trip, a save replaces rather than duplicates, forget
 * really removes it, and a blank host/user is refused before it can become an
 * unusable half-profile.
 */
class SavedSshHostStoreTest {

	private class MapSecret : SecretStore {
		val values = mutableMapOf<String, String>()
		override fun put(key: String, value: String) { values[key] = value }
		override fun get(key: String): String? = values[key]
		override fun remove(key: String) { values.remove(key) }
		override fun has(key: String): Boolean = values[key] != null
	}

	@Test
	fun aProfileRoundTripsEverySecret() {
		val secret = MapSecret()
		val store = SavedSshHostStore(secret)
		val profile = SshProfile(
			host = "example.test",
			port = 2222,
			username = "deploy",
			password = "pw",
			privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----",
			passphrase = "phrase",
			hostKeyFingerprint = "SHA256:abc",
		)

		store.save(profile)

		assertEquals(profile, store.load())
		assertTrue(secret.values.containsKey("ssh.saved_connection"))
	}

	@Test
	fun saveReplacesAndForgetRemoves() {
		val secret = MapSecret()
		val store = SavedSshHostStore(secret)

		store.save(SshProfile("one.test", username = "a", password = "p"))
		store.save(SshProfile("two.test", username = "b", password = "q"))

		assertEquals("two.test", store.load()?.host)
		store.clear()
		assertNull(store.load())
	}

	@Test
	fun aBlankProfileIsRefused() {
		val store = SavedSshHostStore(MapSecret())
		val failure = runCatching {
			store.save(SshProfile("", username = "u", password = "p"))
		}
		assertTrue(failure.isFailure)
	}
}