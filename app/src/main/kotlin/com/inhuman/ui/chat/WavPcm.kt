package com.inhuman.ui.chat

import java.io.ByteArrayOutputStream

/**
 * The WAV container, isolated from the microphone.
 *
 * A recorder that writes a broken header produces a file the ASR service rejects
 * with an unhelpful error; this is pure and testable on the JVM.
 */
internal object WavPcm {

	const val SAMPLE_RATE = 16_000

	fun wrap(pcm: ByteArray): ByteArray {
		val out = ByteArrayOutputStream(pcm.size + 44)
		fun le(value: Int) {
			out.write(value and 0xff)
			out.write((value ushr 8) and 0xff)
			out.write((value ushr 16) and 0xff)
			out.write((value ushr 24) and 0xff)
		}
		fun short(value: Int) {
			out.write(value and 0xff)
			out.write((value ushr 8) and 0xff)
		}
		out.write("RIFF".toByteArray(Charsets.US_ASCII))
		le(36 + pcm.size)
		out.write("WAVE".toByteArray(Charsets.US_ASCII))
		out.write("fmt ".toByteArray(Charsets.US_ASCII))
		le(16)
		short(1) // PCM
		short(1) // mono
		le(SAMPLE_RATE)
		le(SAMPLE_RATE * 2)
		short(2)
		short(16)
		out.write("data".toByteArray(Charsets.US_ASCII))
		le(pcm.size)
		out.write(pcm)
		return out.toByteArray()
	}
}