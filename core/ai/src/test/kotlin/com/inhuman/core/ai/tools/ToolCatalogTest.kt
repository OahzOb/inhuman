package com.inhuman.core.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is allowed to use what.
 *
 * The subject is a permission check, so the tests are mostly about the states
 * that are easy to conflate: null against empty, and a selection naming a tool
 * that no longer exists.
 */
class ToolCatalogTest {

	// --- the two empty-looking states -----------------------------------------

	@Test
	fun `null permits everything`() {
		// What an assistant created before the setting existed has. It must keep
		// working, and it must keep working as new tools are added -- "everything"
		// was the answer when it was recorded and is still the answer.
		ToolCatalog.names.forEach { name ->
			assertTrue("null must permit $name", ToolCatalog.permits(null, name))
		}
	}

	@Test
	fun `an empty selection permits nothing`() {
		// The other empty-looking state, and the opposite answer. An assistant
		// deliberately set up to only talk must not be handed a tool by a
		// fallback that treats "nothing selected" as "not configured".
		ToolCatalog.names.forEach { name ->
			assertFalse("empty must refuse $name", ToolCatalog.permits(emptyList(), name))
		}
	}

	@Test
	fun `the two are not the same`() {
		// Stated as its own test because the whole column exists for this, and a
		// refactor that collapsed them would otherwise pass everything above.
		assertTrue(ToolCatalog.permits(null, "web_search"))
		assertFalse(ToolCatalog.permits(emptyList(), "web_search"))
	}

	// --- ordinary selections --------------------------------------------------

	@Test
	fun `a selection permits exactly what it names`() {
		val selected = listOf("read_file", "read_time")

		assertTrue(ToolCatalog.permits(selected, "read_file"))
		assertTrue(ToolCatalog.permits(selected, "read_time"))
		assertFalse(ToolCatalog.permits(selected, "write_file"))
		assertFalse(ToolCatalog.permits(selected, "delete_file"))
	}

	@Test
	fun `a name that no longer exists does not break the check`() {
		// A release that renames a tool leaves a stale name in storage. The
		// assistant should end up slightly less capable, not unusable -- so an
		// unknown name is ignored rather than throwing or matching something.
		val selected = listOf("read_file", "tool_that_was_removed")

		assertTrue(ToolCatalog.permits(selected, "read_file"))
		assertFalse(ToolCatalog.permits(selected, "write_file"))
	}

	@Test
	fun `a stale name in the selection is not itself permitted`() {
		// The check is about capabilities, not about strings. A name that no
		// tool answers to is refused even though the selection lists it --
		// otherwise the rule would be answering "is this string in that list"
		// and calling the answer a permission.
		assertFalse(ToolCatalog.permits(listOf("tool_that_was_removed"), "tool_that_was_removed"))
		assertFalse(ToolCatalog.permits(null, "tool_that_never_existed"))
	}

	@Test
	fun `selection is case sensitive`() {
		// Tool names are identifiers the model calls. Folding case would let
		// "Read_File" through as a match for a name that is not the same string,
		// and the registry lookup that follows is exact.
		assertFalse(ToolCatalog.permits(listOf("read_file"), "READ_FILE"))
	}

	// --- the catalogue itself -------------------------------------------------

	@Test
	fun `every name is unique`() {
		// The selection is a set of names, so a duplicate would make one entry
		// impossible to deselect: both checkboxes would toggle the same string.
		assertEquals(ToolCatalog.names.size, ToolCatalog.names.toSet().size)
	}

	@Test
	fun `the catalogue is not empty and every entry is described`() {
		assertTrue(ToolCatalog.all.isNotEmpty())
		ToolCatalog.all.forEach { entry ->
			assertTrue("${entry.name} needs a blurb", entry.blurb.isNotBlank())
			assertTrue("${entry.name} needs a name", entry.name.isNotBlank())
		}
	}

	@Test
	fun `allSelection is null rather than the full list`() {
		// Deliberate, and the difference is visible to a user: the full list
		// would mean a future tool arrives switched off, whereas null means it
		// arrives switched on. Someone who chose "everything" chose to keep up.
		assertEquals(null, ToolCatalog.allSelection())
	}

	@Test
	fun `reads are listed before writes`() {
		// Order is what makes the list scannable. The check is loose on purpose:
		// it asserts that no mutating tool appears before a read-only one, not
		// that any particular tool is at any particular index.
		val firstWrite = ToolCatalog.all.indexOfFirst { it.mutates }
		val lastRead = ToolCatalog.all.indexOfLast { !it.mutates }
		assertTrue("read-only tools should come first", lastRead < firstWrite)
	}

	@Test
	fun `the tools that change files are marked as such`() {
		// The marker drives a warning in the picker, so what it covers matters.
		val mutating = ToolCatalog.all.filter { it.mutates }.map { it.name }.toSet()
		assertEquals(
			setOf("write_file", "append_file", "create_directory", "move_file", "delete_file"),
			mutating,
		)
	}

	@Test
	fun `the deepseek web tools are marked paid`() {
		// The marker is a cost warning, not a capability one: these tools are
		// read-only but they spend money at DeepSeek on every successful call.
		val paid = ToolCatalog.all.filter { it.paid }.map { it.name }.toSet()
		assertEquals(setOf("web_search", "web_fetch"), paid)
		assertFalse(ToolCatalog.all.first { it.name == "web_fetch" }.mutates)
	}
}
