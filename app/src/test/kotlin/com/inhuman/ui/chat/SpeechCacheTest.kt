package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cached-speech naming rule.
 *
 * The transcript's "already synthesized" mark and the home list's marker both
 * depend on this matching the name `readAloud` writes. A mismatch is invisible
 * until a user taps a reply and is billed again for audio that was on disk, so
 * the rule is pinned here rather than discovered on a device.
 */
class SpeechCacheTest {

	private val reply = "103e6e4a-00ce-4162-bcbd-e820570e8681"

	@Test
	fun prefixIsMessageIdThenVoice() {
		assertEquals(
			"$reply-stella.",
			SpeechCache.prefix(reply, "stella"),
		)
	}

	@Test
	fun nullVoiceUsesTheDefaultToken() {
		assertEquals("$reply-default.", SpeechCache.prefix(reply, null))
	}

	@Test
	fun matchesOnlyTheRequestedVoice() {
		val names = listOf(
			"$reply-stella.wav",
			"${reply}-tide.mp3",
		)

		assertEquals(
			setOf(reply),
			SpeechCache.cachedMessageIds(names, listOf(reply), "stella"),
		)
		assertTrue(SpeechCache.cachedMessageIds(names, listOf(reply), "nova").isEmpty())
	}

	@Test
	fun doesNotMatchAnotherReplyWithTheSamePrefix() {
		val other = "$reply-2"
		val names = listOf("$other-stella.wav")

		assertTrue(SpeechCache.cachedMessageIds(names, listOf(reply), "stella").isEmpty())
	}

	@Test
	fun noFilesMeansNoCachedReplies() {
		assertTrue(
			SpeechCache.cachedMessageIds(emptyList(), listOf(reply), "stella").isEmpty(),
		)
		assertFalse(SpeechCache.hasAnyAudio(emptyList()))
	}

	@Test
	fun anyFinishedFileMarksTheConversation() {
		assertTrue(SpeechCache.hasAnyAudio(listOf("$reply-stella.wav")))
	}

	@Test
	fun unsafeCharactersAreReplaced() {
		assertEquals("a_b_c", SpeechCache.safeFilePart("a/b c"))
		assertEquals(null, SpeechCache.safeFilePart("   "))
	}
}
