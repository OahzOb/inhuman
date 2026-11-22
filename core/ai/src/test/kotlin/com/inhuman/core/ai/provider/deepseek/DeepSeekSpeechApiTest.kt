package com.inhuman.core.ai.provider.deepseek

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DeepSeek speech client without a network.
 *
 * The cases that matter are the ones that decide a paid call: the request shape
 * (multipart ASR, JSON TTS), the local refusals (format, size, duration, billed
 * characters) and the truncation check on the TTS WAV. A loopback server pins
 * the shape; the live test covers the endpoint contract.
 */
class DeepSeekSpeechApiTest {

	@Test
	fun `billed characters count Chinese as two and everything else as one`() {
		assertEquals(6, DeepSeekSpeechApi.billedCharacters("你好ab"))
		assertEquals(5, DeepSeekSpeechApi.billedCharacters("ab cd"))
		assertEquals(0, DeepSeekSpeechApi.billedCharacters(""))
	}

	@Test
	fun `a one-second wav reports one second`() {
		val duration = DeepSeekSpeechApi.wavDurationMillis(wav(dataSeconds = 1.0))
		assertEquals(1_000L, duration)
	}

	@Test
	fun `a truncated data chunk is not complete`() {
		val full = wav(dataSeconds = 1.0)
		assertTrue(DeepSeekSpeechApi.wavDataComplete(full))
		assertFalse(DeepSeekSpeechApi.wavDataComplete(full.copyOf(full.size - 64)))
	}

	@Test
	fun `a streamed data chunk of minus one is accepted`() {
		val streamed = wav(dataSeconds = 0.5, dataChunkSize = -1)
		assertTrue(DeepSeekSpeechApi.wavDataComplete(streamed))
		assertEquals(500L, DeepSeekSpeechApi.wavDurationMillis(streamed))
	}

	@Test
	fun `asr posts the file part and returns the transcript`() = runBlocking {
		SpeechServer {
			TestResponse(200, "application/json", """{"request_id":"r","text":"hello","duration_ms":1000}""")
		}.use { server ->
			val api = DeepSeekSpeechApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val result = api.transcribe(wav(dataSeconds = 1.0), "wav")

			assertEquals("hello", result.text)
			val request = server.requests.single()
			assertEquals("POST", request.method)
			assertEquals("/v1/tools/asr", request.path)
			assertEquals("Bearer test-key", request.authorization)
			val contentType = request.contentType.orEmpty()
			assertTrue("not multipart: $contentType", contentType.startsWith("multipart/form-data"))
			assertTrue("no file part", request.bodyText.contains("name=\"file\""))
			assertTrue("no wav filename", request.bodyText.contains("filename=\"speech.wav\""))
			assertTrue("the wav bytes are missing", request.bodyText.contains("RIFF"))
		}
	}

	@Test
	fun `tts posts json and returns wav bytes`() = runBlocking {
		val audio = wav(dataSeconds = 0.5)
		SpeechServer { TestResponse(200, "audio/wav", audio) }.use { server ->
			val api = DeepSeekSpeechApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val result = api.synthesize("你好", voiceId = "tide")

			assertTrue(DeepSeekSpeechApi.looksLikeWav(result.bytes))
			assertEquals("audio/wav", result.mimeType)
			assertEquals("wav", result.extension)
			val request = server.requests.single()
			assertEquals("/v1/tools/tts", request.path)
			assertEquals("Bearer test-key", request.authorization)
			assertTrue(request.contentType.orEmpty().startsWith("application/json"))
			val body = request.bodyUtf8
			assertTrue("format wav was not requested: $body", body.contains("\"format\":\"wav\""))
			assertTrue("the text was not sent: $body", body.contains("你好"))
			assertTrue("the chosen voice was not sent: $body", body.contains("\"voice_id\":\"tide\""))
		}
	}

	@Test
	fun `tts omits voice_id when no voice is chosen`() = runBlocking {
		val audio = wav(dataSeconds = 0.2)
		SpeechServer { TestResponse(200, "audio/wav", audio) }.use { server ->
			val api = DeepSeekSpeechApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			api.synthesize("hello")

			val body = server.requests.single().bodyUtf8
			assertTrue(
				"a default request must not name a voice: $body",
				!body.contains("voice_id"),
			)
		}
	}

	@Test
	fun `a refused synthesis carries the service message`() {
		SpeechServer {
			TestResponse(
				400,
				"application/json",
				"""{"error":{"message":"text did not pass content review"}}""",
			)
		}.use { server ->
			val api = DeepSeekSpeechApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val failure = assertThrows(IOException::class.java) {
				runBlocking { api.synthesize("some text") }
			}
			assertTrue(
				"the service message should survive: ${failure.message}",
				failure.message.orEmpty().contains("content review"),
			)
		}
	}

	@Test
	fun `an unsupported format is refused before any request`() {
		SpeechServer {
			TestResponse(200, "application/json", """{"text":"unexpected"}""")
		}.use { server ->
			val api = DeepSeekSpeechApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val failure = assertThrows(IOException::class.java) {
				runBlocking { api.transcribe(ByteArray(128) { 1 }, "mp3") }
			}
			assertTrue(failure.message.orEmpty().contains("WAV or Ogg Opus"))
			assertEquals(0, server.requests.size)
		}
	}

	@Test
	fun `audio over two minutes is refused before any request`() {
		SpeechServer {
			TestResponse(200, "application/json", """{"text":"unexpected"}""")
		}.use { server ->
			val api = DeepSeekSpeechApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val failure = assertThrows(IOException::class.java) {
				runBlocking { api.transcribe(wav(dataSeconds = 121.0), "wav") }
			}
			assertTrue(failure.message.orEmpty().contains("120"))
			assertEquals(0, server.requests.size)
		}
	}

	@Test
	fun `text over the billed character limit is refused before any request`() {
		SpeechServer {
			TestResponse(200, "audio/wav", wav(dataSeconds = 0.2))
		}.use { server ->
			val api = DeepSeekSpeechApi(
				apiKey = { "test-key" },
				baseUrl = server.baseUrl,
				client = OkHttpClient(),
			)

			val failure = assertThrows(IOException::class.java) {
				runBlocking { api.synthesize("好".repeat(6_000)) }
			}
			assertTrue(failure.message.orEmpty().contains("12000"))
			assertEquals(0, server.requests.size)
		}
	}

	@Test
	fun `a streamed wav is normalized before it is handed on`() {
		val streamed = wav(dataSeconds = 0.5, dataChunkSize = -1)
		val normalized = DeepSeekSpeechApi.normalizeWav(streamed)

		// data chunk size at offset 40 and the RIFF size at offset 4.
		assertEquals(normalized.size - 44, leInt(normalized, 40))
		assertEquals(normalized.size - 8, leInt(normalized, 4))
		assertEquals(500L, DeepSeekSpeechApi.wavDurationMillis(normalized))
	}

	private fun leInt(bytes: ByteArray, offset: Int): Int =
		(bytes[offset].toInt() and 0xff) or
			((bytes[offset + 1].toInt() and 0xff) shl 8) or
			((bytes[offset + 2].toInt() and 0xff) shl 16) or
			((bytes[offset + 3].toInt() and 0xff) shl 24)

	private data class TestResponse(
		val status: Int,
		val contentType: String?,
		val body: String,
	) {
		constructor(status: Int, contentType: String?, bytes: ByteArray) :
			this(status, contentType, String(bytes, Charsets.ISO_8859_1))
	}

	private class SpeechServer(
		private val handler: (Recorded) -> TestResponse,
	) : AutoCloseable {

		data class Recorded(
			val method: String,
			val path: String,
			val authorization: String?,
			val contentType: String?,
			val bodyBytes: ByteArray,
		) {
			/** ISO-8859-1 keeps multipart file bytes one char per byte. */
			val bodyText: String get() = String(bodyBytes, Charsets.ISO_8859_1)

			/** The JSON requests are UTF-8, and their text must be readable. */
			val bodyUtf8: String get() = String(bodyBytes, Charsets.UTF_8)
		}

		private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		val requests = CopyOnWriteArrayList<Recorded>()
		val baseUrl: String

		init {
			http.createContext("/") { exchange ->
				val recorded = Recorded(
					method = exchange.requestMethod,
					path = exchange.requestURI.path,
					authorization = exchange.requestHeaders.getFirst("Authorization"),
					contentType = exchange.requestHeaders.getFirst("Content-Type"),
					bodyBytes = exchange.requestBody.readBytes(),
				)
				requests += recorded
				val response = handler(recorded)
				response.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
				val bytes = response.body.toByteArray(Charsets.ISO_8859_1)
				exchange.sendResponseHeaders(response.status, bytes.size.toLong())
				exchange.responseBody.use { it.write(bytes) }
			}
			http.start()
			baseUrl = "http://127.0.0.1:${http.address.port}"
		}

		override fun close() {
			http.stop(0)
		}
	}

	/**
	 * A PCM WAV, built the way the recorder builds one.
	 *
	 * [dataChunkSize] can override the header's `data` size; `-1` reproduces the
	 * live TTS endpoint's streamed size.
	 */
	private fun wav(
		sampleRate: Int = 16_000,
		channels: Int = 1,
		bitsPerSample: Int = 16,
		dataSeconds: Double = 1.0,
		dataChunkSize: Int? = null,
	): ByteArray {
		val frameBytes = channels * (bitsPerSample / 8)
		val dataBytes = (sampleRate * frameBytes * dataSeconds).toInt()
		val out = ByteArray(44 + dataBytes)

		fun ascii(offset: Int, value: String) {
			value.toByteArray(Charsets.US_ASCII).copyInto(out, offset)
		}

		fun short(offset: Int, value: Int) {
			out[offset] = (value and 0xff).toByte()
			out[offset + 1] = ((value ushr 8) and 0xff).toByte()
		}

		fun int(offset: Int, value: Int) {
			for (index in 0..3) out[offset + index] = ((value ushr (8 * index)) and 0xff).toByte()
		}

		ascii(0, "RIFF")
		int(4, 36 + dataBytes)
		ascii(8, "WAVE")
		ascii(12, "fmt ")
		int(16, 16)
		short(20, 1) // PCM
		short(22, channels)
		int(24, sampleRate)
		int(28, sampleRate * frameBytes)
		short(32, frameBytes)
		short(34, bitsPerSample)
		ascii(36, "data")
		int(40, dataChunkSize ?: dataBytes)
		return out
	}
}
