package com.inhuman.ui.common

import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.tools.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The families and the master controls over them.
 *
 * The grouping is what lets three screens offer forty-odd tools as a handful of
 * switches. These tests pin the two properties the screens rely on: every row
 * lands in exactly one family, and a master control means the same thing as
 * ticking every name in it -- including the opt-in and remote names that `null`
 * ("all") deliberately refuses.
 */
class ToolGroupsTest {

	private val byName = { name: String -> ToolCatalog.all.first { it.name == name } }

	@Test
	fun `the families partition the catalogue in display order`() {
		val groups = toolGroups(ToolCatalog.all)
		val flat = groups.flatMap { group -> group.entries.map { it.name } }
		assertEquals(ToolCatalog.names.sorted(), flat.sorted())
		assertEquals(groups.map { it.family }, groups.map { it.family }.sortedBy { it.ordinal })
		groups.forEach { group -> assertTrue(group.entries.isNotEmpty()) }
	}

	@Test
	fun `the ssh family is split at the irreversible half`() {
		val read = toolGroups(ToolCatalog.all).first { it.family == ToolFamily.SshRead }
		assertEquals(
			setOf("ssh_hosts", "ssh_probe", "ssh_list", "ssh_read_file", "ssh_download"),
			read.entries.mapTo(mutableSetOf()) { it.name },
		)
		val write = toolGroups(ToolCatalog.all).first { it.family == ToolFamily.SshWrite }
		assertEquals(
			setOf("ssh_exec", "ssh_upload", "ssh_write_file"),
			write.entries.mapTo(mutableSetOf()) { it.name },
		)
		// The split is derived from the catalogue, not from a second list: every
		// irreversible tool is in the write family and nothing else is.
		ToolCatalog.all.filterNot { it.reversible }.forEach {
			assertEquals(it.name, ToolFamily.SshWrite, toolFamilyOf(it))
		}
	}

	@Test
	fun `a family with opt-in members reads as mixed under a null selection`() {
		val read = toolGroups(ToolCatalog.all).first { it.family == ToolFamily.Read }
		val names = read.entries.map { it.name }
		val state = groupSelection(null, names)
		// Null permits every non-opt-in tool, so the paid vision/OCR pair is the
		// part that is off: the master control must show a mixed family, not a
		// fully-on one.
		assertTrue(state.mixed)
		assertEquals(names.size, state.total)
		assertEquals(names.count { name -> ToolCatalog.permits(null, name) }, state.on)
	}

	@Test
	fun `a mixed family turns on rather than discarding the rest`() {
		val read = toolGroups(ToolCatalog.all).first { it.family == ToolFamily.Read }.entries
		val names = read.map { it.name }
		val next = groupToggle(null, names, on = true)
		names.forEach { name ->
			assertTrue("$name must be explicitly selected", ToolCatalog.permits(next, name))
		}
		// Everything else that null meant is still there, in an explicit form.
		ToolCatalog.all.filterNot { it.remote || it.optIn }.forEach { entry ->
			assertTrue("${entry.name} must survive the materialisation", entry.name in next)
		}
		assertEquals(next, ToolCatalog.names.filter { it in next })
	}

	@Test
	fun `turning a family off materialises all minus that family`() {
		val web = toolGroups(ToolCatalog.all).first { it.family == ToolFamily.Web }.entries
		val names = web.map { it.name }
		val next = groupToggle(null, names, on = false)
		names.forEach { assertFalse(it in next) }
		assertTrue("read_file is untouched", "read_file" in next)
		// The other family-control gestures agree: a second toggle re-adds only
		// the family's names.
		val back = groupToggle(next, names, on = true)
		names.forEach { assertTrue(it in back) }
	}

	@Test
	fun `the ssh write family is named explicitly and read-only is separate`() {
		val read = toolGroups(ToolCatalog.all).first { it.family == ToolFamily.SshRead }.entries
		val write = toolGroups(ToolCatalog.all).first { it.family == ToolFamily.SshWrite }.entries
		val readOn = groupToggle(null, read.map { it.name }, on = true)
		write.forEach {
			assertFalse("${it.name} must not ride on the read-only switch", it.name in readOn)
		}
		val both = groupToggle(readOn, write.map { it.name }, on = true)
		assertTrue("ssh_exec is now explicitly granted", "ssh_exec" in both)
		assertTrue("ssh_list is still granted", "ssh_list" in both)
		assertFalse("null never granted SSH", ToolCatalog.permits(null, "ssh_exec"))
	}

	@Test
	fun `an explicit selection toggles the family without materialising`() {
		val names = setOf("read_file", "write_file")
		val next = groupToggle(names, setOf("read_file", "list_files"), on = false)
		assertEquals(setOf("write_file"), next)
		assertEquals(setOf("read_file", "write_file"), groupToggle(next, setOf("read_file"), on = true))
	}

	@Test
	fun `a family policy is one value or none when mixed`() {
		val names = listOf("read_file", "list_files")
		assertEquals(ToolPolicy.Free, groupPolicy(emptyMap(), names))
		assertEquals(ToolPolicy.Ask, groupPolicy(mapOf("read_file" to ToolPolicy.Ask, "list_files" to ToolPolicy.Ask), names))
		assertNull(groupPolicy(mapOf("read_file" to ToolPolicy.Ask), names))
	}

	@Test
	fun `applying a family policy writes every member and free removes`() {
		val names = listOf("read_file", "list_files")
		val ask = withGroupPolicy(emptyMap(), names, ToolPolicy.Ask)
		assertEquals(mapOf("read_file" to ToolPolicy.Ask, "list_files" to ToolPolicy.Ask), ask)
		val cleared = withGroupPolicy(ask + ("write_file" to ToolPolicy.Deny), names, ToolPolicy.Free)
		assertEquals(mapOf("write_file" to ToolPolicy.Deny), cleared)
	}

	@Test
	fun `group selection counts only permitted names`() {
		val names = listOf("read_file", "ssh_exec", "ssh_list")
		val explicit = setOf("read_file", "ssh_list")
		val state = groupSelection(explicit, names)
		assertEquals(2, state.on)
		assertEquals(3, state.total)
		assertTrue(state.mixed)
		assertEquals(3, groupSelection(names.toSet(), names).on)
		assertTrue(groupSelection(names.toSet(), names).all)
	}
}
