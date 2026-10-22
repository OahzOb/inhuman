package com.inhuman.core.ai.ssh

import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The SFTP half of [SshClient], against MINA SSHD rather than a fake.
 *
 * The exec path already proves the handshake and TOFU work; what these tests
 * add is that the chunked [net.schmizz.sshj.sftp.RemoteFile] reads and writes
 * are correct for binary content and that the size and overwrite refusals
 * happen before a partial file can land.
 */
class SshFileClientTest {

	private lateinit var server: SshServer
	private lateinit var dir: Path
	private lateinit var root: Path
	private lateinit var knownHosts: Path

	@Before
	fun setUp() {
		dir = Files.createTempDirectory("inhuman-sftp-test")
		root = Files.createDirectories(dir.resolve("root"))
		knownHosts = dir.resolve("known_hosts")
		val hostKey = SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser")).apply {
			algorithm = "RSA"
			keySize = 2048
		}
		server = SshServer.setUpDefaultServer().apply {
			host = "127.0.0.1"
			port = 0
			keyPairProvider = hostKey
			fileSystemFactory = VirtualFileSystemFactory(root)
			subsystemFactories = listOf(SftpSubsystemFactory())
			passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
				user == "tester" && password == "secret"
			}
		}
		server.start()
	}

	@After
	fun tearDown() {
		runCatching { server.stop(true) }
		dir.toFile().deleteRecursively()
	}

	@Test(timeout = 30000)
	fun `upload then download round-trips binary bytes`() = runBlocking {
		val client = SshClient(knownHosts.toFile())
		val bytes = ByteArray(70_000) { (it % 251).toByte() }
		Files.createDirectories(root.resolve("nested"))
		val uploaded = client.upload(
			host = "127.0.0.1",
			port = server.port,
			username = "tester",
			remotePath = "nested/data.bin",
			content = bytes,
			auth = SshAuth(password = "secret"),
		)

		assertEquals(bytes.size.toLong(), uploaded.bytes)
		assertTrue(uploaded.hostFingerprint.startsWith("SHA256:"))
		assertArrayEquals(bytes, Files.readAllBytes(root.resolve("nested/data.bin")))

		val downloaded = client.download(
			host = "127.0.0.1",
			port = server.port,
			username = "tester",
			remotePath = "nested/data.bin",
			auth = SshAuth(password = "secret"),
			maxBytes = 1_000_000,
		)
		assertArrayEquals(bytes, downloaded.bytes)
	}

	@Test(timeout = 30000)
	fun `an existing remote file is refused unless overwrite is true`() = runBlocking {
		val client = SshClient(knownHosts.toFile())
		Files.createDirectories(root.resolve("keep"))
		Files.writeString(root.resolve("keep/file.txt"), "first")
		val auth = SshAuth(password = "secret")

		try {
			client.upload(
				"127.0.0.1", server.port, "tester", "keep/file.txt",
				"second".toByteArray(), auth,
			)
			fail("expected the existing file to be refused")
		} catch (e: SshException) {
			assertTrue(e.message.orEmpty().contains("exists"))
		}
		assertEquals("first", Files.readString(root.resolve("keep/file.txt")))

		client.upload(
			"127.0.0.1", server.port, "tester", "keep/file.txt",
			"second".toByteArray(), auth, overwrite = true,
		)
		assertEquals("second", Files.readString(root.resolve("keep/file.txt")))
	}

	@Test(timeout = 30000)
	fun `a download larger than the limit is refused before reading`() = runBlocking {
		Files.writeString(root.resolve("big.txt"), "0123456789")
		try {
			SshClient(knownHosts.toFile()).download(
				host = "127.0.0.1",
				port = server.port,
				username = "tester",
				remotePath = "big.txt",
				auth = SshAuth(password = "secret"),
				maxBytes = 5,
			)
			fail("expected the size limit to refuse the file")
		} catch (e: SshException) {
			assertTrue(e.message.orEmpty().contains("limit"))
		}
	}

	@Test(timeout = 30000)
	fun `a missing remote file is an error, not an empty download`() = runBlocking {
		try {
			SshClient(knownHosts.toFile()).download(
				host = "127.0.0.1",
				port = server.port,
				username = "tester",
				remotePath = "does-not-exist.txt",
				auth = SshAuth(password = "secret"),
				maxBytes = 1024,
			)
			fail("expected a missing file to fail")
		} catch (e: SshException) {
			assertTrue(e.message.orEmpty().contains("not found"))
		}
	}
}
