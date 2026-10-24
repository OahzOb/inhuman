package com.inhuman.desktop

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decode, downscale and re-encode a user-selected image so it fits the
 * workspace read limit before it is sent to a provider.
 *
 * Android uses `BitmapFactory`; the desktop has ImageIO. The contract is the
 * same: reject non-images, bound the source size, cap the long edge, and keep
 * trying lower quality/lower resolution until the bytes fit. A raw screenshot
 * is routinely larger than the 1 MiB read ceiling, so copying it unchanged
 * would produce an attachment the request builder cannot read.
 */
object DesktopImages {
	const val MAX_SOURCE_BYTES = 24L * 1024 * 1024
	private const val MAX_SOURCE_DIMENSION = 12_000
	private const val MAX_LONG_EDGE = 1_600

	data class Prepared(
		val bytes: ByteArray,
		val mimeType: String,
		val width: Int,
		val height: Int,
	)

	fun prepare(source: File, maxBytes: Long): Prepared {
		require(source.isFile) { "not a file" }
		require(source.length() <= MAX_SOURCE_BYTES) { "image is larger than 24 MB" }
		val decoded = ImageIO.read(source) ?: error("unsupported image format")
		require(decoded.width in 1..MAX_SOURCE_DIMENSION) { "image is too wide" }
		require(decoded.height in 1..MAX_SOURCE_DIMENSION) { "image is too tall" }

		var scale = min(1.0, MAX_LONG_EDGE.toDouble() / max(decoded.width, decoded.height))
		repeat(6) {
			val width = (decoded.width * scale).roundToInt().coerceAtLeast(1)
			val height = (decoded.height * scale).roundToInt().coerceAtLeast(1)
			val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
			val graphics = rgb.createGraphics()
			try {
				graphics.setRenderingHint(
					RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR,
				)
				graphics.setRenderingHint(
					RenderingHints.KEY_RENDERING,
					RenderingHints.VALUE_RENDER_QUALITY,
				)
				// JPEG has no alpha; a white matte avoids transparent pixels
				// turning black, which is what ImageIO does by default.
				graphics.color = Color.WHITE
				graphics.fillRect(0, 0, width, height)
				graphics.drawImage(decoded, 0, 0, width, height, null)
			} finally {
				graphics.dispose()
			}
			for (quality in listOf(0.85f, 0.7f, 0.55f, 0.4f)) {
				val bytes = encodeJpeg(rgb, quality)
				if (bytes.size <= maxBytes) {
					return Prepared(bytes, "image/jpeg", width, height)
				}
			}
			scale *= 0.75
		}
		error("image is still larger than ${maxBytes / 1024} KB after resizing")
	}

	private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
		val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
		val output = ByteArrayOutputStream()
		try {
			writer.output = MemoryCacheImageOutputStream(output)
			val params = writer.defaultWriteParam.apply {
				compressionMode = ImageWriteParam.MODE_EXPLICIT
				compressionQuality = quality
			}
			writer.write(null, IIOImage(image, null, null), params)
		} finally {
			writer.dispose()
		}
		return output.toByteArray()
	}
}
