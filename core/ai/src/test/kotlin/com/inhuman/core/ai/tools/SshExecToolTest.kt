package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ssh.SshAuth
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshResult
import com.inhuman.core.ai.ssh.SshTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshExecToolTest {

	private class FakeTransport(
		private val result: SshResult = SshResult(
			stdout = "hello\n",
			stderr = "",
			exitCode = 0,
			truncated = false,
			hostFingerprint = "SHA256:abc",
		),
		private val failure: Exception? = null,
	) : SshTransport {
		var auth: SshAuth? = null
		var command: String? = null
		var host: String? = null

		override suspend fun execute(
			host: String,
			port: Int,
			username: String,
			command: String,
			auth: SshAuth,
			hostKeyFingerprint: String?,
			connectTimeoutMs: Int,
			commandTimeoutMs: Int,
		): SshResult {
			this.host = host
			this.command = command
			this.auth = auth
			failure?.let { throw it }
			return result
		}
	}

	@Test
	fun `the tool is mutating and is not a per-call approval`() {
		val tool = SshExecTool(FakeTransport())
		assertEquals("ssh_exec", tool.name)
		assertTrue(tool.mutates)
		assertFalse(tool.needsApproval(buildJsonObject { }))
	}

	@Test
	fun `password authentication runs the command and formats the exit`() = runBlocking {
		val transport = FakeTransport()
		val tool = SshExecTool(transport)
		val result = tool.execute(
			buildJsonObject {
				put("host", "example.test")
				put("username", "root")
				put("command", "systemctl restart nginx")
				put("password", "secret")
			},
		)

		assertFalse(result.isError)
		assertEquals("example.test", transport.host)
		assertEquals("systemctl restart nginx", transport.command)
		assertEquals("secret", transport.auth?.password)
		assertTrue(result.content.contains("exit code: 0"))
		assertTrue(result.content.contains("hello"))
	}

	@Test
	fun `identityFile is read through the workspace reader and not the prompt`() = runBlocking {
		val transport = FakeTransport()
		val tool = SshExecTool(transport) { path ->
			if (path == "keys/server") "PRIVATE KEY".toByteArray() else null
		}
		val result = tool.execute(
			buildJsonObject {
				put("host", "example.test")
				put("username", "root")
				put("command", "uname -a")
				put("identityFile", "keys/server")
			},
		)

		assertFalse(result.isError)
		assertEquals("PRIVATE KEY", transport.auth?.privateKey?.toString(Charsets.UTF_8))
	}

	@Test
	fun `a missing identityFile is an error before connecting`() = runBlocking {
		val transport = FakeTransport()
		val tool = SshExecTool(transport) { null }
		val result = tool.execute(
			buildJsonObject {
				put("host", "example.test")
				put("username", "root")
				put("command", "uname -a")
				put("identityFile", "keys/missing")
			},
		)

		assertTrue(result.isError)
		assertTrue(result.content.contains("identityFile not found"))
		assertEquals(null, transport.host)
	}

	@Test
	fun `authentication is required`() = runBlocking {
		val tool = SshExecTool(FakeTransport())
		val result = tool.execute(
			buildJsonObject {
				put("host", "example.test")
				put("username", "root")
				put("command", "uname -a")
			},
		)
		assertTrue(result.isError)
	}

	@Test
	fun `a transport failure becomes a tool error`() = runBlocking {
		val tool = SshExecTool(FakeTransport(failure = SshException("auth failed")))
		val result = tool.execute(
			buildJsonObject {
				put("host", "example.test")
				put("username", "root")
				put("command", "uname -a")
				put("password", "wrong")
			},
		)
		assertTrue(result.isError)
		assertTrue(result.content.contains("auth failed"))
	}

	@Test
	fun `a wrapped arguments string is unwrapped`() = runBlocking {
		val transport = FakeTransport()
		val tool = SshExecTool(transport)
		val result = tool.execute(
			buildJsonObject {
				put(
					"arguments",
					"""{"hostname":"example.test","username":"root","command":"uname -a","password":"secret"}""",
				)
			},
		)

		assertFalse(result.isError)
		assertEquals("example.test", transport.host)
		assertEquals("uname -a", transport.command)
	}

	@Test
	fun `a port that is not an integer is rejected`() = runBlocking {
		val tool = SshExecTool(FakeTransport())
		val result = tool.execute(
			buildJsonObject {
				put("host", "example.test")
				put("port", JsonPrimitive("not-a-port"))
				put("username", "root")
				put("command", "uname -a")
				put("password", "secret")
			},
		)
		assertTrue(result.isError)
	}
}
