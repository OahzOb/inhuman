package com.inhuman.ui.ssh

import com.inhuman.core.ai.ssh.SshHost
import com.inhuman.core.data.secret.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved hosts are one encrypted address book, not a database table and not
 * a credential store.
 *
 * These pin the parts that would otherwise be found by a failed SSH call: the
 * entries round-trip, editing one does not disturb the others, deleting one
 * really removes it, the single host an earlier release could save is converted
 * once -- **without its password or key** -- and a blank field never becomes an
 * unusable half-entry.
 */
class SshHostStoreTest {

	private class MapSecret : SecretStore {
		val values = mutableMapOf<String, String>()
		override fun put(key: String, value: String) { values[key] = value }
		override fun get(key: String): String? = values[key]
		override fun remove(key: String) { values.remove(key) }
		override fun has(key: String): Boolean = values[key] != null
	}

	private fun host(
		id: String,
		name: String = id,
		host: String = "$id.test",
		username: String = "deploy",
	) = SshHost(
		id = id,
		name = name,
		host = host,
		port = 2222,
		username = username,
		hostKeyFingerprint = "SHA256:abc",
	)

	@Test
	fun `hosts round-trip and keep their order`() {
		val secret = MapSecret()
		val store = SshHostStore(secret)
		val hosts = listOf(host("a"), host("b", name = "prod"))

		store.save(hosts)

		assertEquals(hosts, store.load())
		// One value, so a backup or a log that lists keys learns nothing about
		// how many machines exist.
		assertTrue(secret.values.containsKey("ssh.hosts"))
		assertEquals(1, secret.values.size)
	}

	@Test
	fun `upsert replaces by id and appends a new host`() {
		val store = SshHostStore(MapSecret())
		store.save(listOf(host("a"), host("b")))

		store.upsert(host("a", host = "moved.test"))
		assertEquals("moved.test", store.find("a")?.host)
		assertEquals(2, store.load().size)

		store.upsert(host("c"))
		assertEquals(listOf("a", "b", "c"), store.load().map { it.id })
	}

	@Test
	fun `remove deletes only the named host`() {
		val store = SshHostStore(MapSecret())
		store.save(listOf(host("a"), host("b")))

		store.remove("a")

		assertEquals(listOf("b"), store.load().map { it.id })
		assertNull(store.find("a"))
	}

	@Test
	fun `the single host of an earlier release keeps its address and drops its secrets`() {
		val secret = MapSecret()
		secret.put(
			"ssh.saved_connection",
			"""{"host":"old.test","port":22,"username":"root","password":"pw","privateKey":"KEY","passphrase":"phrase","hostKeyFingerprint":"SHA256:old"}""",
		)
		val store = SshHostStore(secret)

		val migrated = store.load()

		assertEquals(1, migrated.size)
		val host = migrated.single()
		assertEquals("old.test", host.host)
		assertEquals("root", host.username)
		assertEquals("SHA256:old", host.hostKeyFingerprint)
		// The converted entry has no credential to carry: the type has no field
		// for one, and the old document is removed rather than left readable.
		assertFalse(secret.values.containsKey("ssh.saved_connection"))
		assertFalse(secret.values.values.any { it.contains("\"pw\"") || it.contains("KEY") })
		assertEquals(migrated, store.load())
	}

	@Test
	fun `a credential written by an earlier release is ignored on read`() {
		val secret = MapSecret()
		secret.put(
			"ssh.hosts",
			"""{"hosts":[{"id":"a","name":"prod","host":"a.test","port":22,"username":"deploy","password":"pw","privateKey":"KEY"}]}""",
		)

		val host = SshHostStore(secret).load().single()

		assertEquals("a.test", host.host)
		// Re-saving drops the old fields: there is nothing to carry them.
		SshHostStore(secret).save(listOf(host))
		assertFalse(secret.values.getValue("ssh.hosts").contains("pw"))
		assertFalse(secret.values.getValue("ssh.hosts").contains("KEY"))
	}

	@Test
	fun `an entry without an address or a user is dropped on read`() {
		val secret = MapSecret()
		secret.put(
			"ssh.hosts",
			"""{"hosts":[{"id":"a","host":"","username":"u"},{"id":"b","host":"b.test","username":""},{"id":"c","host":"c.test","username":"u"}]}""",
		)

		assertEquals(listOf("c"), SshHostStore(secret).load().map { it.id })
	}

	@Test
	fun `a host keeps a blank name and falls back to user at host`() {
		val store = SshHostStore(MapSecret())
		val unnamed = host("a", name = "")

		store.save(listOf(unnamed))

		val loaded = store.load().single()
		assertEquals("", loaded.name)
		assertEquals("deploy@a.test:2222", loaded.summary)
		assertEquals("deploy@a.test:2222", loaded.label)
	}
}
