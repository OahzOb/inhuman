package com.inhuman.core.files.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Helpers for reading a tool's arguments.
 *
 * Every accessor takes the argument name and throws a [WorkspaceTool.Refused]
 * with a message naming the missing field. A model that omits `path` should be
 * told so explicitly; a bare null-pointer somewhere deeper in the call stack
 * tells it nothing and wastes a turn.
 */
internal fun JsonObject.requireString(name: String): String =
	this[name]?.jsonPrimitive?.contentOrNull
		?: throw IllegalArgumentException("missing required string argument '$name'")

internal fun JsonObject.optionalString(name: String): String? =
	this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.optionalInt(name: String): Int? =
	this[name]?.jsonPrimitive?.intOrNull

/** Common schema fragment for the `path` argument every tool takes. */
internal fun pathProperty(description: String): Map<String, Any?> =
	mapOf("type" to "string", "description" to description)

/**
 * The tools that give the model hands.
 *
 * Every one of them takes a **workspace-relative path** and none of them is
 * ever told where the workspace is. That is the whole design: a model cannot
 * name a location outside the conversation because it has no vocabulary for
 * one, and a path that looks absolute is reinterpreted as relative rather than
 * honoured. Confinement is a property of the interface, not of a prompt telling
 * the model to behave.
 *
 * The document tools are appended here rather than registered separately: they
 * take a workspace-relative path, obey the same quotas, and must disappear with
 * the file tools when the startup fence check fails.
 */
object FileTools {

	/**
	 * The default set, with a real free-space check.
	 *
	 * @param freeSpaceBytes overrides the space query. Production passes
	 *   nothing and reads `File.usableSpace`; a unit test passes a fixed number
	 *   or null, so it does not depend on the test machine's disk.
	 */
	fun all(
		workspace: SessionWorkspace,
		freeSpaceBytes: (() -> Long)? = {
			runCatching { workspace.rootPath().usableSpace }.getOrNull() ?: 0L
		},
		/**
		 * Whether a workspace-relative path is hidden from the agent.
		 *
		 * Only `list_files` consults it, and that is the point: the other tools
		 * are refused by `ToolPolicy.Deny` before they run, but a path the user
		 * marked `Blind` must not appear in a listing either. **A filename can
		 * leak on its own** -- a note called `divorce-lawyer.md` has already told
		 * the model something -- so "cannot open it" and "cannot see it" are
		 * different promises and this is the second one.
		 *
		 * Defaults to nothing hidden, so a caller that has no rules to apply
		 * (every existing test, and any future one) behaves exactly as before.
		 */
		hidden: (String) -> Boolean = { false },
	): List<Tool> = listOf(
		ListFilesTool(workspace, freeSpaceBytes, hidden),
		ReadFileTool(workspace, freeSpaceBytes),
		WriteFileTool(workspace, freeSpaceBytes),
		AppendFileTool(workspace, freeSpaceBytes),
		CreateDirectoryTool(workspace, freeSpaceBytes),
		MoveFileTool(workspace, freeSpaceBytes),
		DeleteFileTool(workspace, freeSpaceBytes),
		FileInfoTool(workspace, freeSpaceBytes),
	) + DocumentTools.all(workspace)
}

private typealias FreeSpace = (() -> Long)?

internal class ListFilesTool(
	workspace: SessionWorkspace,
	free: FreeSpace = null,
	/** Whether a workspace-relative path should be left out of the listing. */
	private val hidden: (String) -> Boolean = { false },
) :
	WorkspaceTool(workspace, free), Tool {
	override val name = "list_files"

	/**
	 * Reads only; replaying it changes nothing.
	 */
	override val mutates = false
	override val description =
		"List the files and directories in the conversation workspace. " +
			"Paths are relative to the workspace root; use \".\" or \"\" for the root."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf("path" to pathProperty("Directory to list, relative to the workspace root.")),
		"required" to listOf("path"),
	)

	override suspend fun execute(arguments: JsonObject) = guard {
		val dir = resolve(arguments.requireString("path")).getOrThrow()
		val (listed, truncated) = listEntries(dir)
		// Filtered after `listEntries` rather than inside it, so the cap on how
		// many entries are read is the same whether or not rules exist -- a rule
		// must not change how far down a huge directory is walked.
		val entries = listed.filterNot { hidden(workspace.relativize(it)) }
		if (entries.isEmpty()) {
			"${workspace.relativize(dir).ifEmpty { "." }} is empty"
		} else {
			buildString {
				appendLine("${workspace.relativize(dir).ifEmpty { "." }} (${entries.size} entries):")
				entries.forEach { f ->
					val kind = if (f.isDirectory) "dir " else "file"
					val size = if (f.isDirectory) "" else "  ${humanSize(f.length())}"
					appendLine("  $kind  ${f.name}$size")
				}
				if (truncated) {
					appendLine("  … truncated at ${workspace.limits.maxListEntries} entries")
				}
			}.trimEnd()
		}
	}
}

internal class ReadFileTool(workspace: SessionWorkspace, free: FreeSpace = null) :
	WorkspaceTool(workspace, free), Tool {
	override val name = "read_file"

	/**
	 * Reads only; replaying it changes nothing.
	 */
	override val mutates = false
	override val description =
		"Read a text file from the conversation workspace, with line numbers. " +
			"A file larger than the read limit is refused rather than truncated; " +
			"for those, read it in pieces by passing offset and limit, which are " +
			"line numbers and can be used together or separately."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"path" to pathProperty("File to read, relative to the workspace root."),
			"offset" to mapOf(
				"type" to "integer",
				"description" to "First line to read, 1-based. Defaults to the start of the file.",
			),
			"limit" to mapOf(
				"type" to "integer",
				"description" to "How many lines to read from offset. Defaults to the rest of the file.",
			),
		),
		"required" to listOf("path"),
	)

	override suspend fun execute(arguments: JsonObject) = guard {
		val file = resolve(arguments.requireString("path")).getOrThrow()
		val offset = arguments.optionalInt("offset")
		val limit = arguments.optionalInt("limit")
		val text = readText(file, offset, limit)

		// Line numbers, because a model asked to edit a file works far more
		// reliably when it can refer to a line than when it has to count.
		//
		// They are the file's own numbers, not the slice's: a ranged read that
		// restarted at 1 would invite the model to edit the wrong line, and the
		// mistake would look like a successful edit.
		val first = (offset ?: 1).coerceAtLeast(1)
		val lines = text.lines()
		val numbered = lines.mapIndexed { i, line -> "${first + i}\t$line" }.joinToString("\n")

		val header = if (offset == null && limit == null) {
			"${workspace.relativize(file)} (${lines.size} lines):"
		} else {
			// Said plainly, because a partial view that looks complete is how a
			// model concludes a file ends where the slice did.
			"${workspace.relativize(file)} (lines $first–${first + lines.size - 1}, of a larger file):"
		}
		"$header\n$numbered"
	}
}

internal class WriteFileTool(workspace: SessionWorkspace, free: FreeSpace = null) :
	WorkspaceTool(workspace, free), Tool {
	override val name = "write_file"

	/**
	 * Changes the workspace, so a turn that used it cannot be replayed.
	 */
	override val mutates = true
	override val description =
		"Write a text file in the conversation workspace, creating it if needed and " +
			"replacing its contents if it exists. Parent directories are created."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"path" to pathProperty("File to write, relative to the workspace root."),
			"content" to mapOf("type" to "string", "description" to "Full contents to write."),
		),
		"required" to listOf("path", "content"),
	)

	override suspend fun execute(arguments: JsonObject) = guard {
		val file = resolve(arguments.requireString("path")).getOrThrow()
		val content = arguments.requireString("content")
		val existed = file.exists()
		val written = writeText(file, content)
		val verb = if (existed) "Replaced" else "Created"
		"$verb ${workspace.relativize(file)} (${humanSize(written.toLong())}, " +
			"${content.lines().size} lines)"
	}
}

internal class AppendFileTool(workspace: SessionWorkspace, free: FreeSpace = null) :
	WorkspaceTool(workspace, free), Tool {
	override val name = "append_file"

	/**
	 * Changes the workspace, so a turn that used it cannot be replayed.
	 */
	override val mutates = true
	override val description =
		"Append text to a file in the conversation workspace, creating it if needed."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"path" to pathProperty("File to append to, relative to the workspace root."),
			"content" to mapOf("type" to "string", "description" to "Text to append."),
		),
		"required" to listOf("path", "content"),
	)

	override suspend fun execute(arguments: JsonObject) = guard {
		val file = resolve(arguments.requireString("path")).getOrThrow()
		val content = arguments.requireString("content")
		val existing = if (file.exists() && file.isFile) file.readText() else ""
		val written = writeText(file, existing + content)
		"Appended to ${workspace.relativize(file)} (now ${humanSize(written.toLong())})"
	}
}

internal class CreateDirectoryTool(workspace: SessionWorkspace, free: FreeSpace = null) :
	WorkspaceTool(workspace, free), Tool {
	override val name = "create_directory"

	/**
	 * Changes the workspace, so a turn that used it cannot be replayed.
	 */
	override val mutates = true
	override val description = "Create a directory in the conversation workspace."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf("path" to pathProperty("Directory to create, relative to the workspace root.")),
		"required" to listOf("path"),
	)

	override suspend fun execute(arguments: JsonObject) = guard {
		val dir = resolve(arguments.requireString("path")).getOrThrow()
		if (dir.exists() && dir.isDirectory) {
			"${workspace.relativize(dir)} already exists"
		} else {
			if (!dir.mkdirs()) throw Refused("could not create ${workspace.relativize(dir)}")
			"Created ${workspace.relativize(dir)}"
		}
	}
}

internal class MoveFileTool(workspace: SessionWorkspace, free: FreeSpace = null) :
	WorkspaceTool(workspace, free), Tool {
	override val name = "move_file"

	/**
	 * Changes the workspace, so a turn that used it cannot be replayed.
	 */
	override val mutates = true
	override val description = "Move or rename a file or directory inside the conversation workspace."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"from" to pathProperty("Source path, relative to the workspace root."),
			"to" to pathProperty("Destination path, relative to the workspace root."),
		),
		"required" to listOf("from", "to"),
	)

	override suspend fun execute(arguments: JsonObject) = guard {
		val from = resolve(arguments.requireString("from")).getOrThrow()
		val to = resolve(arguments.requireString("to")).getOrThrow()
		if (!from.exists()) throw Refused("no such file: ${workspace.relativize(from)}")

		// A directory cannot be moved inside itself; the result is a tree that
		// no longer exists and a very confusing error later.
		if (from.isDirectory &&
			to.canonicalPath.startsWith(from.canonicalPath + java.io.File.separator)
		) {
			throw Refused("cannot move ${workspace.relativize(from)} inside itself")
		}
		if (to.exists()) throw Refused("${workspace.relativize(to)} already exists")

		to.parentFile?.mkdirs()
		if (!from.renameTo(to)) throw Refused("could not move ${workspace.relativize(from)}")
		"Moved ${workspace.relativize(from)} to ${workspace.relativize(to)}"
	}
}

internal class DeleteFileTool(workspace: SessionWorkspace, free: FreeSpace = null) :
	WorkspaceTool(workspace, free), Tool {
	override val name = "delete_file"

	/**
	 * Changes the workspace, so a turn that used it cannot be replayed.
	 */
	override val mutates = true
	override val description =
		"Delete a file or directory from the conversation workspace. " +
			"A directory is deleted with everything inside it. This cannot be undone."

	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf("path" to pathProperty("Path to delete, relative to the workspace root.")),
		"required" to listOf("path"),
	)

	/**
	 * Whether this call asks first. Not by default; configurably.
	 *
	 * This *is* the one irreversible operation in the set -- overwriting is
	 * recoverable from the transcript and a lost file is not -- and that is
	 * exactly why the reasoning below has to be stated rather than assumed.
	 *
	 * ## Why this is no longer `true`
	 *
	 * Deletion inside a conversation's own directory is reversible, and that is
	 * the whole reason it is allowed at all: the sandbox confines the agent to
	 * one directory, and what the user brings in is a **copy** -- the original
	 * never moves and is never in the directory. So an approval prompt here
	 * asks a question whose answer is always yes, which is not a safety measure
	 * but friction, and friction is what trains people to click through the
	 * prompt that eventually matters.
	 *
	 * A user who wants to be asked can configure it -- globally, per assistant,
	 * or per path -- and where two rules apply the stricter wins. See
	 * `docs/09-approvals.md`. The gate itself is unchanged and still per call,
	 * because when it *is* configured the arguments are what make a call risky:
	 * deleting an empty scratch file and deleting a directory of the user's
	 * uploads are not the same act.
	 */
	override fun needsApproval(arguments: JsonObject): Boolean = false

	override suspend fun execute(arguments: JsonObject) = guard {
		val target = resolve(arguments.requireString("path")).getOrThrow()

		// Refusing the root: a model that decides to "clean up" by deleting
		// "." would remove every file in the conversation, and there is no
		// reading of that instruction where the user wanted it.
		if (target.canonicalPath == workspace.rootPath().canonicalPath) {
			throw Refused("refusing to delete the workspace root")
		}
		if (!target.exists()) throw Refused("no such file: ${workspace.relativize(target)}")

		val wasDirectory = target.isDirectory
		val count = if (wasDirectory) target.walkTopDown().count() else 1
		if (!target.deleteRecursively()) throw Refused("could not delete ${workspace.relativize(target)}")

		if (wasDirectory) "Deleted ${workspace.relativize(target)} (directory, $count entries)"
		else "Deleted ${workspace.relativize(target)}"
	}
}

internal class FileInfoTool(workspace: SessionWorkspace, free: FreeSpace = null) :
	WorkspaceTool(workspace, free), Tool {
	override val name = "file_info"

	/**
	 * Reads only; replaying it changes nothing.
	 */
	override val mutates = false
	override val description =
		"Report size, type and modification time for a path in the conversation workspace, " +
			"or for the whole workspace if no path is given."
	override val parameters = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"path" to pathProperty("Path to inspect; omit for workspace totals."),
		),
		"required" to emptyList<String>(),
	)

	override suspend fun execute(arguments: JsonObject) = guard {
		val path = arguments.optionalString("path")
		if (path.isNullOrBlank() || path == "." || path == "/") {
			val usage = workspace.usage()
			val used = usage.bytes
			val files = usage.files
			buildString {
				appendLine("Workspace totals:")
				appendLine("  files: $files / ${workspace.limits.maxFileCount}")
				appendLine("  bytes: ${humanSize(used)} / ${humanSize(workspace.limits.maxTotalBytes)}")
				appendLine("  read limit: ${humanSize(workspace.limits.maxReadBytes)} per file")
				appendLine("  write limit: ${humanSize(workspace.limits.maxWriteBytes)} per file")
			}.trimEnd()
		} else {
			val f = resolve(path).getOrThrow()
			if (!f.exists()) throw Refused("no such file: ${workspace.relativize(f)}")
			buildString {
				appendLine("${workspace.relativize(f)}")
				appendLine("  type: ${if (f.isDirectory) "directory" else "file"}")
				if (!f.isDirectory) appendLine("  size: ${humanSize(f.length())} (${f.length()} bytes)")
				appendLine("  modified: ${java.util.Date(f.lastModified())}")
				if (f.isDirectory) {
					appendLine("  entries: ${f.listFiles()?.size ?: 0}")
				}
			}.trimEnd()
		}
	}
}
