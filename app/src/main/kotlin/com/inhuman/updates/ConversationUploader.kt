package com.inhuman.updates

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One explicit upload of a conversation's Markdown to the configured server.
 *
 * The agent has no path here, and this is not called by the updater or by
 * diagnostics. It is the user pressing **Upload conversation** and confirming
 * the preview: the body is the transcript, so unlike `/log` it contains
 * message text. The endpoint is separate (`/conversation`) and the server
 * stores it under `logs/conversations/`.
 *
 * There is no retry and no background job. A failed upload leaves the
 * conversation on the device; the dialog offers Upload again.
 */
object ConversationUploader {

    /**
     * The same ceiling as `dev-server.py`. Refusing here keeps a very long
     * transcript from being written to the socket only to learn at the end that
     * the server rejected it.
     */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * A conversation can be large, but the server caps a request at 8 MiB.
     * Connect quickly and allow time to write the body; the read is only the
     * short saved-file name.
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Returns the server's saved-file name. Throws on any failure. */
    fun upload(base: String, markdown: String, version: String): String {
        val bytes = markdown.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "conversation is larger than 8 MB" }
        val body = bytes.toRequestBody("text/markdown; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(conversationUploadUrl(base))
            .post(body)
            .header("X-Inhuman-Version", version)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                error("HTTP ${response.code} ${text.take(200)}")
            }
            return text.ifBlank { "ok" }
        }
    }
}
