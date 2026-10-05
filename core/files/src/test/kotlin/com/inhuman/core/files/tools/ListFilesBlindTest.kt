package com.inhuman.core.files.tools

import com.inhuman.core.files.SessionWorkspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A path the user marked `Blind` must not appear in a listing.
 *
 * ## Why this is a separate file from `FileToolsTest`
 *
 * `Blind` is the one policy with a promise the other three do not make. `Ask`
 * makes the agent wait, `Deny` makes it fail -- both leave the path **nameable**,
 * and both are therefore visible in `list_files`. `Blind` promises the path is
 * not there at all, on the grounds that a filename can leak on its own: a note
 * called `divorce-lawyer.md` has told the model something already.
 *
 * That promise was documented and not implemented. `ListFilesTool` never
 * consulted the policy, so `Blind` behaved exactly like `Deny` while the
 * settings screen offered it as "Hidden from the agent". A test here is what
 * keeps the two from drifting apart again, because nothing else would notice:
 * the file is still refused when named, so every other check passes.
 */
class ListFilesBlindTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private fun workspace(): SessionWorkspace =
		SessionWorkspace.open(File(tmp.root, "ws").apply { mkdirs() })

	private fun seed(ws: SessionWorkspace) {
		File(tmp.root, "ws/public").mkdirs()
		File(tmp.root, "ws/public/readme.md").writeText("visible")
		File(tmp.root, "ws/private").mkdirs()
		File(tmp.root, "ws/private/diary.md").writeText("hidden")
		File(tmp.root, "ws/private-old").mkdirs()
		File(tmp.root, "ws/private-old/notes.md").writeText("not hidden: a sibling")
	}

	private fun args(path: String) = JsonObject(mapOf("path" to JsonPrimitive(path)))

	private fun listTool(ws: SessionWorkspace, hidden: (String) -> Boolean) =
		FileTools.all(ws, freeSpaceBytes = null, hidden = hidden).first { it.name == "list_files" }

	@Test
	fun `a hidden file is absent from the listing`() {
		val ws = workspace()
		seed(ws)
		// Exact and prefix-of-itself, not `startsWith("private")`: the sibling
		// is called `private-old`, so a sloppy predicate would hide it and the
		// test would then be asserting the wrong thing about the wrong file.
		val tool = listTool(ws) { it == "private" || it.startsWith("private/") }

		// `readme.md` is inside `public`, so the root listing shows the
		// directory and the file has to be asked for. The first version of this
		// expected `readme.md` in the root listing and failed on that.
		val root = runBlocking { tool.execute(args(".")).content }
		assertTrue("the visible directory should be listed", root.contains("public"))
		val public = runBlocking { tool.execute(args("public")).content }
		assertTrue("the visible file should be listed", public.contains("readme.md"))

		// Line-wise, because `dir  private` may be the last line and so has no
		// trailing newline -- an earlier version asserted on "private\n" and
		// failed for that reason rather than for the filter's.
		assertTrue(
			"the hidden directory leaked its name",
			root.lines().none { it.trim().endsWith("dir  private") },
		)
		assertFalse("the hidden file leaked its name", public.contains("diary.md"))
	}

	@Test
	fun `a hidden path is absent even when its parent is listed`() {
		// The case that matters most: the model does not have to guess the path,
		// it just lists the parent.
		val ws = workspace()
		seed(ws)
		val tool = listTool(ws) { it == "private" || it.startsWith("private/") }

		// Listing the hidden directory itself must not enumerate what is in it.
		val inside = runBlocking { tool.execute(args("private")).content }
		assertFalse("listing the hidden directory revealed a file", inside.contains("diary.md"))

		val root = runBlocking { tool.execute(args(".")).content }
		assertTrue(
			"a prefix match would have hidden the sibling too",
			root.contains("private-old"),
		)
		assertTrue(
			"the exact directory should be gone from the root listing",
			root.lines().none { it.trim().endsWith("dir  private") },
		)
	}

	@Test
	fun `nothing hidden lists everything, as before`() {
		// The default has to be inert, or every existing caller changes meaning.
		val ws = workspace()
		seed(ws)
		val tool = listTool(ws) { false }

		val output = runBlocking { tool.execute(args(".")).content }

		for (name in listOf("public", "private", "private-old")) {
			assertTrue("$name should be listed when nothing is hidden", output.contains(name))
		}
	}

	@Test
	fun `hiding everything reads as an empty directory, not an error`() {
		// A model told "refused" learns there is something to ask about. A model
		// told "empty" learns nothing, which is the point of Blind over Deny.
		val ws = workspace()
		seed(ws)
		val tool = listTool(ws) { true }

		val output = runBlocking { tool.execute(args(".")).content }

		assertTrue("expected the empty-directory wording, got: $output", output.contains("is empty"))
		assertFalse(output.contains("private"))
	}

	@Test
	fun `the description does not promise what the filter withholds`() {
		// The tool's own description is prompt text. If it said "lists
		// everything", a model would be right to keep asking.
		val ws = workspace()
		val tool = listTool(ws) { false }

		assertFalse(
			"the description claims completeness",
			tool.description.contains("everything", ignoreCase = true),
		)
	}
}
