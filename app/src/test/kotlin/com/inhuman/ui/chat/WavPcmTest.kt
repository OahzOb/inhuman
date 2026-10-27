package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The WAV header must be exactly what an ASR endpoint expects. */
class WavPcmTest {

	@Test
	fun headerDescribesMono16BitPcm() {
		val pcm = ByteArray(320) // 10 ms at 16 kHz, 16-bit
		val wav = WavPcm.wrap(pcm)

		assertEquals(44 + pcm.size, wav.size)
		assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
		assertEquals(36 + pcm.size, le32(wav, 4))
		assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
		assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
		assertEquals(16, le32(wav, 16))
		assertEquals(1, le16(wav, 20)) // PCM
		assertEquals(1, le16(wav, 22)) // mono
		assertEquals(16_000, le32(wav, 24))
		assertEquals(32_000, le32(wav, 28))
		assertEquals(2, le16(wav, 32))
		assertEquals(16, le16(wav, 34))
		assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
		assertEquals(pcm.size, le32(wav, 40))
	}

	@Test
	fun anEmptyRecordingStillProducesAValidContainer() {
		val wav = WavPcm.wrap(ByteArray(0))
		assertEquals(44, wav.size)
		assertTrue(le32(wav, 40) == 0)
	}

	private fun le16(bytes: ByteArray, offset: Int): Int =
		(bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

	private fun le32(bytes: ByteArray, offset: Int): Int =
		le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)
}