package com.inhuman.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings search's one decision: does the typed word reach this section?
 *
 * The regression these pin is a search that only looked at the code, title and
 * summary: a user typing "音色" (inside 02 / Web & media) or "Tailscale" (inside
 * 09 / Local) was told there was no matching section while the setting was two
 * taps away.
 */
class SettingsSearchTest {

	private fun matches(query: String, keywords: String = "") = settingsSectionMatches(
		query = query,
		code = "09 / Local",
		title = "Updates and logs",
		summary = "LAN server, log upload and in-app updates",
		keywords = keywords,
	)

	@Test
	fun `an empty query keeps every section`() {
		assertTrue(matches(""))
		assertTrue(matches("   "))
	}

	@Test
	fun `the code, title and summary still match`() {
		assertTrue(matches("09"))
		assertTrue(matches("Updates"))
		assertTrue(matches("in-app"))
	}

	@Test
	fun `a keyword that no prose contains matches the section`() {
		val keywords = "update server LAN Tailscale address check"
		assertTrue(matches("Tailscale", keywords))
		assertTrue("lower case is the same word", matches("tailscale", keywords))
		// Without the keyword the same word finds nothing, which is the bug.
		assertFalse(matches("Tailscale"))
	}

	@Test
	fun `an unrelated word matches nothing`() {
		assertFalse(matches("banana", "update server LAN Tailscale"))
	}

	@Test
	fun `a query with surrounding whitespace still matches`() {
		assertTrue(matches("  LAN  "))
	}
}
