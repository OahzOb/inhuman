package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.common.diag.Diag
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek's OCR API, called directly.
 *
 * `POST /v1/tools/ocr` takes one file plus a `purpose` and returns the text of
 * each page. The file is recognized synchronously: the service sends newline
 * keep-alives while it works, then one JSON object, and the whole call must
 * finish inside 180 seconds. The file is not stored in the Files API and is not
 * passed to a chat model ([OCR guide](https://api-docs.deepseek.com/guides/ocr.md)).
 *
 * ## This is a read, not a conversion
 *
 * The app has local readers for PDF/DOC/XLS (see `docs/14-documents-voice.md`).
 * This client exists for the formats those readers cannot read: a scanned page
 * with no text layer, and a photo or screenshot. It returns text only; it never
 * writes a file and it does not make the document a remote document — the bytes
 * are request payload.
 *
 * ## Why the client has its own HTTP client
 *
 * The chat client carries `HeaderTimeoutInterceptor`, which cancels a call that
 * has not received response headers within 90 seconds. OCR uploads the file and
 * only then starts answering, and the service's own ceiling is 180 seconds, so
 * a long scan would be reported as "no response" by the chat rule. This client
 * therefore has explicit, longer timeouts and no header-timeout interceptor.
 */
class DeepSeekOcrApi(
	/** Read per call rather than captured, so a key added later is picked up. */
	private val apiKey: () -> String?,
	private val baseUrl: String = DEFAULT_BASE_URL,
	/**
	 * A process-wide client, not one per tool build: OCR is resolved whenever a
	 * conversation is opened, and a client per turn would throw away the
	 * connection pool. Tests pass their own client.
	 */
	private val client: OkHttpClient = shared,
) {

	/**
	 * Recognize one PDF or image.
	 *
	 * [purpose] is `text_recognition` by default. The rich-layout purpose also
	 * crops figures, but the app has no way to show them to the model here, so
	 * only text is requested.
	 */
	suspend fun recognize(
		file: ByteArray,
		filename: String,
		purpose: String = TEXT_RECOGNITION,
	): DeepSeekOcrResult = withContext(Dispatchers.IO) {
		val key = apiKey()?.takeIf { it.isNotBlank() }
			?: throw IOException("no DeepSeek API key is configured")
		if (purpose != TEXT_RECOGNITION && purpose != RICH_LAYOUT_RECOGNITION) {
			throw IllegalArgumentException("unsupported OCR purpose: $purpose")
		}
		if (file.isEmpty()) throw IOException("the file to recognize is empty")
		if (file.size > MAX_FILE_BYTES) {
			throw IOException(
				"the file is ${file.size / 1024 / 1024} MiB; DeepSeek OCR accepts " +
					"at most ${MAX_FILE_BYTES / 1024 / 1024} MiB",
			)
		}
		val safeName = filename.trim().ifBlank { "document" }

		Diag.i("ocr", "request purpose=$purpose bytes=${file.size} ext=${safeName.substringAfterLast('.', "")}")
		val body = MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			// The API requires `purpose`; the guide's own example sends it
			// before the file, and the Files API treats the order as meaningful.
			.addFormDataPart("purpose", purpose)
			.addFormDataPart("file", safeName, file.toRequestBody(mimeFor(safeName)))
			.build()
		val request = Request.Builder()
			.url("${baseUrl.trimEnd('/')}/v1/tools/ocr")
			.header("Authorization", "Bearer $key")
			.post(body)
			.build()

		client.newCall(request).execute().use { response ->
			val text = response.body.string()
			Diag.i("ocr", "response HTTP ${response.code} chars=${text.length}")
			if (!response.isSuccessful) {
				throw failure("OCR", response.code, response.message, text)
			}
			parseResult(text)
		}
	}

	private fun failure(action: String, code: Int, message: String, body: String): IOException {
		Diag.w("ocr", "$action HTTP $code ${message}: ${body.take(200)}")
		return IOException("$action failed: HTTP $code $message — ${body.take(300)}")
	}

	companion object {

		const val DEFAULT_BASE_URL = "https://api.deepseek.com"

		const val TEXT_RECOGNITION = "text_recognition"
		const val RICH_LAYOUT_RECOGNITION = "rich_layout_recognition"

		/** The API's own upload ceiling; the tool layer caps lower than this. */
		const val MAX_FILE_BYTES = 100L * 1024 * 1024

		private val PDF = "application/pdf".toMediaType()
		private val PNG = "image/png".toMediaType()
		private val JPEG = "image/jpeg".toMediaType()
		private val WEBP = "image/webp".toMediaType()
		private val OCTET = "application/octet-stream".toMediaType()

		/** Presentation tokens the API wraps around located text. */
		private val LOCATOR_TAG = Regex("<｜[^｜]*｜>")

		/**
		 * The coordinate array that follows a `box` token, e.g.
		 * `[[52, 40, 948, 96]]`. It is a location, not content.
		 */
		private val BOX_COORDINATES =
			Regex("\\[\\[\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*\\]\\]")

		internal fun mimeFor(filename: String) = when (filename.substringAfterLast('.', "").lowercase()) {
			"pdf" -> PDF
			"png" -> PNG
			"jpg", "jpeg" -> JPEG
			"webp" -> WEBP
			else -> OCTET
		}

		/**
		 * Turn one page's raw OCR output into text for the model.
		 *
		 * The service wraps located text in `<｜ref｜>…</｜ref｜>` and
		 * `<｜box｜>[[x1, y1, x2, y2]]</｜box｜>` tokens so a client can crop or
		 * highlight. The app keeps no layout model and shows no page image, so
		 * the tokens and coordinates are noise that would push the actual text
		 * apart.
		 */
		internal fun cleanPageText(raw: String): String {
			if (raw.isEmpty()) return ""
			return raw
				.replace(LOCATOR_TAG, "")
				.replace(BOX_COORDINATES, "")
				.replace("\r\n", "\n")
				.replace('\r', '\n')
				.lines()
				.joinToString("\n") { it.trimEnd() }
				.replace(Regex("\\n{3,}"), "\n\n")
				.trim()
		}

		/**
		 * Parse one OCR response body.
		 *
		 * A 200 response can still be an error object when recognition started
		 * (the guide says so), so `error` is checked before `pages`.
		 */
		internal fun parseResult(body: String): DeepSeekOcrResult {
			val trimmed = body.trim()
			if (trimmed.isEmpty()) throw IOException("OCR returned an empty response")
			val root = runCatching {
				DeepSeekJson.parseToJsonElement(trimmed).jsonObject
			}.getOrNull() ?: throw IOException("OCR returned a non-JSON response: ${trimmed.take(160)}")

			val error = root["error"]?.runCatching { jsonObject }?.getOrNull()
			if (error != null) {
				val message = error.string("message") ?: "unknown error"
				throw IOException("OCR failed: $message")
			}

			val pageCount = root.string("page_count")?.toIntOrNull() ?: 0
			val rawPages = root["pages"] as? JsonArray ?: JsonArray(emptyList())
			val pages = rawPages.mapNotNull { element ->
				val page = element as? JsonObject ?: return@mapNotNull null
				DeepSeekOcrPage(
					index = page.string("index")?.toIntOrNull() ?: -1,
					text = cleanPageText(page.string("text").orEmpty()),
					truncated = page.string("finish_reason") == "length",
				)
			}
			if (pages.isEmpty() && pageCount <= 0) throw IOException("OCR returned no pages")
			return DeepSeekOcrResult(
				pageCount = if (pageCount > 0) pageCount else pages.size,
				pages = pages,
			)
		}

		private fun JsonObject.string(name: String): String? =
			(this[name] as? JsonPrimitive)?.contentOrNull

		/**
		 * The OCR client.
		 *
		 * No header-timeout interceptor: see the class comment. The read timeout
		 * covers the API's 180-second total plus a slow upload.
		 */
		internal val shared: OkHttpClient by lazy {
			OkHttpClient.Builder()
				.connectTimeout(20, TimeUnit.SECONDS)
				.writeTimeout(180, TimeUnit.SECONDS)
				.readTimeout(300, TimeUnit.SECONDS)
				.build()
		}
	}

	data class DeepSeekOcrPage(
		val index: Int,
		val text: String,
		/** The page's output hit the model's limit; the text is incomplete. */
		val truncated: Boolean,
	)

	data class DeepSeekOcrResult(
		val pageCount: Int,
		val pages: List<DeepSeekOcrPage>,
	) {
		override fun equals(other: Any?): Boolean =
			this === other || (other is DeepSeekOcrResult &&
				pageCount == other.pageCount &&
				pages == other.pages)

		override fun hashCode(): Int = 31 * pageCount + pages.hashCode()
	}
}
