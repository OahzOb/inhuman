package com.inhuman.core.ai.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * One real SSH host, only when its credentials are supplied in the environment.
 *
 * This is a **read-only** probe on purpose: it lists `/tmp` and downloads
 * `/etc/hostname`. It never uploads, writes, or runs a remote command, so the
 * check is safe to point at a machine that is not a disposable test box.
 *
 * Skipped by default. Run with:
 *
 * ```
 * INHUMAN_SSH_HOST=... INHUMAN_SSH_USER=... INHUMAN_SSH_PASSWORD=... \
 *   ./gradlew :core:ai:testDebugUnitTest --tests '*SshLiveProbeTest*'
 * ```
 *
 * The password is read from the environment and is never printed, persisted, or
 * written to the test report.
 */
class SshLiveProbeTest {

	@Test(timeout = 45_000)
	fun `list and download against a real host`() = runBlocking {
		val host = System.getenv("INHUMAN_SSH_HOST").orEmpty()
		val user = System.getenv("INHUMAN_SSH_USER").orEmpty()
		val password = System.getenv("INHUMAN_SSH_PASSWORD").orEmpty()
		assumeTrue(
			"set INHUMAN_SSH_HOST/USER/PASSWORD to run the live read-only probe",
			host.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
		)

		val dir = Files.createTempDirectory("inhuman-ssh-live")
		try {
			val client = SshClient(dir.resolve("known_hosts").toFile())
			val entries = client.list(
				host = host,
				port = 22,
				username = user,
				remotePath = "/tmp",
				auth = SshAuth(password = password),
				maxEntries = 20,
			)
			assertTrue("live host returned an empty /tmp listing", entries.isNotEmpty())

			val file = client.download(
				host = host,
				port = 22,
				username = user,
				remotePath = "/etc/hostname",
				auth = SshAuth(password = password),
				maxBytes = 64L * 1024,
			)
			assertTrue("live host returned an empty /etc/hostname", file.bytes.isNotEmpty())
		} finally {
			dir.toFile().deleteRecursively()
		}
	}
}
