package com.inhuman.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMenuTabTest {

	@Test
	fun `All tab shows every section`() {
		for (section in ChatMenuTab.entries) {
			if (section == ChatMenuTab.All) continue
			assertTrue(ChatMenuTab.All.shows(section))
		}
	}

	@Test
	fun `specific tab shows only its own section`() {
		val sections = listOf(
			ChatMenuTab.Conversation,
			ChatMenuTab.Inspect,
			ChatMenuTab.Files,
			ChatMenuTab.Media,
		)
		for (selected in sections) {
			for (target in sections) {
				if (selected == target) {
					assertTrue(selected.shows(target))
				} else {
					assertFalse(selected.shows(target))
				}
			}
		}
	}
}
