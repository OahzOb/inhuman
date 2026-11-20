package com.inhuman.ui.bilibili

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Draws the Bilibili login QR in the app.
 *
 * The payload is a Bilibili-owned URL containing a one-time login key; nothing
 * secret is encoded (the key only works while unconfirmed and expires in about
 * three minutes). The bitmap is generated locally and never leaves the device,
 * which is also why no third-party QR image service is used: sending the login
 * URL to one would hand it a live half-credential.
 */
object BiliQrRenderer {

	/**
	 * Encode [content] as a square QR bitmap.
	 *
	 * @param sizePx edge length in pixels. 720 is crisp on a phone dialog while
	 *   staying under a megabyte of ARGB_8888.
	 */
	fun encode(content: String, sizePx: Int = 720): Bitmap {
		val matrix = QRCodeWriter().encode(
			content,
			BarcodeFormat.QR_CODE,
			sizePx,
			sizePx,
			mapOf(EncodeHintType.MARGIN to 1),
		)
		val pixels = IntArray(sizePx * sizePx)
		for (y in 0 until sizePx) {
			val row = y * sizePx
			for (x in 0 until sizePx) {
				pixels[row + x] = if (matrix[x, y]) BLACK else WHITE
			}
		}
		return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
	}

	private const val BLACK = 0xFF000000.toInt()
	private const val WHITE = 0xFFFFFFFF.toInt()
}
