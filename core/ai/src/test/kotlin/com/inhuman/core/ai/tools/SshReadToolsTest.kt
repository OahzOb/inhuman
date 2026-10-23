package com.inhuman.core.ai.tools

import com.inhuman.core.ai.ssh.SshAuth
import com.inhuman.core.ai.ssh.SshDownloadedFile
import com.inhuman.core.ai.ssh.SshEntryType
import com.inhuman.core.ai.ssh.SshException
import com.inhuman.core.ai.ssh.SshFileResult
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshRemoteEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-only SSH tools, with a fake transport.
 *
 * The SFTP wire is covered by `SshFileClientTest`; this is the tool layer:
 * formatting, the text/binary decision and the refusal of an oversized read.
 */
class SshReadToolsTest {

	private class FakeTransport(
		private val entries: List<SshRemoteEntry> = emptyList(),
		private val file: ByteArray = "hello\n".toByteArray(),
		private val failure: Exception? = null,
		private val tailStart: Long = 0,
		private val tailTotal: Long = file.size.toLong(),
	) : SshFileTransport {
		var listMaxEntries: Int? = null
		var downloadMaxBytes: Long? = null

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
		): SshFileResult = error("upload is not used here")

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
			downloadMaxBytes = maxBytes
			return SshDownloadedFile(file, "SHA256:abc")
		}

		override suspend fun downloadTail(
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
			return SshDownloadedFile(file, "SHA256:abc", tailStart, tailTotal)
		}

		override suspend fun list(
			host: String,
			port: Int,
			username: String,
			remotePath: String,
			auth: SshAuth,
			hostKeyFingerprint: String?,
			maxEntries: Int,
			connectTimeoutMs: Int,
		): List<SshRemoteEntry> {
			failure?.let { throw it }
			listMaxEntries = maxEntries
			return entries
		}
	}

	private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
		put("host", "example.test")
		put("username", "tester")
		put("password", "secret")
		pairs.forEach { (key, value) -> put(key, value) }
	}

	@Test
	fun `list puts directories first and reports the metadata`() = runBlocking {
		val entries = listOf(
			SshRemoteEntry("z.log", SshEntryType.FILE, 1234, 1_700_000_000),
			SshRemoteEntry("sub", SshEntryType.DIRECTORY, 4096, null),
			SshRemoteEntry("link", SshEntryType.SYMLINK, 0, null),
		)
		val result = SshListTool(FakeTransport(entries = entries)).execute(
			args("remotePath" to "/var/log"),
		)

		assertFalse(result.isError)
		assertTrue(result.content.startsWith("/var/log:"))
		val lines = result.content.lines().filter { it.isNotBlank() }
		assertTrue("directories should sort first: $lines", lines[1].startsWith("d"))
		assertTrue(lines[1].contains("sub"))
		assertTrue(lines.any { it.startsWith("-") && it.contains("z.log") && it.contains("1234") })
		assertTrue(lines.any { it.startsWith("l") && it.contains("link") })
	}

	@Test
	fun `list reports the entry cap`() = runBlocking {
		val entries = listOf(SshRemoteEntry("a", SshEntryType.FILE, 1, null))
		val result = SshListTool(FakeTransport(entries = entries), maxEntries = 1).execute(
			args("remotePath" to "."),
		)
		assertTrue(result.content.contains("at most 1 entries"))
	}

	@Test
	fun `list clamps a request above the user ceiling`() = runBlocking {
		val transport = FakeTransport(
			entries = listOf(SshRemoteEntry("a", SshEntryType.FILE, 1, null)),
		)
		val result = SshListTool(transport, maxEntries = 2).execute(
			args("remotePath" to ".", "maxEntries" to "100"),
		)
		assertEquals(2, transport.listMaxEntries)
		assertTrue(result.content.contains("maxEntries clamped to 2"))
	}

	@Test
	fun `read clamps a request above the user ceiling`() = runBlocking {
		val transport = FakeTransport(file = "hello\n".toByteArray())
		val result = SshReadFileTool(transport, maxBytes = 1024).execute(
			args("remotePath" to "/tmp/a", "maxBytes" to "999999"),
		)
		assertEquals(1024L, transport.downloadMaxBytes)
		assertTrue(result.content.contains("maxBytes clamped to 1024"))
	}

	@Test
	fun `read returns text and the host key`() = runBlocking {
		val result = SshReadFileTool(FakeTransport(file = "server {\n  listen 80;\n}\n".toByteArray()))
			.execute(args("remotePath" to "/etc/nginx/site.conf"))

		assertFalse(result.isError)
		assertTrue(result.content.contains("listen 80"))
		assertTrue(result.content.contains("SHA256:abc"))
	}

	@Test
	fun `read refuses a binary file and points at download`() = runBlocking {
		val result = SshReadFileTool(FakeTransport(file = byteArrayOf(0, 1, 2, 3))).execute(
			args("remotePath" to "/tmp/blob"),
		)
		assertTrue(result.isError)
		assertTrue(result.content.contains("binary"))
		assertTrue(result.content.contains("ssh_download"))
	}

	@Test
	fun `read surfaces the size limit instead of truncating`() = runBlocking {
		val result = SshReadFileTool(
			FakeTransport(failure = SshException("remote file is 100000 bytes; the limit is 65536")),
		).execute(args("remotePath" to "/var/log/big.log"))
		assertTrue(result.isError)
		assertTrue(result.content.contains("65536"))
	}

	@Test
	fun `missing authentication is refused before the transport`() = runBlocking {
		val bare = buildJsonObject {
			put("host", "example.test")
			put("username", "tester")
			put("remotePath", "/etc/hostname")
		}
		assertTrue(SshListTool(FakeTransport()).execute(bare).isError)
		assertTrue(SshReadFileTool(FakeTransport()).execute(bare).isError)
	}

	@Test
	fun `tail returns the last requested lines`() = runBlocking {
		val result = SshReadFileTool(
			FakeTransport(file = "one\ntwo\nthree\nfour\n".toByteArray()),
		).execute(args("remotePath" to "/var/log/app.log", "tailLines" to "2"))

		assertFalse(result.isError)
		assertTrue(result.content.contains("last 2 lines"))
		assertTrue(result.content.contains("three"))
		assertTrue(result.content.contains("four"))
		assertFalse(result.content.contains("one"))
	}

	@Test
	fun `tail drops a partial first line from a bounded window`() = runBlocking {
		val result = SshReadFileTool(
			FakeTransport(
				file = "partial-line\nlast-1\nlast-2\n".toByteArray(),
				tailStart = 100,
				tailTotal = 400,
			),
		).execute(args("remotePath" to "/var/log/app.log", "tailLines" to "2"))

		assertFalse(result.isError)
		assertTrue(result.content.contains("last-1"))
		assertTrue(result.content.contains("last-2"))
		assertFalse(result.content.contains("partial-line"))
	}

	@Test
	fun `tail refuses an unreasonable line count`() = runBlocking {
		val result = SshReadFileTool(FakeTransport()).execute(
			args("remotePath" to "/var/log/app.log", "tailLines" to "2001"),
		)
		assertTrue(result.isError)
		assertTrue(result.content.contains("between 0 and 2000"))
	}

	@Test
	fun `tail refuses a binary window`() = runBlocking {
		val result = SshReadFileTool(
			FakeTransport(file = byteArrayOf(0, 1, 2)),
		).execute(args("remotePath" to "/tmp/blob", "tailLines" to "2"))
		assertTrue(result.isError)
		assertTrue(result.content.contains("binary"))
	}

	@Test
	fun `read-only tools are not marked as mutating`() {
		val list = SshListTool(FakeTransport())
		val read = SshReadFileTool(FakeTransport())
		assertFalse(list.mutates)
		assertFalse(read.mutates)
		assertTrue(list.reversible)
		assertTrue(read.reversible)
	}
}
