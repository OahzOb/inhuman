package com.inhuman.ui.chat

import com.inhuman.core.data.db.MessageEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The bundle is a pure format, so every acceptance property is tested here:
 * values survive the round trip byte-for-byte (including the JSON columns that
 * are themselves text), a damaged or foreign document is refused, and a zip
 * from an untrusted source cannot name a path outside the filesystem.
 */
class ConversationBundleTest {

	@Test
	fun `every field survives the json round trip`() {
		val toolCalls = """[{"id":"call-1","name":"read_file","argumentsJson":"{\"path\":\"notes.md\"}"}]"""
		val providerState = """[{"type":"reasoning","encrypted_content":"opaque"}]"""
		val attachments = """[{"path":"图片/一.png","mime":"image/png"}]"""
		val changedFiles = """[{"path":"notes.md","kind":"write"}]"""
		val messages = listOf(
			entity(
				id = "m1",
				role = "user",
				content = "你好 👋",
				createdAt = 11,
				attachmentsJson = attachments,
			),
			entity(
				id = "m2",
				role = "assistant",
				content = "回答",
				createdAt = 12,
				reasoning = "思考",
				reasoningSignature = "signature",
				providerState = providerState,
				model = "deepseek-flash",
				finishReason = "stop",
				promptTokens = 10,
				completionTokens = 20,
				cachedPromptTokens = 3,
				reasoningTokens = 4,
				timeToFirstTokenMs = 100,
				generationMs = 200,
				error = "provider said no",
				toolCallsJson = toolCalls,
				changedFilesJson = changedFiles,
				mutated = true,
			),
			entity(
				id = "m3",
				role = "tool",
				content = "file body",
				createdAt = 13,
				toolCallId = "call-1",
				toolName = "read_file",
			),
		)

		val encoded = ConversationBundleCodec.encode("标题 🚀", "continuous", 1_700_000_000_123L, messages)
		assertTrue(encoded.contains("\"format\":\"inhuman.conversation/1\""))

		val decoded = ConversationBundleCodec.decode(encoded)
		assertEquals(
			ConversationBundle(
				title = "标题 🚀",
				sessionMode = "continuous",
				exportedAt = 1_700_000_000_123L,
				messages = listOf(
					BundleMessage(
						role = "user",
						content = "你好 👋",
						reasoning = null,
						reasoningSignature = null,
						providerState = null,
						model = null,
						finishReason = null,
						createdAt = 11,
						promptTokens = null,
						completionTokens = null,
						cachedPromptTokens = null,
						reasoningTokens = null,
						timeToFirstTokenMs = null,
						generationMs = null,
						error = null,
						toolCallsJson = null,
						toolCallId = null,
						toolName = null,
						attachmentsJson = attachments,
						changedFilesJson = null,
						mutated = false,
					),
					BundleMessage(
						role = "assistant",
						content = "回答",
						reasoning = "思考",
						reasoningSignature = "signature",
						providerState = providerState,
						model = "deepseek-flash",
						finishReason = "stop",
						createdAt = 12,
						promptTokens = 10,
						completionTokens = 20,
						cachedPromptTokens = 3,
						reasoningTokens = 4,
						timeToFirstTokenMs = 100,
						generationMs = 200,
						error = "provider said no",
						toolCallsJson = toolCalls,
						toolCallId = null,
						toolName = null,
						attachmentsJson = null,
						changedFilesJson = changedFiles,
						mutated = true,
					),
					BundleMessage(
						role = "tool",
						content = "file body",
						reasoning = null,
						reasoningSignature = null,
						providerState = null,
						model = null,
						finishReason = null,
						createdAt = 13,
						promptTokens = null,
						completionTokens = null,
						cachedPromptTokens = null,
						reasoningTokens = null,
						timeToFirstTokenMs = null,
						generationMs = null,
						error = null,
						toolCallsJson = null,
						toolCallId = "call-1",
						toolName = "read_file",
						attachmentsJson = null,
						changedFilesJson = null,
						mutated = false,
					),
				),
			),
			decoded,
		)
		// The JSON columns are text, not parsed values: a re-serialization would
		// change key order and whitespace and stop the transcript from matching
		// what the provider originally sent.
		assertEquals(toolCalls, decoded?.messages?.get(1)?.toolCallsJson)
		assertEquals(providerState, decoded?.messages?.get(1)?.providerState)
	}

	@Test
	fun `missing optional fields decode as null and defaults`() {
		val json = """
			{
			  "format": "inhuman.conversation/1",
			  "title": "t",
			  "sessionMode": "continuous",
			  "exportedAt": 42,
			  "futureField": {"ignored": true},
			  "messages": [
			    {"role": "user", "content": "hi", "promptTokens": null, "unknown": {"x": 1}},
			    {"role": "assistant", "content": "yo", "promptTokens": 7.0, "mutated": true, "toolName": 17}
			  ]
			}
		""".trimIndent()
		val bundle = ConversationBundleCodec.decode(json)
		assertEquals("t", bundle?.title)
		assertEquals("continuous", bundle?.sessionMode)
		assertEquals(42L, bundle?.exportedAt)
		assertEquals(2, bundle?.messages?.size)
		assertEquals("hi", bundle?.messages?.get(0)?.content)
		assertNull(bundle?.messages?.get(0)?.promptTokens)
		assertFalse(bundle?.messages?.get(0)?.mutated ?: true)
		assertEquals(7, bundle?.messages?.get(1)?.promptTokens)
		assertTrue(bundle?.messages?.get(1)?.mutated ?: false)
		assertNull(bundle?.messages?.get(1)?.toolName)
	}

	@Test
	fun `a document without the envelope fields still decodes`() {
		val bundle = ConversationBundleCodec.decode("""{"format":"inhuman.conversation/1"}""")
		assertEquals("", bundle?.title)
		assertEquals("classic", bundle?.sessionMode)
		assertEquals(0L, bundle?.exportedAt)
		assertTrue(bundle?.messages?.isEmpty() ?: false)
	}

	@Test
	fun `bad json a wrong format and a malformed message are refused`() {
		assertNull(ConversationBundleCodec.decode("not json"))
		assertNull(ConversationBundleCodec.decode("{}"))
		assertNull(ConversationBundleCodec.decode("""{"format":"other","messages":[]}"""))
		assertNull(ConversationBundleCodec.decode("""{"format":"inhuman.conversation/1","messages":{}}"""))
		assertNull(ConversationBundleCodec.decode("""{"format":"inhuman.conversation/1","messages":[1]}"""))
	}

	@Test
	fun `attachment paths are extracted in order and malformed entries are skipped`() {
		assertTrue(ConversationBundleCodec.attachmentPaths(null).isEmpty())
		assertTrue(ConversationBundleCodec.attachmentPaths("").isEmpty())
		assertTrue(ConversationBundleCodec.attachmentPaths("{").isEmpty())
		assertTrue(ConversationBundleCodec.attachmentPaths("""{"path":"x"}""").isEmpty())
		assertEquals(
			listOf("a.png", "dir/b.txt"),
			ConversationBundleCodec.attachmentPaths(
				"""[{"path":"a.png","mime":"image/png"},{"path":""},{"mime":"x"},{"path":2},null,{"path":"dir/b.txt"}]""",
			),
		)
	}

	@Test
	fun `the zip round trip keeps json and binary attachment bytes`() {
		val messages = listOf(entity(id = "m1", role = "user", content = "hi", createdAt = 1))
		val json = ConversationBundleCodec.encode("bundle", "classic", 9L, messages)
		val binary = ByteArray(256) { index -> index.toByte() }
		val sources = listOf(
			ConversationBundleZip.Source("a.txt") { ByteArrayInputStream("hello".toByteArray()) },
			ConversationBundleZip.Source("dir/图片.bin") { ByteArrayInputStream(binary) },
		)

		val archive = ByteArrayOutputStream()
		val result = ConversationBundleZip.write(archive, json, sources)
		assertEquals(2, result.files)
		assertEquals(5L + 256L, result.bytes)
		assertTrue(result.missing.isEmpty())
		assertEquals(
			listOf("conversation.json", "files/a.txt", "files/dir/图片.bin"),
			entryNames(archive.toByteArray()),
		)

		val read = ConversationBundleZip.read(ByteArrayInputStream(archive.toByteArray()))
		assertEquals(json, read?.json)
		assertEquals(setOf("a.txt", "dir/图片.bin"), read?.files?.keys)
		assertArrayEquals("hello".toByteArray(), read?.files?.get("a.txt"))
		assertArrayEquals(binary, read?.files?.get("dir/图片.bin"))
		assertTrue(read?.skipped?.isEmpty() ?: false)
	}

	@Test
	fun `unreadable and unsafe sources are reported and the archive stays readable`() {
		val json = ConversationBundleCodec.encode("bundle", "classic", 9L, emptyList())
		val failingRead = object : InputStream() {
			override fun read(): Int = throw IOException("boom")
		}
		val sources = listOf(
			ConversationBundleZip.Source("ok.txt") { ByteArrayInputStream("abc".toByteArray()) },
			ConversationBundleZip.Source("gone.bin") { throw FileNotFoundException("gone") },
			ConversationBundleZip.Source("boom.bin") { failingRead },
			ConversationBundleZip.Source("ok.txt") { ByteArrayInputStream("second".toByteArray()) },
			ConversationBundleZip.Source("../escape.txt") { ByteArrayInputStream(byteArrayOf(1)) },
		)

		val archive = ByteArrayOutputStream()
		val result = ConversationBundleZip.write(archive, json, sources)
		assertEquals(1, result.files)
		assertEquals(3L, result.bytes)
		assertEquals(
			listOf("gone.bin", "boom.bin", "ok.txt", "../escape.txt"),
			result.missing,
		)

		val read = ConversationBundleZip.read(ByteArrayInputStream(archive.toByteArray()))
		assertEquals(json, read?.json)
		assertEquals(setOf("ok.txt"), read?.files?.keys)
		assertArrayEquals("abc".toByteArray(), read?.files?.get("ok.txt"))
	}

	@Test
	fun `zip slip entries are skipped and never become keys`() {
		val json = ConversationBundleCodec.encode("bundle", "classic", 9L, emptyList())
		val unsafe = listOf(
			"files/../escape.txt",
			"files/C:/evil.txt",
			"files/..\\evil.txt",
			"files//double.txt",
			"files/./dot.txt",
			"/absolute.txt",
			"other/unknown.bin",
		)
		val archive = zipOf(
			buildList {
				add("conversation.json" to json.toByteArray())
				unsafe.forEach { name -> add(name to byteArrayOf(1)) }
				add("files/ok.txt" to "safe".toByteArray())
				add("files/" to ByteArray(0))
			},
		)

		val read = ConversationBundleZip.read(ByteArrayInputStream(archive))
		assertEquals(json, read?.json)
		assertEquals(setOf("ok.txt"), read?.files?.keys)
		assertArrayEquals("safe".toByteArray(), read?.files?.get("ok.txt"))
		assertEquals(unsafe + listOf("files/"), read?.skipped)
	}

	@Test
	fun `byte caps return null for json file and total overflow`() {
		val json = ConversationBundleCodec.encode("bundle", "classic", 9L, emptyList())
		val jsonBytes = json.toByteArray()
		val bigFile = ByteArray(8) { 7 }
		val archive = zipOf(
			listOf(
				"conversation.json" to jsonBytes,
				"files/big.bin" to bigFile,
			),
		)

		assertNull(
			ConversationBundleZip.read(
				ByteArrayInputStream(archive),
				maxFileBytes = 4,
			),
		)
		assertNull(
			ConversationBundleZip.read(
				ByteArrayInputStream(archive),
				maxJsonBytes = 4,
			),
		)
		assertNull(
			ConversationBundleZip.read(
				ByteArrayInputStream(archive),
				maxTotalBytes = jsonBytes.size.toLong() + 4,
			),
		)
	}

	@Test
	fun `a zip without conversation json is refused`() {
		val archive = zipOf(listOf("files/a.txt" to "hello".toByteArray()))
		assertNull(ConversationBundleZip.read(ByteArrayInputStream(archive)))
	}

	@Test
	fun `a non zip stream is refused`() {
		assertNull(ConversationBundleZip.read(ByteArrayInputStream("not a zip".toByteArray())))
	}

	private fun entity(
		id: String,
		role: String,
		content: String,
		createdAt: Long,
		reasoning: String? = null,
		reasoningSignature: String? = null,
		providerState: String? = null,
		model: String? = null,
		finishReason: String? = null,
		promptTokens: Int? = null,
		completionTokens: Int? = null,
		cachedPromptTokens: Int? = null,
		reasoningTokens: Int? = null,
		timeToFirstTokenMs: Long? = null,
		generationMs: Long? = null,
		error: String? = null,
		toolCallsJson: String? = null,
		toolCallId: String? = null,
		toolName: String? = null,
		attachmentsJson: String? = null,
		changedFilesJson: String? = null,
		mutated: Boolean = false,
	): MessageEntity = MessageEntity(
		id = id,
		conversationId = "conversation",
		parentId = null,
		role = role,
		content = content,
		reasoning = reasoning,
		reasoningSignature = reasoningSignature,
		providerState = providerState,
		model = model,
		finishReason = finishReason,
		createdAt = createdAt,
		promptTokens = promptTokens,
		completionTokens = completionTokens,
		cachedPromptTokens = cachedPromptTokens,
		reasoningTokens = reasoningTokens,
		timeToFirstTokenMs = timeToFirstTokenMs,
		generationMs = generationMs,
		selectedChildId = null,
		error = error,
		toolCallsJson = toolCallsJson,
		toolCallId = toolCallId,
		toolName = toolName,
		changedFilesJson = changedFilesJson,
		attachmentsJson = attachmentsJson,
		mutated = mutated,
	)

	private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
		val out = ByteArrayOutputStream()
		ZipOutputStream(out).use { zip ->
			for ((name, bytes) in entries) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(bytes)
				zip.closeEntry()
			}
		}
		return out.toByteArray()
	}

	private fun entryNames(archive: ByteArray): List<String> {
		val names = mutableListOf<String>()
		ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				names += entry.name
				zip.closeEntry()
			}
		}
		return names
	}
}
