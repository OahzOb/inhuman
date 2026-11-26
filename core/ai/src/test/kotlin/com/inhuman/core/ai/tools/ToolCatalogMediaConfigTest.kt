package com.inhuman.core.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media configuration gate on [ToolCatalog.visible].
 *
 * `docs/13-product.md` promises that a media tool whose backend is unsupported
 * or unconfigured "does not appear in the list", while the gap report is
 * allowed to say it needs configuration. That is two different universes from
 * one function: `null` (a non-picker caller) keeps the row, a real set removes
 * what is not in it. These tests pin both halves so a later "simplification"
 * cannot collapse them into one.
 */
class ToolCatalogMediaConfigTest {

	private val mediaNames = setOf(
		"decide",
		"transcribe",
		"generate_image",
		"generate_video",
		"video_status",
		"speak",
	)

	@Test
	fun `every media tool is flagged, and nothing else is`() {
		assertEquals(mediaNames, ToolCatalog.mediaConfigGatedNames)
	}

	@Test
	fun `the media tools are paid or workspace bound, not remote or opt-in`() {
		// The gate is about configuration, so these rows must not also carry
		// the capability-switch flags: a media tool is selectable the moment
		// its backend resolves.
		for (entry in ToolCatalog.all.filter { it.gatedByMediaConfig }) {
			assertFalse(entry.name, entry.remote)
			assertFalse(entry.name, entry.gatedBySsh)
			assertFalse(entry.name, entry.gatedByBilibili)
			assertFalse(entry.name, entry.gatedByDownload)
		}
	}

	@Test
	fun `a null set keeps every media row for non-picker callers`() {
		val offered = ToolCatalog.visible(includeSsh = true, mediaConfigured = null)
		for (name in mediaNames) {
			assertTrue(name, offered.any { it.name == name })
		}
	}

	@Test
	fun `an empty set removes every unconfigured media row`() {
		val offered = ToolCatalog.visible(includeSsh = true, mediaConfigured = emptySet())
		for (name in mediaNames) {
			assertFalse(name, offered.any { it.name == name })
		}
	}

	@Test
	fun `only the configured media rows survive`() {
		val offered = ToolCatalog.visible(includeSsh = true, mediaConfigured = setOf("speak"))
		assertTrue(offered.any { it.name == "speak" })
		assertFalse(offered.any { it.name == "generate_image" })
	}

	@Test
	fun `the gate does not touch non-media tools`() {
		val offered = ToolCatalog.visible(
			includeSsh = true,
			includeDownload = true,
			mediaConfigured = emptySet(),
		)
		assertTrue(offered.any { it.name == "read_file" })
		assertTrue(offered.any { it.name == "web_search" })
		assertTrue(offered.any { it.name == "download_url" })
	}
}
