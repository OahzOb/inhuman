package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ssh.SshAuth
import com.inhuman.core.ai.ssh.SshDownloadedFile
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshFileResult
import com.inhuman.core.ai.ssh.SshFileTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two transfer tools, with a fake transport.
 *
 * The SFTP wire itself is covered by [com.inhuman.core.ai.ssh.SshFileClientTest];
 * these tests are about the tool layer: which arguments travel, what is refused
 * before the transport is reached, and what the model is told happened.
 */
class SshFileToolsTest {

	private class FakeTransport(
		private val downloadBytes: ByteArray = "remote".toByteArray(),
		private val failure: Exception? = null,
	) : SshFileTransport {
		var uploaded: ByteArray? = null
		var remotePath: String? = null
		var overwrite: Boolean? = null
		var maxBytes: Long? = null

		override suspend fun upload(
			host: String,
			port: Int,
			username: String,
			remotePath: String,
			content: ByteArray,
			auth: SshAuth,
			hostKeyFingerprint: String?,
			overwrite: Boolean,
			connectTimeoutMs: Int,
		): SshFileResult {
			failure?.let { throw it }
			this.uploaded = content
			this.remotePath = remotePath
			this.overwrite = overwrite
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
		): SshDownloadedFile {
			failure?.let { throw it }
			this.remotePath = remotePath
			this.maxBytes = maxBytes
			return SshDownloadedFile(downloadBytes, "SHA256:abc")
		}
	}

	private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
		put("host", "example.test")
		put("username", "tester")
		put("password", "secret")
		pairs.forEach { (key, value) -> put(key, value) }
	}

	@Test
	fun `upload sends the workspace bytes and reports the destination`() = runBlocking {
		val transport = FakeTransport()
		val tool = SshUploadTool(transport, { "script".toByteArray() }, maxBytes = 1024)
		val result = tool.execute(
			args(
				"localPath" to "deploy/run.sh",
				"remotePath" to "/srv/run.sh",
				"overwrite" to "true",
			),
		)

		assertTrue(result.isError.not())
		assertArrayEquals("script".toByteArray(), transport.uploaded)
		assertEquals("/srv/run.sh", transport.remotePath)
		assertEquals(true, transport.overwrite)
		assertTrue(result.content.contains("/srv/run.sh"))
	}

	@Test
	fun `upload refuses a file over the limit before connecting`() = runBlocking {
		val transport = FakeTransport()
		val tool = SshUploadTool(transport, { ByteArray(50) }, maxBytes = 10)
		val result = tool.execute(
			args("localPath" to "big.bin", "remotePath" to "/tmp/big.bin"),
		)

		assertTrue(result.isError)
		assertTrue(result.content.contains("limit"))
		assertEquals(null, transport.uploaded)
	}

	@Test
	fun `upload refuses a missing local file`() = runBlocking {
		val result = SshUploadTool(FakeTransport(), { null }).execute(
			args("localPath" to "missing.txt", "remotePath" to "/tmp/x"),
		)
		assertTrue(result.isError)
		assertTrue(result.content.contains("not found"))
	}

	@Test
	fun `download saves the bytes and reports the local path`() = runBlocking {
		val transport = FakeTransport("hello".toByteArray())
		var saved: ByteArray? = null
		var savedOverwrite: Boolean? = null
		val tool = SshDownloadTool(
			transport = transport,
			saveWorkspaceFile = { path, bytes, overwrite ->
				saved = bytes
				savedOverwrite = overwrite
				path
			},
			maxBytes = 2048,
		)
		val result = tool.execute(
			args("remotePath" to "/var/log/app.log", "localPath" to "logs/app.log"),
		)

		assertTrue(result.isError.not())
		assertArrayEquals("hello".toByteArray(), saved)
		assertEquals(false, savedOverwrite)
		assertEquals(2048L, transport.maxBytes)
		assertTrue(result.content.contains("logs/app.log"))
	}

	@Test
	fun `download surfaces a workspace refusal as a tool error`() = runBlocking {
		val tool = SshDownloadTool(
			transport = FakeTransport(),
			saveWorkspaceFile = { path, _, _ -> throw IllegalArgumentException("$path exists") },
		)
		val result = tool.execute(
			args("remotePath" to "/tmp/x", "localPath" to "x"),
		)
		assertTrue(result.isError)
		assertTrue(result.content.contains("exists"))
	}

	@Test
	fun `a transport failure is reported without a stack trace`() = runBlocking {
		val result = SshUploadTool(
			FakeTransport(failure = SshException("remote file exists: /srv/x")),
			{ "x".toByteArray() },
		).execute(args("localPath" to "x", "remotePath" to "/srv/x"))
		assertTrue(result.isError)
		assertEquals("ssh_upload: remote file exists: /srv/x", result.content)
	}

	@Test
	fun `missing authentication is refused by both tools`() = runBlocking {
		val bare = buildJsonObject {
			put("host", "example.test")
			put("username", "tester")
			put("localPath", "x")
			put("remotePath", "/tmp/x")
		}
		assertTrue(SshUploadTool(FakeTransport(), { "x".toByteArray() }).execute(bare).isError)
		assertTrue(
			SshDownloadTool(
				transport = FakeTransport(),
				saveWorkspaceFile = { _, _, _ -> "x" },
			).execute(bare).isError,
		)
	}

	@Test
	fun `upload is irreversible and download is not`() {
		val upload = SshUploadTool(FakeTransport(), { ByteArray(0) })
		val download = SshDownloadTool(
			transport = FakeTransport(),
			saveWorkspaceFile = { _, _, _ -> "x" },
		)
		assertTrue(upload.mutates)
		assertFalse(upload.reversible)
		assertTrue(download.mutates)
		assertTrue(download.reversible)
		assertFalse(upload.needsApproval(buildJsonObject { }))
		assertFalse(download.needsApproval(buildJsonObject { }))
	}
}
