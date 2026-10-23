package com.inhuman.core.ai.ssh

import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The SSH client, over a real SSH server rather than a mock.
 *
 * Apache MINA SSHD is test-only: a fake session would prove the code calls the
 * methods it calls, which is not the question. What needs proving is that sshj
 * and the server agree on authentication, host keys and an exec channel.
 */
class SshClientTest {

	private lateinit var server: SshServer
	private lateinit var dir: Path
	private lateinit var knownHosts: Path

	@Before
	fun setUp() {
		dir = Files.createTempDirectory("inhuman-ssh-test")
		knownHosts = dir.resolve("known_hosts")
		val hostKey = SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser")).apply {
			algorithm = "RSA"
			keySize = 2048
		}
		server = SshServer.setUpDefaultServer().apply {
			host = "127.0.0.1"
			port = 0
			keyPairProvider = hostKey
			passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
				user == "tester" && password == "secret"
			}
			publickeyAuthenticator = PublickeyAuthenticator { user, _, _ -> user == "tester" }
			commandFactory = CommandFactory { _, command -> EchoCommand(command) }
		}
		server.start()
	}

	@After
	fun tearDown() {
		runCatching { server.stop(true) }
		dir.toFile().deleteRecursively()
	}

	@Test(timeout = 30000)
	fun `runs a command and reports output and exit status`() = runBlocking {
		val result = SshClient(knownHosts.toFile()).execute(
			host = "127.0.0.1",
			port = server.port,
			username = "tester",
			command = "hello",
			auth = SshAuth(password = "secret"),
		)

		assertEquals("ran:hello\n", result.stdout)
		assertEquals("", result.stderr)
		assertEquals(7, result.exitCode)
		assertTrue(result.hostFingerprint.startsWith("SHA256:"))
		assertTrue(Files.readString(knownHosts).contains("127.0.0.1"))
	}

	@Test(timeout = 30000)
	fun `the output cap is applied per stream`() = runBlocking {
		val result = SshClient(knownHosts.toFile()).execute(
			host = "127.0.0.1",
			port = server.port,
			username = "tester",
			command = "abcdefghijklmnopqrstuvwxyz",
			auth = SshAuth(password = "secret"),
			maxOutputBytes = 8,
		)
		assertTrue("stdout should be capped: ${result.stdout}", result.stdout.length <= 8)
		assertTrue(result.truncated)
	}

	@Test(timeout = 30000)
	fun `a second connection accepts the recorded host key`() = runBlocking {
		val client = SshClient(knownHosts.toFile())
		client.execute("127.0.0.1", server.port, "tester", "one", SshAuth(password = "secret"))
		val second = client.execute(
			"127.0.0.1",
			server.port,
			"tester",
			"two",
			SshAuth(password = "secret"),
		)
		assertEquals("ran:two\n", second.stdout)
	}

	@Test(timeout = 30000)
	fun `a changed host key fails closed`() = runBlocking {
		val client = SshClient(knownHosts.toFile())
		client.execute("127.0.0.1", server.port, "tester", "one", SshAuth(password = "secret"))
		val stored = Files.readString(knownHosts)
		val storedHost = stored.lineSequence().first().trim().substringBefore(' ')
		Files.writeString(knownHosts, "$storedHost SHA256:AAAA\n")

		try {
			client.execute("127.0.0.1", server.port, "tester", "two", SshAuth(password = "secret"))
			fail("expected a host-key failure")
		} catch (e: SshException) {
			assertTrue(
				e.message.orEmpty().contains("host key", ignoreCase = true) ||
					e.message.orEmpty().contains("HOST_KEY", ignoreCase = true) ||
					e.message.orEmpty().contains("verify", ignoreCase = true),
			)
		}
	}

	@Test(timeout = 30000)
	fun `an explicit fingerprint trusts a first connection`() = runBlocking {
		val first = SshClient(knownHosts.toFile()).execute(
			"127.0.0.1",
			server.port,
			"tester",
			"one",
			SshAuth(password = "secret"),
		)
		Files.delete(knownHosts)

		val second = SshClient(knownHosts.toFile()).execute(
			"127.0.0.1",
			server.port,
			"tester",
			"two",
			SshAuth(password = "secret"),
			hostKeyFingerprint = first.hostFingerprint,
		)
		assertEquals("ran:two\n", second.stdout)
	}

	@Test(timeout = 30000)
	fun `a wrong password is an error, not an empty success`() = runBlocking {
		try {
			SshClient(knownHosts.toFile()).execute(
				"127.0.0.1",
				server.port,
				"tester",
				"hello",
				SshAuth(password = "wrong"),
			)
			fail("expected authentication to fail")
		} catch (e: SshException) {
			assertTrue(e.message.orEmpty().isNotBlank())
		}
	}

	@Test(timeout = 30000)
	fun `an OpenSSH ed25519 key also authenticates`() = runBlocking {
		val key = javaClass.getResourceAsStream("/ssh/ed25519_test_key")!!.use { it.readBytes() }
		val result = SshClient(knownHosts.toFile()).execute(
			host = "127.0.0.1",
			port = server.port,
			username = "tester",
			command = "ed25519",
			auth = SshAuth(privateKey = key),
		)
		assertEquals("ran:ed25519\n", result.stdout)
	}

	@Test(timeout = 30000)
	fun `a command that outlives the timeout is stopped`() = runBlocking {
		try {
			SshClient(knownHosts.toFile()).execute(
				host = "127.0.0.1",
				port = server.port,
				username = "tester",
				command = "sleep",
				auth = SshAuth(password = "secret"),
				commandTimeoutMs = 200,
			)
			fail("expected a timeout")
		} catch (e: SshException) {
			assertTrue(e.message.orEmpty().contains("timed out", ignoreCase = true) ||
				e.message.orEmpty().contains("timeout", ignoreCase = true))
		}
	}

	@Test(timeout = 30000)
	fun `output over the cap is truncated`() = runBlocking {
		val result = SshClient(knownHosts.toFile()).execute(
			"127.0.0.1",
			server.port,
			"tester",
			"large",
			SshAuth(password = "secret"),
		)
		assertTrue(result.truncated)
		assertEquals(20_000, result.stdout.length)
	}

	/**
	 * The smallest useful shell: prints a line, optionally a big one, and exits
	 * with a distinctive status so the client's exit-code path is exercised.
	 */
	private class EchoCommand(private val command: String) : Command {
		private var out: OutputStream? = null
		private var err: OutputStream? = null
		private var callback: ExitCallback? = null

		override fun setInputStream(input: InputStream?) = Unit

		override fun setOutputStream(out: OutputStream?) {
			this.out = out
		}

		override fun setErrorStream(err: OutputStream?) {
			this.err = err
		}

		override fun setExitCallback(callback: ExitCallback?) {
			this.callback = callback
		}

		override fun start(channel: ChannelSession?, env: Environment?) {
			if (command == "sleep") {
				Thread.sleep(2_000)
				callback?.onExit(0)
				return
			}
			val text = if (command == "large") "x".repeat(30_000) else "ran:$command"
			out?.write((text + "\n").toByteArray())
			out?.flush()
			// MINA's channel output is asynchronous; let the write reach the
			// wire before onExit closes the channel.
			Thread.sleep(100)
			callback?.onExit(if (command == "large") 0 else 7)
		}

		override fun destroy(channel: ChannelSession?) = Unit
	}
}
