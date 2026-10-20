package com.inhuman.ui.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The rules that decide whether a picked file can become a system prompt.
 *
 * The provider MIME type is not trustworthy for `.md` -- some report
 * `text/markdown`, some `application/octet-stream` -- so the extension matters,
 * and a text MIME keeps extensionless prompt files usable. Decoding is strict:
 * a prompt with U+FFFD scattered through it is worse than an error.
 */
class PromptImportTest {

	@Test
	fun `markdown is accepted even when the provider says octet-stream`() {
		assertTrue(isPromptText("rules.md", "application/octet-stream"))
		assertTrue(isPromptText("rules.markdown", null))
		assertTrue(isPromptText("rules.MD", "application/octet-stream"))
	}

	@Test
	fun `text files are accepted`() {
		assertTrue(isPromptText("prompt.txt", null))
		assertTrue(isPromptText("notes", "text/plain"))
	}

	@Test
	fun `binary files are rejected`() {
		assertFalse(isPromptText("photo.png", "image/png"))
		assertFalse(isPromptText("archive.zip", "application/zip"))
		assertFalse(isPromptText("no-extension", "application/octet-stream"))
	}

	@Test
	fun `a file over the read guard is rejected`() {
		val tooBig = ByteArray(MAX_PROMPT_IMPORT_BYTES + 1)
		try {
			readPromptBytes(tooBig.inputStream())
			throw AssertionError("expected IOException")
		} catch (expected: IOException) {
			assertTrue(expected.message!!.contains("1 MiB"))
		}
	}

	@Test
	fun `utf8 text decodes`() {
		assertEquals("用中文写，也可以有 emoji 🙂", decodePromptText("用中文写，也可以有 emoji 🙂".toByteArray()))
	}

	@Test
	fun `a byte order mark is stripped`() {
		assertEquals("hello", decodePromptText("\uFEFFhello".toByteArray()))
	}

	@Test
	fun `invalid utf8 is rejected`() {
		val invalid = byteArrayOf(0xC3.toByte(), 0x28)
		try {
			decodePromptText(invalid)
			throw AssertionError("expected IOException")
		} catch (expected: IOException) {
			assertTrue(expected.message!!.contains("UTF-8"))
		}
	}

	@Test
	fun `a blank file is rejected`() {
		try {
			decodePromptText(" \n\t ".toByteArray())
			throw AssertionError("expected IOException")
		} catch (expected: IOException) {
			assertTrue(expected.message!!.contains("empty"))
		}
	}
}
