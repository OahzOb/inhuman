package com.inhuman.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A width/height pair with no Android dependency, so the arithmetic is testable on the JVM. */
internal data class PixelSize(val width: Int, val height: Int)

/**
 * The size decisions behind image preparation.
 *
 * Kept separate from [ImagePreprocessor] because `Bitmap` does not exist under
 * the JVM tests and these two rules are the ones that were easy to get wrong:
 * upscaling a small image, and decoding a 48 MP photo at full resolution before
 * scaling it down.
 */
internal object ImageSizing {

	/** Fit inside a square of [maxDimension], preserving aspect ratio and never upscaling. */
	fun fitWithin(width: Int, height: Int, maxDimension: Int): PixelSize {
		require(width > 0 && height > 0 && maxDimension > 0)
		val longest = max(width, height)
		if (longest <= maxDimension) return PixelSize(width, height)
		val scale = maxDimension.toDouble() / longest
		return PixelSize(
			(width * scale).roundToInt().coerceAtLeast(1),
			(height * scale).roundToInt().coerceAtLeast(1),
		)
	}

	/**
	 * The power-of-two `inSampleSize` that gets the decoded bitmap near the target.
	 *
	 * `BitmapFactory` only honours powers of two efficiently, and decoding the
	 * full 4032×3024 before `createScaledBitmap` is what turns a phone photo into
	 * a 48 MB allocation and, on a low-memory device, an OOM. The factor of two
	 * is deliberate: the final scale is high quality, while the decode is at
	 * most twice the target edge.
	 */
	fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
		require(width > 0 && height > 0 && maxDimension > 0)
		var sample = 1
		while (max(width, height) / sample > maxDimension * 2) {
			sample *= 2
		}
		return sample
	}
}

internal data class PreparedImage(
	val bytes: ByteArray,
	val mimeType: String,
	val width: Int,
	val height: Int,
	val quality: Int,
) {
	/**
	 * A filename whose extension matches the encoded format.
	 *
	 * The original extension lies after re-encoding: a PNG becomes WebP, a HEIC
	 * becomes JPEG. Keeping the old extension would make Files offer the wrong
	 * viewer and would put the wrong type in the request.
	 */
	fun fileName(sourceName: String): String {
		val name = sourceName.substringAfterLast('/').substringAfterLast('\\')
		val base = name.substringBeforeLast('.', name).ifBlank { "image" }.take(80)
		return "$base.${if (mimeType == "image/webp") "webp" else "jpg"}"
	}
}

/**
 * Turn whatever the picker returned into a bounded, correctly oriented image.
 *
 * ## Why the app resizes before the provider does
 *
 * Every vision provider resizes anyway -- DeepSeek scales to roughly a
 * 1300×1300 pixel budget and caps an image at 1024 tokens; Claude caps the long
 * edge and bills by area -- so this is **not** about token price. It is about
 * the phone-side request: the image is base64-inlined into the chat request,
 * which means the bytes are held at least twice, and a 12 MP JPEG is a 48 MB
 * bitmap before it is even re-encoded. It also keeps the app independent of the
 * provider's per-image limit (Claude 5 MB, DeepSeek 32 MB inline) and of the
 * request-body limit (DeepSeek 48 MiB).
 *
 * The stored copy is the prepared one. The original is not kept: the
 * transcript only stores the path, and retaining both would double the
 * workspace and quota use for an image the model will never see at full size.
 * A picked file's untouched original still exists wherever it was picked from.
 * A camera capture has no such copy -- its cache original is deleted after
 * staging -- so the prepared image is the only one.
 *
 * ## Format
 *
 * Opaque photos become JPEG at quality 85; PNG/WebP/GIF sources and anything
 * with an alpha channel become WebP at 90. Text screenshots gain more from
 * WebP than from JPEG, which turns sharp glyph edges into ringing artefacts.
 * WebP is accepted by DeepSeek, Claude, OpenAI and Gemini.
 */
internal object ImagePreprocessor {

	/** Long edge of the prepared image. */
	const val MAX_DIMENSION = 1568

	/** Lowest quality tried before the image is scaled down again. */
	private const val MIN_QUALITY = 40

	/** How many times the dimensions may be reduced by 20% before giving up. */
	private const val MAX_REDUCTIONS = 3

	fun prepare(
		open: () -> InputStream,
		sourceMime: String,
		maxBytes: Long,
	): PreparedImage {
		require(maxBytes > 0) { "maxBytes must be positive" }
		val bounds = readBounds(open)
		val orientation = readOrientation(open)
		val sample = ImageSizing.sampleSize(bounds.width, bounds.height, MAX_DIMENSION)
		val decoded = decode(open, sample)

		var current = orient(decoded, orientation)
		if (current !== decoded) decoded.recycle()

		val target = ImageSizing.fitWithin(current.width, current.height, MAX_DIMENSION)
		if (target.width != current.width || target.height != current.height) {
			val scaled = Bitmap.createScaledBitmap(current, target.width, target.height, true)
			if (scaled !== current) current.recycle()
			current = scaled
		}

		val useWebp = current.hasAlpha() ||
			sourceMime.equals("image/png", ignoreCase = true) ||
			sourceMime.equals("image/webp", ignoreCase = true) ||
			sourceMime.equals("image/gif", ignoreCase = true)
		val format = if (useWebp) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
		val mime = if (useWebp) "image/webp" else "image/jpeg"

		try {
			repeat(MAX_REDUCTIONS + 1) { reduction ->
				var quality = if (useWebp) 90 else 85
				while (quality >= MIN_QUALITY) {
					val bytes = encode(current, format, quality)
					if (bytes.size <= maxBytes) {
						return PreparedImage(
							bytes = bytes,
							mimeType = mime,
							width = current.width,
							height = current.height,
							quality = quality,
						)
					}
					quality -= 10
				}
				if (reduction < MAX_REDUCTIONS) {
					val reduced = ImageSizing.fitWithin(
						current.width,
						current.height,
						max(1, (max(current.width, current.height) * 0.8f).roundToInt()),
					)
					val next = Bitmap.createScaledBitmap(current, reduced.width, reduced.height, true)
					if (next !== current) current.recycle()
					current = next
				}
			}
		} finally {
			current.recycle()
		}
		throw IllegalStateException("image could not be compressed below $maxBytes bytes")
	}

	private fun readBounds(open: () -> InputStream): PixelSize {
		val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		open().use { BitmapFactory.decodeStream(it, null, options) }
		if (options.outWidth <= 0 || options.outHeight <= 0) {
			throw IllegalArgumentException("not a readable image")
		}
		return PixelSize(options.outWidth, options.outHeight)
	}

	private fun readOrientation(open: () -> InputStream): Int = runCatching {
		open().use { stream ->
			ExifInterface(stream).getAttributeInt(
				ExifInterface.TAG_ORIENTATION,
				ExifInterface.ORIENTATION_NORMAL,
			)
		}
	}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

	private fun decode(open: () -> InputStream, sample: Int): Bitmap {
		val options = BitmapFactory.Options().apply {
			inSampleSize = sample
			inPreferredConfig = Bitmap.Config.ARGB_8888
		}
		val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) }
			?: throw IllegalArgumentException("not a readable image")
		return bitmap
	}

	private fun orient(source: Bitmap, orientation: Int): Bitmap {
		val matrix = Matrix()
		when (orientation) {
			ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
			ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
			ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
			ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
			ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
			ExifInterface.ORIENTATION_TRANSPOSE -> {
				matrix.postRotate(90f)
				matrix.postScale(-1f, 1f)
			}
			ExifInterface.ORIENTATION_TRANSVERSE -> {
				matrix.postRotate(270f)
				matrix.postScale(-1f, 1f)
			}
			else -> return source
		}
		return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
	}

	private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
		val out = ByteArrayOutputStream()
		check(bitmap.compress(format, quality, out)) { "could not encode the image" }
		return out.toByteArray()
	}
}
