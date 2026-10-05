package com.inhuman.core.ai.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition rule, which is the whole point of this package.
 *
 * Three sources of rules exist and they combine by taking the strictest. The
 * tests are written around the property that makes that safe rather than around
 * the individual branches: **no combination can be looser than its strictest
 * input.** A precedence order between the sources would be easier to write and
 * would let one of them relax another, which is the failure mode worth an
 * afternoon of tests.
 */
class PolicySetTest {

	@Test
	fun `strictest takes the stricter, in either order`() {
		assertEquals(ToolPolicy.Ask, ToolPolicy.strictest(ToolPolicy.Free, ToolPolicy.Ask))
		assertEquals(ToolPolicy.Ask, ToolPolicy.strictest(ToolPolicy.Ask, ToolPolicy.Free))
		assertEquals(ToolPolicy.Deny, ToolPolicy.strictest(ToolPolicy.Ask, ToolPolicy.Deny))
		assertEquals(ToolPolicy.Blind, ToolPolicy.strictest(ToolPolicy.Deny, ToolPolicy.Blind))
		assertEquals(ToolPolicy.Blind, ToolPolicy.strictest(ToolPolicy.Blind, ToolPolicy.Free))
	}

	@Test
	fun `an empty configuration refuses nothing`() {
		assertEquals(ToolPolicy.Free, PolicySet.None.effective("delete_file", "notes.md"))
		assertTrue(PolicySet.None.isEmpty)
	}

	@Test
	fun `a global rule applies to every path`() {
		val rules = PolicySet(global = mapOf("delete_file" to ToolPolicy.Ask))

		assertEquals(ToolPolicy.Ask, rules.effective("delete_file", "a.txt"))
		assertEquals(ToolPolicy.Ask, rules.effective("delete_file", "deep/inside/b.txt"))
		// And only to its own tool.
		assertEquals(ToolPolicy.Free, rules.effective("read_file", "a.txt"))
	}

	@Test
	fun `an assistant rule can tighten a global one`() {
		val rules = PolicySet(
			global = mapOf("write_file" to ToolPolicy.Free),
			assistant = mapOf("write_file" to ToolPolicy.Ask),
		)

		assertEquals(ToolPolicy.Ask, rules.effective("write_file", "a.txt"))
	}

	@Test
	fun `an assistant rule cannot loosen a global one`() {
		// The property, stated as a test. With a precedence order this would
		// return Free and quietly hand back a capability the user restricted.
		val rules = PolicySet(
			global = mapOf("delete_file" to ToolPolicy.Deny),
			assistant = mapOf("delete_file" to ToolPolicy.Free),
		)

		assertEquals(ToolPolicy.Deny, rules.effective("delete_file", "a.txt"))
	}

	@Test
	fun `a path rule cannot loosen a global one either`() {
		val rules = PolicySet(
			global = mapOf("write_file" to ToolPolicy.Ask),
			paths = mapOf("scratch" to ToolPolicy.Free),
		)

		assertEquals(ToolPolicy.Ask, rules.effective("write_file", "scratch/notes.md"))
	}

	@Test
	fun `a path rule can tighten a loose tool`() {
		val rules = PolicySet(paths = mapOf("private" to ToolPolicy.Deny))

		assertEquals(ToolPolicy.Deny, rules.effective("write_file", "private/diary.md"))
		assertEquals(ToolPolicy.Free, rules.effective("write_file", "public/notes.md"))
	}

	@Test
	fun `a rule on a directory covers what is inside it, at any depth`() {
		val rules = PolicySet(paths = mapOf("notes" to ToolPolicy.Blind))

		assertEquals(ToolPolicy.Blind, rules.effective("read_file", "notes"))
		assertEquals(ToolPolicy.Blind, rules.effective("read_file", "notes/todo.md"))
		assertEquals(ToolPolicy.Blind, rules.effective("read_file", "notes/2026/march.md"))
	}

	@Test
	fun `a sibling whose name starts the same is not covered`() {
		// The reason ancestors are compared segment-wise. A string-prefix test
		// would put `notes-old` inside `notes`, so a rule meant to hide one
		// directory would hide another -- the same class of mistake as `a/../b`
		// defeating a prefix check on a raw path.
		val rules = PolicySet(paths = mapOf("notes" to ToolPolicy.Blind))

		assertEquals(ToolPolicy.Free, rules.effective("read_file", "notes-old/todo.md"))
		assertEquals(ToolPolicy.Free, rules.effective("read_file", "notebook.md"))
	}

	@Test
	fun `the strictest ancestor wins`() {
		val rules = PolicySet(
			paths = mapOf(
				"a" to ToolPolicy.Ask,
				"a/b" to ToolPolicy.Blind,
			),
		)

		assertEquals(ToolPolicy.Ask, rules.effective("write_file", "a/x.md"))
		assertEquals(ToolPolicy.Blind, rules.effective("write_file", "a/b/x.md"))
		assertEquals(ToolPolicy.Blind, rules.effective("write_file", "a/b/c/x.md"))
	}

	@Test
	fun `a path rule does not apply to a tool that names no path`() {
		val rules = PolicySet(paths = mapOf("private" to ToolPolicy.Blind))

		// `read_time` takes no path, so a directory rule has nothing to say
		// about it. Only a tool-keyed rule can restrict one.
		assertEquals(ToolPolicy.Free, rules.effectiveFor("read_time"))
		// And a tool-keyed rule still does.
		assertEquals(
			ToolPolicy.Deny,
			PolicySet(global = mapOf("web_search" to ToolPolicy.Deny)).effectiveFor("web_search"),
		)
	}

	@Test
	fun `all three sources are considered together`() {
		val rules = PolicySet(
			global = mapOf("write_file" to ToolPolicy.Ask),
			assistant = mapOf("write_file" to ToolPolicy.Free),
			paths = mapOf("vault" to ToolPolicy.Blind),
		)

		assertEquals(ToolPolicy.Ask, rules.effective("write_file", "notes.md"))
		assertEquals(ToolPolicy.Blind, rules.effective("write_file", "vault/k.md"))
	}

	@Test
	fun `a stored value that means nothing reads as Free`() {
		// A row written by a newer build, or a hand-edited backup. Refusing to
		// open a conversation over a settings row would be the worse failure.
		assertEquals(ToolPolicy.Free, ToolPolicy.parse("something-newer"))
		assertEquals(ToolPolicy.Free, ToolPolicy.parse(null))
		assertEquals(ToolPolicy.Free, ToolPolicy.parse("   "))
		assertEquals(ToolPolicy.Deny, ToolPolicy.parse("deny"))
		assertEquals(ToolPolicy.Ask, ToolPolicy.parse(" ASK "))
	}

	@Test
	fun `isEmpty reports whether any work is worth doing`() {
		assertTrue(PolicySet().isEmpty)
		assertFalse(PolicySet(global = mapOf("x" to ToolPolicy.Free)).isEmpty)
		assertFalse(PolicySet(paths = mapOf("d" to ToolPolicy.Free)).isEmpty)
	}
}
