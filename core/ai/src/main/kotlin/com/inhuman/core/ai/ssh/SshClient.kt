package com.inhuman.core.ai.ssh

import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Credentials for one SSH call.
 *
 * They live here rather than in a settings table on purpose: the app does not
 * configure remote machines. The user tells the agent how to connect; the tool
 * carries that information for the length of one call. Nothing is persisted by
 * this class.
 */
class SshAuth(
	val password: String? = null,
	val privateKey: ByteArray? = null,
	val passphrase: ByteArray? = null,
)

data class SshResult(
	val stdout: String,
	val stderr: String,
	/** -1 when the server did not report one. */
	val exitCode: Int,
	val truncated: Boolean,
	val hostFingerprint: String,
)

class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The seam the tool is tested through; the client is the only production implementation. */
interface SshTransport {
	companion object {
		const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
		const val DEFAULT_COMMAND_TIMEOUT_MS = 120_000
	}

	suspend fun execute(
		host: String,
		port: Int,
		username: String,
		command: String,
		auth: SshAuth,
		hostKeyFingerprint: String? = null,
		connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
		commandTimeoutMs: Int = DEFAULT_COMMAND_TIMEOUT_MS,
	): SshResult
}

/**
 * A single non-interactive command over SSH.
 *
 * No PTY and no forwarded ports. The caller gets the stdout/stderr it asked
 * for, an exit status, and nothing that keeps running after the call.
 *
 * sshj rather than JSch: JSch's EdDSA key support throws
 * `UnsupportedOperationException` on the first `ssh-keygen -t ed25519` key,
 * which is the default key type today. sshj's BouncyCastle dependency is the
 * price of accepting modern keys.
 */
class SshClient(private val knownHostsFile: File) : SshTransport {

	override suspend fun execute(
		host: String,
		port: Int,
		username: String,
		command: String,
		auth: SshAuth,
		hostKeyFingerprint: String?,
		connectTimeoutMs: Int,
		commandTimeoutMs: Int,
	): SshResult = withContext(Dispatchers.IO) {
		require(host.isNotBlank()) { "host is required" }
		require(port in 1..65535) { "port must be between 1 and 65535" }
		require(username.isNotBlank()) { "username is required" }
		require(command.isNotBlank()) { "command is required" }
		require(auth.password != null || auth.privateKey?.isNotEmpty() == true) {
			"password or private key is required"
		}

		knownHostsFile.parentFile?.mkdirs()
		ensureBouncyCastle()
		val knownHosts = TofuKnownHosts(knownHostsFile, hostKeyFingerprint)
		val ssh = SSHClient().apply {
			connectTimeout = connectTimeoutMs
			timeout = commandTimeoutMs
			addHostKeyVerifier(knownHosts)
		}

		Diag.i(
			"ssh",
			"connect host=$host port=$port user=$username " +
				"auth=${if (auth.privateKey?.isNotEmpty() == true) "key" else "password"}",
		)

		var session: net.schmizz.sshj.connection.channel.direct.Session? = null
		var commandChannel: net.schmizz.sshj.connection.channel.direct.Session.Command? = null
		try {
			ssh.connect(host, port)
			Diag.i("ssh", "connected host=$host fingerprint=${knownHosts.fingerprint ?: "-"}")

			if (auth.privateKey?.isNotEmpty() == true) {
				val provider = OpenSSHKeyV1KeyFile().apply {
					init(
						StringReader(String(auth.privateKey, Charsets.UTF_8)),
						null,
						auth.passphrase?.let { pass -> Passphrase(pass) },
					)
				}
				ssh.authPublickey(username, provider)
			} else {
				ssh.authPassword(username, auth.password)
			}

			session = ssh.startSession()
			commandChannel = session.exec(command)

			val stdout = CappedBuffer(MAX_OUTPUT_BYTES)
			val stderr = CappedBuffer(MAX_OUTPUT_BYTES)
			val outThread = pump(commandChannel.inputStream, stdout)
			val errThread = pump(commandChannel.errorStream, stderr)

			val started = System.currentTimeMillis()
			commandChannel.join(commandTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
			val elapsed = System.currentTimeMillis() - started
			if (commandChannel.isOpen || elapsed >= commandTimeoutMs) {
				throw SshException("command timed out after ${commandTimeoutMs}ms")
			}
			outThread.join(1_000)
			errThread.join(1_000)

			val exit = commandChannel.exitStatus ?: -1
			val result = SshResult(
				stdout = stdout.toString(Charsets.UTF_8.name()),
				stderr = stderr.toString(Charsets.UTF_8.name()),
				exitCode = exit,
				truncated = stdout.truncated || stderr.truncated,
				hostFingerprint = knownHosts.fingerprint.orEmpty(),
			)
			Diag.i(
				"ssh",
				"exec done host=$host exit=$exit stdout=${result.stdout.length} " +
					"stderr=${result.stderr.length} truncated=${result.truncated}",
			)
			result
		} catch (e: SshException) {
			throw e
		} catch (e: Exception) {
			Diag.w("ssh", "failed host=$host: ${e.message}")
			throw SshException(e.message ?: "SSH connection failed", e)
		} finally {
			runCatching { commandChannel?.close() }
			runCatching { session?.close() }
			runCatching { ssh.close() }
		}
	}

	/**
	 * First use records the host key; later use requires the same one.
	 *
	 * This is trust-on-first-use, not "no verification": an unsolicited key
	 * change is a failure. It is deliberately invisible when nothing changed,
	 * because the app has no host-configuration screen.
	 *
	 * The fingerprint is the OpenSSH `SHA256:...` form (`ssh-keygen -lf`), so a
	 * user who cares can compare it out of band.
	 */
	private class TofuKnownHosts(
		private val file: File,
		private val explicitFingerprint: String?,
	) : HostKeyVerifier {

		private val entries = mutableMapOf<String, String>()
		var fingerprint: String? = null
			private set

		init {
			if (file.isFile) {
				file.readLines().forEach { line ->
					val trimmed = line.trim()
					if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
					val parts = trimmed.split(' ', limit = 2)
					if (parts.size == 2) entries[parts[0]] = parts[1]
				}
			}
		}

		override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
			val actual = fingerprintOf(key)
			fingerprint = actual
			val host = if (port == 22) hostname else "$hostname:$port"

			explicitFingerprint?.let { expected ->
				if (!sameFingerprint(expected, actual)) return false
				entries[host] = actual
				persist()
				return true
			}

			val known = entries[host]
			return when {
				known == null -> {
					entries[host] = actual
					persist()
					true
				}
				known == actual -> true
				else -> false
			}
		}

		override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

		private fun persist() {
			runCatching {
				file.writeText(entries.entries.joinToString("\n") { "${it.key} ${it.value}" } + "\n")
			}.onFailure { Diag.w("ssh", "could not persist known_hosts", it) }
		}
	}

	private class CappedBuffer(private val limit: Int) : ByteArrayOutputStream() {
		var truncated = false
			private set

		override fun write(b: ByteArray, off: Int, len: Int) {
			val room = limit - size()
			if (len > room) truncated = true
			if (room > 0) super.write(b, off, minOf(len, room))
		}
	}

	/** A password finder that always returns the one passphrase. */
	private class Passphrase(private val passphrase: ByteArray) : PasswordFinder {
		override fun reqPassword(resource: Resource<*>?): CharArray =
			String(passphrase, Charsets.UTF_8).toCharArray()

		override fun shouldRetry(resource: Resource<*>?): Boolean = false
	}

	companion object {
		const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
		const val DEFAULT_COMMAND_TIMEOUT_MS = 120_000
		private const val MAX_OUTPUT_BYTES = 20_000

		/**
		 * Android ships a stripped-down BouncyCastle under the provider name
		 * `BC`. sshj sees that name and assumes a full installation, then asks
		 * for X25519 and gets `NoSuchAlgorithmException`. The full bcprov is on
		 * our classpath; replacing the stripped provider before the first SSH
		 * connection is what makes modern key exchange work on the device.
		 *
		 * Runs once. If removal is refused by the platform, the original
		 * exception is still reported by the connection rather than hidden.
		 */
		@Volatile
		private var providerReady = false

		@Synchronized
		private fun ensureBouncyCastle() {
			if (providerReady) return
			runCatching {
				val existing = Security.getProvider("BC")
				if (existing?.javaClass?.name != BouncyCastleProvider::class.java.name) {
					Security.removeProvider("BC")
					Security.addProvider(BouncyCastleProvider())
					Diag.i("ssh", "installed full BouncyCastle provider")
				}
			}.onFailure { Diag.w("ssh", "BouncyCastle provider replacement failed", it) }
			providerReady = true
		}

		private fun pump(input: InputStream, output: CappedBuffer): Thread =
			Thread {
				val buffer = ByteArray(8192)
				runCatching {
					while (true) {
						val read = input.read(buffer)
						if (read < 0) break
						output.write(buffer, 0, read)
					}
				}
			}.apply {
				isDaemon = true
				start()
			}

		private fun fingerprintOf(key: PublicKey): String {
			val type = KeyType.fromKey(key)
			val buffer = Buffer.PlainBuffer().apply { type.putPubKeyIntoBuffer(key, this) }
			val digest = MessageDigest.getInstance("SHA-256").digest(buffer.compactData)
			return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
		}

		private fun sameFingerprint(expected: String, actual: String): Boolean {
			val a = expected.trim().removePrefix("SHA256:").trimEnd('=')
			val b = actual.removePrefix("SHA256:").trimEnd('=')
			return a.equals(b, ignoreCase = true)
		}
	}
}
