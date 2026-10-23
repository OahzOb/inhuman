package com.inhuman.core.files

import com.inhuman.core.files.SessionWorkspace.Resolution
import java.io.File

/**
 * Shared plumbing for the file tools.
 *
 * Every tool resolves its path through [SessionWorkspace] and then goes through
 * one of the guarded operations here. Nothing in this file constructs a `File`
 * from model input on its own, which is the property that makes the sandbox
 * hold: there is exactly one place a path becomes a file, and it is not here.
 *
 * @param freeSpaceBytes the space available on the volume holding the
 *   workspace, or null to skip the check.
 *
 *   Injected rather than called directly so a unit test can pass a fixed
 *   number, or null to test the things that are not about disk space. Production
 *   reads `File.usableSpace` on the workspace's volume.
 */
abstract class WorkspaceTool(
	protected val workspace: SessionWorkspace,
	private val freeSpaceBytes: (() -> Long)? = null,
) {

	/** Resolve, or return the refusal as a tool result. */
	protected fun resolve(path: String): Result<File> =
		when (val r = workspace.resolve(path)) {
			is Resolution.Ok -> Result.success(r.file)
			is Resolution.Rejected -> Result.failure(Refused(r.reason))
		}

	/** A refusal that the model should read and act on, not a crash. */
	protected class Refused(message: String) : Exception(message)

	/**
	 * Read a text file, subject to the byte ceiling.
	 *
	 * Refuses rather than truncating. A truncated read looks like a complete one
	 * to the model, which will then reason confidently about half a file --
	 * a failure mode that is very hard to notice from the outside.
	 *
	 * ## The refusal has to name something the app can actually do
	 *
	 * It used to end "Read part of it, or use a shell." There is no shell --
	 * the app deliberately has none -- and `read_file` had no way to read part
	 * of a file either, so the sentence told the model to do two impossible
	 * things. A model that follows it either calls a tool that does not exist
	 * or retries the same read, and either way the turn is spent on nothing.
	 * `read_file` now takes a line range, so the advice is actionable.
	 */
	protected fun readText(file: File, offset: Int? = null, limit: Int? = null): String {
		if (!file.exists()) throw Refused("no such file: ${workspace.relativize(file)}")
		if (file.isDirectory) throw Refused("${workspace.relativize(file)} is a directory, not a file")

		val total = file.length()
		val ceiling = workspace.limits.maxReadBytes

		// A ranged read never loads the whole file, so the ceiling that applies
		// to it is the size of the slice, not the size of the file. Without
		// this, a 20 MB log would be unreadable in any part.
		if (offset == null && limit == null) {
			if (total > ceiling) {
				throw Refused(
					"${workspace.relativize(file)} is $total bytes, over the ${ceiling}-byte " +
						"read limit. Read it in pieces with the offset and limit arguments " +
						"(line numbers), or use file_info for the size alone.",
				)
			}
			return file.readText()
		}

		val text = file.readText()
		val lines = text.lines()
		val from = ((offset ?: 1) - 1).coerceAtLeast(0)
		if (from >= lines.size && lines.isNotEmpty()) {
			throw Refused(
				"${workspace.relativize(file)} has ${lines.size} lines; offset $offset is past the end",
			)
		}
		val take = (limit ?: lines.size).coerceAtLeast(1)
		val slice = lines.drop(from).take(take)

		// The slice is still bounded: a caller asking for a million lines gets
		// the ceiling, not the memory.
		val rendered = slice.joinToString("\n")
		if (rendered.length > ceiling) {
			throw Refused(
				"those $take lines are ${rendered.length} bytes, over the ${ceiling}-byte " +
					"read limit. Ask for fewer lines.",
			)
		}
		return rendered
	}

	/**
	 * Write a text file, creating parents, subject to every quota.
	 *
	 * The free-space precheck is not paranoia: without it a large write can fill
	 * the volume, and the app's own database then fails to commit -- turning one
	 * oversized file into losing a conversation.
	 */
	protected fun writeText(file: File, text: String): Int {
		val bytes = text.toByteArray(Charsets.UTF_8)
		val limit = workspace.limits.maxWriteBytes
		if (bytes.size > limit) {
			throw Refused("refusing to write ${bytes.size} bytes; the limit is $limit")
		}

		val existing = if (file.exists() && file.isFile) file.length() else 0L
		val delta = bytes.size - existing
		if (delta > 0) {
			checkQuota(delta)
			checkFreeSpace(delta)
		}

		file.parentFile?.mkdirs()
		file.writeBytes(bytes)
		return bytes.size
	}

	/**
	 * Total size and file-count ceilings for the whole workspace.
	 *
	 * The arithmetic lives on [SessionWorkspace.quotaRefusal] now, because
	 * import needed the same check and did not have it -- a second copy of a
	 * rule is how one of them comes to be missing.
	 *
	 * A write stages nothing: the text is already in memory, so there is no
	 * file of its own to exclude, and `writeText` has already subtracted the
	 * length of a file it is about to overwrite.
	 */
	private fun checkQuota(delta: Long) {
		workspace.quotaRefusal(
			stagingBytes = 0,
			stagingFiles = 0,
			replacedBytes = 0,
			addsFiles = 1,
			newBytes = delta,
		)?.let { throw Refused(it) }
	}

	private fun checkFreeSpace(delta: Long) {
		// No provider means the caller has opted out -- a unit test on a JVM
		// where StatFs is a stub, or a platform that cannot answer.
		val free = freeSpaceBytes?.invoke() ?: return
		if (free - delta < workspace.limits.reservedFreeBytes) {
			throw Refused(
				"not enough free space: $free bytes available, and " +
					"${workspace.limits.reservedFreeBytes} is reserved for the app itself.",
			)
		}
	}

	/** List entries, capped, so a huge directory cannot hang the UI. */
	protected fun listEntries(dir: File): Pair<List<File>, Boolean> {
		if (!dir.exists()) throw Refused("no such directory: ${workspace.relativize(dir)}")
		if (!dir.isDirectory) throw Refused("${workspace.relativize(dir)} is not a directory")
		val all = dir.listFiles().orEmpty().sortedWith(
			compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
		)
		val limit = workspace.limits.maxListEntries
		return if (all.size > limit) all.take(limit) to true else all to false
	}

	/**
	 * Render one result, converting every ordinary failure into tool output.
	 *
	 * `IllegalArgumentException` is in the list because that is what a missing
	 * required argument throws. A model that omits a field has to be told which
	 * one; an exception escaping here would end the turn and tell it nothing.
	 */
	protected inline fun guard(block: () -> String): com.inhuman.core.ai.ToolResult =
		try {
			com.inhuman.core.ai.ToolResult.ok(block())
		} catch (e: Refused) {
			com.inhuman.core.ai.ToolResult.error(e.message ?: "refused")
		} catch (e: IllegalArgumentException) {
			com.inhuman.core.ai.ToolResult.error(e.message ?: "invalid arguments")
		} catch (e: java.io.IOException) {
			com.inhuman.core.ai.ToolResult.error("filesystem error: ${e.message}")
		}

	protected fun humanSize(bytes: Long): String = when {
		bytes < 1024 -> "$bytes B"
		bytes < 1024 * 1024 -> "${bytes / 1024} KB"
		else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
	}
}
