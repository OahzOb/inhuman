package com.inhuman.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Side of the thumbnail drawn in the transcript. */
internal const val ATTACHMENT_PREVIEW_DP = 96

/**
 * Ceiling on the bytes a preview may read.
 *
 * The same order as the workspace's own per-file write limit: a preview is a
 * convenience, and a conversation holding a 60 MiB camera raw must fall back to
 * the file-name chip rather than decode it into the transcript.
 */
internal const val MAX_ATTACHMENT_PREVIEW_BYTES = 8L * 1024 * 1024

/** Longest side the bitmap is sampled down to before it reaches the image. */
internal const val ATTACHMENT_PREVIEW_TARGET_PX = 384

private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

/**
 * Whether an attachment should be drawn as a thumbnail.
 *
 * The MIME type decides when it is present, because that is what the picker
 * reported; the extension is the fallback for a provider that gave none.
 * `application/octet-stream` with a `.png` name is an image here, and a
 * `text/plain` file named `notes.txt` never is -- the preview must not try to
 * decode prose.
 */
internal fun isImageAttachment(mimeType: String, displayName: String): Boolean {
	if (mimeType.startsWith("image/", ignoreCase = true)) return true
	val extension = displayName.substringAfterLast('.', "").lowercase()
	return extension in imageExtensions
}

/**
 * The `inSampleSize` that brings [width]x[height] under [targetPx] on its long
 * side. Always a power of two, never zero -- `BitmapFactory` requires both.
 *
 * Extracted and tested because the arithmetic is the part that is easy to get
 * silently wrong: a sample size of 1 on a 4000px photo is a 64 MiB allocation,
 * and a zero is a crash inside the decoder.
 */
internal fun previewInSampleSize(width: Int, height: Int, targetPx: Int = ATTACHMENT_PREVIEW_TARGET_PX): Int {
	if (width <= 0 || height <= 0 || targetPx <= 0) return 1
	var sample = 1
	var longest = maxOf(width, height)
	while (longest / 2 >= targetPx) {
		longest /= 2
		sample *= 2
	}
	return sample
}

/**
 * Decode [bytes] as a bitmap sampled for a thumbnail, or null when they are
 * not an image the platform can read.
 *
 * Two passes on purpose: the first reads only the size, the second allocates
 * the sampled bitmap. Decoding first and scaling after would allocate the full
 * image, which is exactly the cost a transcript must not pay while scrolling.
 */
internal fun decodeAttachmentPreview(bytes: ByteArray): ImageBitmap? {
	val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
	BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
	val sample = previewInSampleSize(bounds.outWidth, bounds.outHeight)
	val options = BitmapFactory.Options().apply { inSampleSize = sample }
	return runCatching {
		BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
	}.getOrNull()
}
