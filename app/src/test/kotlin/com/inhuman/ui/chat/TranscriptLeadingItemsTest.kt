package com.inhuman.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptLeadingItemsTest {

	@Test
	fun `no banners renders no leading items`() {
		assertEquals(
			0,
			transcriptLeadingItems(
				continuous = true,
				compaction = false,
				compacting = false,
				maintaining = false,
			),
		)
	}

	@Test
	fun `each classic banner occupies one item`() {
		assertEquals(
			1,
			transcriptLeadingItems(
				continuous = false,
				compaction = true,
				compacting = false,
				maintaining = false,
			),
		)
		assertEquals(
			1,
			transcriptLeadingItems(
				continuous = false,
				compaction = false,
				compacting = true,
				maintaining = false,
			),
		)
		assertEquals(
			2,
			transcriptLeadingItems(
				continuous = false,
				compaction = true,
				compacting = true,
				maintaining = false,
			),
		)
	}

	@Test
	fun `classic banners do not render in continuous mode`() {
		// The LazyColumn gates both classic rows on !continuous; stale
		// compaction state must not keep counting after a mode switch.
		assertEquals(
			0,
			transcriptLeadingItems(
				continuous = true,
				compaction = true,
				compacting = true,
				maintaining = false,
			),
		)
	}

	@Test
	fun `the maintenance banner is a third leading item`() {
		// The count was two before continuous sessions existed; the
		// maintenance row makes it three while visible. This is the drift
		// the named function exists to pin.
		assertEquals(
			1,
			transcriptLeadingItems(
				continuous = true,
				compaction = false,
				compacting = false,
				maintaining = true,
			),
		)
		assertEquals(
			3,
			transcriptLeadingItems(
				continuous = false,
				compaction = true,
				compacting = true,
				maintaining = true,
			),
		)
	}
}
