package com.inhuman.core.ai.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WAV header rules the speech backend and the player share.
 *
 * These are the shapes a streamed TTS response actually arrives in; each case
 * here was either observed from the live endpoint or is the one shape that
 * reaches a player as a file with no samples (a zeroed `data` size).
 */
class WavTest {

	@Test
	fun `a canonical file needs no repair`() {
		val bytes = wav(seconds = 0.5)
		val facts = Wav.facts(bytes)
		assertNotNull(facts)
		assertNull(Wav.repair(facts!!, bytes.size.toLong()))
		assertNull(Wav.canonicalize(bytes))
	}

	@Test
	fun `a streamed header is given its real sizes`() {
		val streamed = wav(seconds = 0.5, dataSize = -1, riffSize = -1)
		val facts = Wav.facts(streamed)!!
		val repair = Wav.repair(facts, streamed.size.toLong())!!

		assertEquals(streamed.size - 44L, repair.payloadBytes)
		assertEquals(streamed.size - 8L, leInt(repair.header, 4).toLong())
		assertEquals(streamed.size - 44L, leInt(repair.header, 40).toLong())

		val canonical = Wav.canonicalize(streamed)!!
		assertEquals(streamed.size, canonical.size)
		assertEquals(canonical.size - 8, leInt(canonical, 4))
		assertEquals(canonical.size - 44, leInt(canonical, 40))
		assertEquals(500L, facts.durationMillis(streamed.size.toLong()))
	}

	@Test
	fun `a zeroed data size with a payload means read to the end`() {
		// The ambiguity that used to reach the player as an empty file.
		val zeroed = wav(seconds = 0.5, dataSize = 0)
		val facts = Wav.facts(zeroed)!!
		assertEquals(zeroed.size - 44L, facts.payloadBytes(zeroed.size.toLong()))

		val canonical = Wav.canonicalize(zeroed)!!
		assertEquals(zeroed.size - 44, leInt(canonical, 40))
		assertEquals(zeroed.size, canonical.size)
	}

	@Test
	fun `a data size larger than the file is clamped to it`() {
		val overstated = wav(seconds = 0.5, dataSize = 999_999)
		val facts = Wav.facts(overstated)!!
		assertEquals(overstated.size - 44L, facts.payloadBytes(overstated.size.toLong()))
		assertEquals(overstated.size - 44, leInt(Wav.canonicalize(overstated)!!, 40))
	}

	@Test
	fun `chunks after the payload are dropped`() {
		val trailing = wav(seconds = 0.5) + ByteArray(64) { 7 }
		val facts = Wav.facts(trailing)!!
		val repair = Wav.repair(facts, trailing.size.toLong())!!
		assertEquals(trailing.size - 64L - 44L, repair.payloadBytes)
		assertEquals(trailing.size - 64, Wav.canonicalize(trailing)!!.size)
	}

	@Test
	fun `an odd payload gets its pad byte`() {
		val odd = wav(seconds = 0.5, dataBytes = 1_001)
		val canonical = Wav.canonicalize(odd)!!
		assertEquals(44 + 1_001 + 1, canonical.size)
		assertEquals(1_001, leInt(canonical, 40))
		assertEquals(canonical.size - 8, leInt(canonical, 4))
		assertEquals(0, canonical.last().toInt())
	}

	@Test
	fun `an extensible header keeps its own fmt chunk`() {
		val extensible = wav(seconds = 0.2, extensible = true, dataSize = -1)
		val facts = Wav.facts(extensible)!!
		assertEquals(Wav.FORMAT_EXTENSIBLE, facts.formatTag)

		val canonical = Wav.canonicalize(extensible)!!
		// The GUID and channel mask survive because the chunk is copied, not
		// rebuilt from the fields this parser happens to know.
		assertArrayEquals(facts.fmtChunk, canonical.copyOfRange(12, 12 + facts.fmtChunk.size))
		assertEquals(canonical.size - 8, leInt(canonical, 4))
		assertEquals(canonical.size - 12 - facts.fmtChunk.size - 8, leInt(
			canonical,
			12 + facts.fmtChunk.size + 4,
		))
	}

	@Test
	fun `a non-pcm format is left alone`() {
		// ADPCM: the sizes may be wrong, but rewriting the container of a
		// format this app cannot decode would only move the failure.
		val adpcm = wav(seconds = 0.2, formatTag = 2, dataSize = -1)
		val facts = Wav.facts(adpcm)!!
		assertNull(Wav.repair(facts, adpcm.size.toLong()))
		assertNull(Wav.canonicalize(adpcm))
	}

	@Test
	fun `a header that never says its format cannot be repaired`() {
		val zeroed = wav(seconds = 0.2, channels = 0, sampleRate = 0, dataSize = -1)
		val facts = Wav.facts(zeroed)!!
		assertTrue(!facts.plausible)
		assertNull(Wav.canonicalize(zeroed))
	}

	@Test
	fun `not a wav means no facts`() {
		assertNull(Wav.facts(ByteArray(100) { 1 }))
		assertNull(Wav.canonicalize("hello".toByteArray()))
	}

	@Test
	fun `duration follows the declared payload`() {
		val bytes = wav(seconds = 2.0, sampleRate = 24_000)
		val facts = Wav.facts(bytes)!!
		assertEquals(2_000L, facts.durationMillis(bytes.size.toLong()))
	}

	/**
	 * A PCM WAV built the way the TTS endpoint streams one.
	 *
	 * [dataSize] and [riffSize] of -1 write `0xFFFFFFFF`, the streamed marker;
	 * [dataBytes] overrides the payload length so the pad-byte case can be
	 * produced.
	 */
	private fun wav(
		seconds: Double = 1.0,
		sampleRate: Int = 16_000,
		channels: Int = 1,
		bitsPerSample: Int = 16,
		formatTag: Int = 1,
		dataSize: Int? = null,
		riffSize: Int? = null,
		dataBytes: Int? = null,
		extensible: Boolean = false,
	): ByteArray {
		val frameBytes = channels * (bitsPerSample / 8)
		val payload = dataBytes ?: (sampleRate * frameBytes * seconds).toInt()
		val fmtSize = if (extensible) 40 else 16
		val headerSize = 12 + 8 + fmtSize + 8
		val out = ByteArray(headerSize + payload)

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

		val total = if (riffSize == null) out.size - 8 else riffSize
		ascii(0, "RIFF")
		int(4, if (total < 0) -1 else total)
		ascii(8, "WAVE")
		ascii(12, "fmt ")
		int(16, fmtSize)
		short(20, if (extensible) Wav.FORMAT_EXTENSIBLE else formatTag)
		short(22, channels)
		int(24, sampleRate)
		int(28, sampleRate * frameBytes)
		short(32, frameBytes)
		short(34, bitsPerSample)
		if (extensible) {
			short(36, 22)
			short(38, bitsPerSample)
			int(40, 0)
			// KSDATAFORMAT_SUBTYPE_PCM
			int(44, 1)
			int(48, 0)
			int(52, 0x00100000)
			int(56, 0xAA000080.toInt())
			int(60, 0x719B3800)
		}
		ascii(headerSize - 8, "data")
		int(headerSize - 4, dataSize ?: payload)
		return out
	}

	private fun leInt(bytes: ByteArray, offset: Int): Int =
		(bytes[offset].toInt() and 0xff) or
			((bytes[offset + 1].toInt() and 0xff) shl 8) or
			((bytes[offset + 2].toInt() and 0xff) shl 16) or
			((bytes[offset + 3].toInt() and 0xff) shl 24)
}
