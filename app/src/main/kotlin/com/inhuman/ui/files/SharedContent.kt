package com.inhuman.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.files.WorkspaceTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * One item another app shared into inhuman.
 *
 * A share is one item by design: the manifest registers `ACTION_SEND`, not
 * `ACTION_SEND_MULTIPLE`, and the MIME filter is text, an image type or an
 * application type -- never a catch-all wildcard. "One file, chosen by the
 * user, copied now" is the whole capability; a multi-item or catch-all entry
 * would turn a share target into a general import channel.
 */
data class SharedContent(
	val mimeType: String,
	/** The stream, when the sender shared a file or image. */
	val uri: Uri?,
	/** The text, when the sender shared plain text. */
	val text: String?,
	/** The sender's display name, when the content provider gives one. */
	val displayName: String?,
)

/**
 * The MIME types the share target accepts.
 *
 * Deliberately not a catch-all wildcard: an exported catch-all would let any
 * app hand the workspace arbitrary content under any type. The application
 * family is broad because "a file" has no narrower common type, but audio and
 * video are not part of the requested entry.
 */
internal fun isShareableMime(mimeType: String?): Boolean {
	val mime = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
	if (mime.isEmpty() || mime == "*/*") return false
	return mime == "text/plain" || mime.startsWith("image/") || mime.startsWith("application/")
}

/**
 * The file name a shared item lands under.
 *
 * A sender-provided name wins, because it is what the user saw in the other
 * app. Separators are flattened so the name is one path segment; the target
 * `WorkspaceTransfer.uniqueName` still handles collisions. A text share has no
 * name, and an unnamed image falls back to its type rather than `shared.bin`.
 */
internal fun sharedFileName(
	mimeType: String?,
	displayName: String?,
	text: String?,
): String {
	displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
		return name.replace('/', '_').replace('\\', '_')
	}
	if (text != null) return "shared.txt"
	val extension = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
		"image/jpeg" -> "jpg"
		"image/png" -> "png"
		"image/webp" -> "webp"
		"image/gif" -> "gif"
		"image/heic" -> "heic"
		"application/pdf" -> "pdf"
		"application/zip" -> "zip"
		else -> "bin"
	}
	return "shared.$extension"
}

/** Read an `ACTION_SEND` intent, or null when it is not a share we accept. */
fun sharedContentFrom(context: Context, intent: Intent?): SharedContent? {
	if (intent?.action != Intent.ACTION_SEND) return null
	val mime = intent.type?.substringBefore(';')?.trim()?.lowercase()
	if (!isShareableMime(mime)) return null
	@Suppress("DEPRECATION")
	val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
	val text = intent.getStringExtra(Intent.EXTRA_TEXT)
	if (uri == null && text.isNullOrBlank()) return null
	val displayName = uri?.let { queryDisplayName(context, it) }
	return SharedContent(
		mimeType = mime.orEmpty(),
		uri = uri,
		text = text,
		displayName = displayName,
	)
}

/**
 * Copy the shared item into the conversation's workspace root.
 *
 * Returns true on success. The file uses the same `WorkspaceTransfer.import`
 * path as an ordinary import, so the 4 MiB per-file ceiling, total quota, file
 * count, free-space reserve and " (2)" collision rule all apply. The URI is
 * read immediately: the share grant is transient, and keeping it for later
 * would be a broken file rather than a copied one.
 */
suspend fun saveSharedContent(
	context: Context,
	repository: ConversationRepository,
	conversationId: String,
	content: SharedContent,
): Boolean = withContext(Dispatchers.IO) {
	val entity = repository.conversation(conversationId)?.entity ?: run {
		Diag.w("share", "save failed: conversation not found")
		return@withContext false
	}
	val workspace = repository.workspace(entity)
	val transfer = WorkspaceTransfer(workspace)
	val name = transfer.uniqueName(
		sharedFileName(content.mimeType, content.displayName, content.text),
	)
	val opener: () -> InputStream = {
		val uri = content.uri
		if (uri != null) {
			context.contentResolver.openInputStream(uri)
				?: error("could not open the shared item")
		} else {
			ByteArrayInputStream(content.text.orEmpty().toByteArray(Charsets.UTF_8))
		}
	}
	when (val outcome = transfer.import(name) { opener() }) {
		is WorkspaceTransfer.Outcome.Ok -> {
			Diag.i("share", "saved path=${outcome.relativePath} bytes=${outcome.bytes}")
			true
		}

		is WorkspaceTransfer.Outcome.Failed -> {
			Diag.w("share", "save failed reason=${outcome.reason}")
			false
		}
	}
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
	runCatching {
		context.contentResolver.query(
			uri,
			arrayOf(OpenableColumns.DISPLAY_NAME),
			null,
			null,
			null,
		)?.use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else null
		}
	}.getOrNull()?.takeIf { it.isNotBlank() }