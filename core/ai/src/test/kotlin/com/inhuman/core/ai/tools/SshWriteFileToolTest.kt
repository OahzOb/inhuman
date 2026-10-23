package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ssh.SshAuth
import com.inhuman.core.ai.ssh.SshDownloadedFile
import com.inhuman.core.ai.ssh.SshFileResult
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshRemoteEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The direct-text write tool: argument travel, size checks and flags. */
class SshWriteFileToolTest {

	private class FakeTransport : SshFileTransport {
		var content: ByteArray? = null
		var remotePath: String? = null
		var overwrite: Boolean? = null
		var createParents: Boolean? = null

		override suspend fun upload(
			host: String,
			port: Int,
			username: String,
			remotePath: String,
			content: ByteArray,
			auth: SshAuth,
			hostKeyFingerprint: String?,
			overwrite: Boolean,
			createParents: Boolean,
			connectTimeoutMs: Int,
		): SshFileResult {
			this.content = content
			this.remotePath = remotePath
			this.overwrite = overwrite
			this.createParents = createParents
			return SshFileResult(content.size.toLong(), "SHA256:abc")
		}

		override suspend fun download(
			host: String,
			port: Int,
			username: String,
			remotePath: String,
			auth: SshAuth,
			hostKeyFingerprint: String?,
			maxBytes: Long,
			connectTimeoutMs: Int,
		): SshDownloadedFile = error("not used")

		override suspend fun list(
			host: String,
			port: Int,
			username: String,
			remotePath: String,
			auth: SshAuth,
			hostKeyFingerprint: String?,
			maxEntries: Int,
			connectTimeoutMs: Int,
		): List<SshRemoteEntry> = error("not used")
	}

	private fun args(content: String = "listen 80;\n") = buildJsonObject {
		put("host", "example.test")
		put("username", "tester")
		put("password", "secret")
		put("remotePath", "/etc/nginx/site.conf")
		put("content", content)
	}

	@Test
	fun `write sends the exact bytes and flags`() = runBlocking {
		val fake = FakeTransport()
		val result = SshWriteFileTool(fake).execute(
			buildJsonObject {
				put("host", "example.test")
				put("username", "tester")
				put("password", "secret")
				put("remotePath", "/etc/nginx/site.conf")
				put("content", "listen 80;\n")
				put("overwrite", true)
				put("createParents", true)
			},
		)
		assertFalse(result.isError)
		assertArrayEquals("listen 80;\n".toByteArray(), fake.content)
		assertEquals("/etc/nginx/site.conf", fake.remotePath)
		assertEquals(true, fake.overwrite)
		assertEquals(true, fake.createParents)
		assertTrue(result.content.contains("wrote 11 bytes"))
	}

	@Test
	fun `oversized content is refused before the transport`() = runBlocking {
		val result = SshWriteFileTool(FakeTransport(), maxBytes = 4).execute(args("too long"))
		assertTrue(result.isError)
		assertTrue(result.content.contains("limit is 4"))
	}

	@Test
	fun `a null byte is refused`() = runBlocking {
		val result = SshWriteFileTool(FakeTransport()).execute(args("a\u0000b"))
		assertTrue(result.isError)
		assertTrue(result.content.contains("null byte"))
	}

	@Test
	fun `missing authentication is refused`() = runBlocking {
		val bare = buildJsonObject {
			put("host", "example.test")
			put("username", "tester")
			put("remotePath", "/tmp/a")
			put("content", "x")
		}
		assertTrue(SshWriteFileTool(FakeTransport()).execute(bare).isError)
	}

	@Test
	fun `write is mutating and irreversible`() {
		val tool = SshWriteFileTool(FakeTransport())
		assertTrue(tool.mutates)
		assertFalse(tool.reversible)
		assertEquals("ssh_write_file", tool.name)
	}
}
