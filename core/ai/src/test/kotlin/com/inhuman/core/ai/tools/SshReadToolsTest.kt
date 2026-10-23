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
	) : SshFileTransport {
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
			return SshDownloadedFile(file, "SHA256:abc")
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
	fun `read-only tools are not marked as mutating`() {
		val list = SshListTool(FakeTransport())
		val read = SshReadFileTool(FakeTransport())
		assertFalse(list.mutates)
		assertFalse(read.mutates)
		assertTrue(list.reversible)
		assertTrue(read.reversible)
	}
}
