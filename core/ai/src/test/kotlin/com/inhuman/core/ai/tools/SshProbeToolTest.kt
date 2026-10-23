package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ssh.SshAuth
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshResult
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixed probe tool.
 *
 * The point of these tests is that the command is fixed: the model supplies
 * only connection details, so a probe selected without `ssh_exec` cannot become
 * an arbitrary command channel.
 */
class SshProbeToolTest {

	private class FakeSsh(
		private val result: SshResult = SshResult(
			stdout = "== system ==\nLinux test 6.1\n",
			stderr = "",
			exitCode = 0,
			truncated = false,
			hostFingerprint = "SHA256:abc",
		),
		private val failure: Exception? = null,
	) : SshTransport {
		var captured: String? = null

		override suspend fun execute(
			host: String,
			port: Int,
			username: String,
			command: String,
			auth: SshAuth,
			hostKeyFingerprint: String?,
			connectTimeoutMs: Int,
			commandTimeoutMs: Int,
			maxOutputBytes: Int,
		): SshResult {
			captured = command
			failure?.let { throw it }
			return result
		}
	}

	private fun args() = buildJsonObject {
		put("host", "example.test")
		put("username", "tester")
		put("password", "secret")
	}

	@Test
	fun `probe returns the fixed snapshot`() = runBlocking {
		val result = SshProbeTool(FakeSsh()).execute(args())
		assertFalse(result.isError)
		assertTrue(result.content.contains("probe of example.test"))
		assertTrue(result.content.contains("== system =="))
		assertTrue(result.content.contains("SHA256:abc"))
	}

	@Test
	fun `probe command is fixed and covers the read-only sections`() = runBlocking {
		val fake = FakeSsh()
		SshProbeTool(fake).execute(args())
		val command = fake.captured.orEmpty()
		assertTrue(command.contains("uname -srm"))
		assertTrue(command.contains("systemctl --failed"))
		assertTrue(command.contains("ss -lntup"))
		assertTrue(command.contains("docker ps"))
		assertFalse("the model cannot supply a command", command.contains("example.test"))
	}

	@Test
	fun `probe refuses a missing authentication method`() = runBlocking {
		val bare = buildJsonObject {
			put("host", "example.test")
			put("username", "tester")
		}
		assertTrue(SshProbeTool(FakeSsh()).execute(bare).isError)
	}

	@Test
	fun `probe surfaces a transport failure`() = runBlocking {
		val result = SshProbeTool(FakeSsh(failure = SshException("connection refused"))).execute(args())
		assertTrue(result.isError)
		assertTrue(result.content.contains("connection refused"))
	}

	@Test
	fun `probe is read-only but remote`() {
		val probe = SshProbeTool(FakeSsh())
		assertFalse(probe.mutates)
		assertTrue(probe.reversible)
		assertEquals("ssh_probe", probe.name)
	}
}
