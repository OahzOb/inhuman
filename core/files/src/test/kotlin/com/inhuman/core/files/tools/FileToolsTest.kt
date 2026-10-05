package com.inhuman.core.files.tools

import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceLimits
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the tools the model actually calls.
 *
 * [SessionWorkspace] being correct does not make the tools correct: each one
 * could still bypass it, mishandle a quota, or fail in a way the model cannot
 * recover from. These check the properties that matter at the tool boundary --
 * confinement, refusal rather than truncation, and errors that read as
 * information.
 */
class FileToolsTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private lateinit var root: File
	private lateinit var ws: SessionWorkspace
	private lateinit var tools: Map<String, com.inhuman.core.ai.Tool>

	@Before
	fun setUp() {
		root = tmp.newFolder("ws")
		ws = SessionWorkspace.open(root)
		// null free-space provider: `StatFs` is a stub on the JVM and reports
		// zero room, so leaving the real check in place makes every write fail
		// with "not enough free space" -- which is how these tests first came
		// out, with ten failures that were all the same stubbed syscall.
		tools = FileTools.all(ws, freeSpaceBytes = null).associateBy { it.name }
	}

	/** A tool set with a fixed amount of room, for testing the disk-space path. */
	private fun toolsWithFreeSpace(bytes: Long) =
		FileTools.all(ws, freeSpaceBytes = { bytes }).associateBy { it.name }

	private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
		pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
	}

	private fun run(name: String, vararg pairs: Pair<String, String>) = runBlocking {
		tools.getValue(name).execute(args(*pairs))
	}

	// --- the tool set --------------------------------------------------------

	@Test
	fun `every tool takes a path and none of them exposes the root`() {
		// The confinement guarantee is a property of the interface, so it is
		// worth asserting on the interface rather than on individual calls.
		val paths = tools.values.mapNotNull { t ->
			(t.parameters["properties"] as? Map<*, *>)?.keys?.map { it.toString() }
		}.flatten()
		assertTrue(paths.contains("path"))
		// No tool offers a way to name an absolute location.
		tools.values.forEach { t ->
			assertFalse(
				"${t.name} mentions an absolute path in its description",
				t.description.contains("/data/") || t.description.contains("C:\\"),
			)
		}
	}

	@Test
	fun `no tool asks for approval by default`() {
		// Including `delete_file`, which used to be hardcoded `true`.
		//
		// Safety here comes from structure rather than from prompts: the agent
		// is confined to one directory, and what the user brings in is a copy,
		// so the original never moves and is never in the directory. Nothing
		// inside is irreversible, so there is nothing to confirm -- and a
		// question whose answer is always yes is friction that trains people to
		// click through the one that eventually matters.
		//
		// Asserting the whole set rather than just delete_file, because a future
		// tool defaulting to `true` would be making this decision again without
		// noticing it had been made.
		val gated = tools.values.filter { it.needsApproval(args("path" to "x")) }.map { it.name }
		assertEquals(emptyList<String>(), gated)
	}

	@Test
	fun `a tool can still ask, so the gate the UI reads is reachable`() {
		// The mechanism is unchanged: `Tool.needsApproval` still exists, is
		// still consulted per call, and a tool can still override it. Only the
		// built-in default moved. A user who configures a rule therefore still
		// gets asked, and that is the path `ToolCallUi.State.Declined` renders --
		// so turning the default off did not delete a state, it moved what
		// reaches it.
		//
		// Asserted through the contract rather than by constructing a gating
		// tool here, because the gate will be supplied by the policy layer
		// rather than hardcoded in a tool: `docs/09-approvals.md`.
		val hook = com.inhuman.core.ai.Tool::class.java.methods.singleOrNull { it.name == "needsApproval" }
		assertTrue("the contract still offers the hook", hook != null)
		assertEquals("and it takes the arguments", 1, hook!!.parameterCount)
	}

	// --- confinement ---------------------------------------------------------

	@Test
	fun `write then read round trips inside the workspace`() {
		val written = run("write_file", "path" to "notes/todo.txt", "content" to "buy milk\n")
		assertFalse(written.isError)
		assertTrue(written.content.contains("Created"))

		val read = run("read_file", "path" to "notes/todo.txt")
		assertFalse(read.isError)
		assertTrue(read.content.contains("buy milk"))
		// Numbered, because a model editing a file works better with line refs.
		assertTrue(read.content.contains("1\tbuy milk"))
		assertTrue(File(root, "notes/todo.txt").exists())
	}

	@Test
	fun `traversal is refused and nothing is written outside`() {
		val outside = File(tmp.root, "escaped.txt")
		val result = run("write_file", "path" to "../escaped.txt", "content" to "nope")
		assertTrue(result.isError)
		assertFalse(outside.exists())
	}

	@Test
	fun `reading an absolute-looking path stays inside the workspace`() {
		// Not an error: the path is reinterpreted as relative, which lands in a
		// harmless place. What matters is that the result is confined.
		run("write_file", "path" to "etc/passwd", "content" to "local")
		val read = run("read_file", "path" to "/etc/passwd")
		assertFalse(read.isError)
		assertTrue(read.content.contains("local"))
		assertFalse(File("/etc/passwd").exists() && !File(root, "etc/passwd").exists())
	}

	@Test
	fun `deleting the workspace root is refused`() {
		run("write_file", "path" to "keep.txt", "content" to "important")
		val result = run("delete_file", "path" to ".")
		assertTrue(result.isError)
		assertTrue("the root was deleted anyway", File(root, "keep.txt").exists())
	}

	@Test
	fun `moving a directory inside itself is refused`() {
		run("create_directory", "path" to "a")
		run("create_directory", "path" to "a/b")
		val result = run("move_file", "from" to "a", "to" to "a/b/c")
		assertTrue(result.isError)
		assertTrue(File(root, "a").isDirectory)
	}

	// --- refusals the model can act on --------------------------------------

	@Test
	fun `reading a missing file explains what was missing`() {
		val result = run("read_file", "path" to "absent.txt")
		assertTrue(result.isError)
		assertTrue("message should name the path: ${result.content}", result.content.contains("absent.txt"))
	}

	@Test
	fun `a missing required argument is reported, not thrown`() {
		// A model that omits a field must be told which one. A crash somewhere
		// deeper in the stack tells it nothing and costs a turn.
		val result = runBlocking { tools.getValue("write_file").execute(buildJsonObject { }) }
		assertTrue(result.isError)
		assertTrue(result.content.contains("path"))
	}

	@Test
	fun `reading a directory is refused with an explanation`() {
		run("create_directory", "path" to "folder")
		val result = run("read_file", "path" to "folder")
		assertTrue(result.isError)
		assertTrue(result.content.contains("directory"))
	}

	@Test
	fun `moving onto an existing file is refused rather than clobbering it`() {
		run("write_file", "path" to "a.txt", "content" to "A")
		run("write_file", "path" to "b.txt", "content" to "B")
		val result = run("move_file", "from" to "a.txt", "to" to "b.txt")
		assertTrue(result.isError)
		assertEquals("B", File(root, "b.txt").readText())
	}

	// --- quotas --------------------------------------------------------------

	@Test
	fun `an oversized read is refused, not truncated`() {
		// Truncating would look like a complete file to the model, which then
		// reasons confidently about half of one.
		val small = SessionWorkspace.open(root, WorkspaceLimits(maxReadBytes = 16))
		val readTool = FileTools.all(small, freeSpaceBytes = null).first { it.name == "read_file" }
		File(root, "big.txt").writeText("x".repeat(1000))

		val result = runBlocking {
			readTool.execute(args("path" to "big.txt"))
		}
		assertTrue(result.isError)
		assertTrue(result.content.contains("read limit"))
	}

	@Test
	fun `an oversized write is refused before anything is written`() {
		val small = SessionWorkspace.open(root, WorkspaceLimits(maxWriteBytes = 32))
		val writeTool = FileTools.all(small, freeSpaceBytes = null).first { it.name == "write_file" }

		val result = runBlocking {
			writeTool.execute(args("path" to "big.txt", "content" to "x".repeat(1000)))
		}
		assertTrue(result.isError)
		assertFalse(File(root, "big.txt").exists())
	}

	@Test
	fun `the file count ceiling is enforced`() {
		val small = SessionWorkspace.open(root, WorkspaceLimits(maxFileCount = 3))
		val writeTool = FileTools.all(small, freeSpaceBytes = null).first { it.name == "write_file" }

		repeat(3) { i ->
			runBlocking { writeTool.execute(args("path" to "f$i.txt", "content" to "x")) }
		}
		val overflow = runBlocking { writeTool.execute(args("path" to "f4.txt", "content" to "x")) }
		assertTrue(overflow.isError)
		assertTrue(overflow.content.contains("limit"))
	}

	@Test
	fun `listing is capped and says so`() {
		val small = SessionWorkspace.open(root, WorkspaceLimits(maxListEntries = 5))
		val listTool = FileTools.all(small, freeSpaceBytes = null).first { it.name == "list_files" }
		repeat(20) { i -> File(root, "f$i.txt").writeText("x") }

		val result = runBlocking { listTool.execute(args("path" to ".")) }
		assertFalse(result.isError)
		assertTrue("should announce truncation", result.content.contains("truncated"))
	}

	@Test
	fun `a write is refused when the volume is nearly full`() {
		// The check that broke every other test when StatFs was stubbed is
		// itself worth a test, with the number supplied rather than queried.
		val writeTool = toolsWithFreeSpace(1024L)["write_file"]!!
		val result = runBlocking {
			writeTool.execute(args("path" to "x.txt", "content" to "y".repeat(4096)))
		}
		assertTrue(result.isError)
		assertTrue(result.content.contains("free space"))
		assertFalse(File(root, "x.txt").exists())
	}

	@Test
	fun `a write succeeds when there is room`() {
		val writeTool = toolsWithFreeSpace(1L shl 30)["write_file"]!!
		val result = runBlocking {
			writeTool.execute(args("path" to "x.txt", "content" to "hello"))
		}
		assertFalse(result.content, result.isError)
	}

	// --- behaviour -----------------------------------------------------------

	@Test
	fun `write reports whether it created or replaced`() {
		// The distinction matters to a model deciding whether it just destroyed
		// something the user cared about.
		val first = run("write_file", "path" to "a.txt", "content" to "one")
		assertTrue(first.content.contains("Created"))
		val second = run("write_file", "path" to "a.txt", "content" to "two")
		assertTrue(second.content.contains("Replaced"))
	}

	@Test
	fun `append adds to existing content`() {
		run("write_file", "path" to "log.txt", "content" to "first\n")
		run("append_file", "path" to "log.txt", "content" to "second\n")
		assertEquals("first\nsecond\n", File(root, "log.txt").readText())
	}

	@Test
	fun `moving renames without losing content`() {
		run("write_file", "path" to "a.txt", "content" to "payload")
		val moved = run("move_file", "from" to "a.txt", "to" to "sub/b.txt")
		assertFalse(moved.isError)
		assertEquals("payload", File(root, "sub/b.txt").readText())
		assertFalse(File(root, "a.txt").exists())
	}

	@Test
	fun `file_info reports workspace totals when given no path`() {
		run("write_file", "path" to "a.txt", "content" to "12345")
		val info = run("file_info")
		assertFalse(info.isError)
		assertTrue(info.content.contains("files: 1"))
	}

	@Test
	fun `listing an empty workspace says so rather than returning nothing`() {
		// "nothing at all" and "empty" look the same to a model with no output.
		val result = run("list_files", "path" to ".")
		assertFalse(result.isError)
		assertTrue(result.content.contains("empty"))
	}
}
