package com.inhuman.core.files

import android.os.StatFs
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
 *   Injected rather than called directly because `StatFs` is a stub in JVM unit
 *   tests -- it returns zero for every method -- so a hardcoded call makes
 *   every write fail with "not enough free space" in exactly the environment
 *   where the tools need testing. Production passes the real `StatFs`; a test
 *   passes a fixed number, or null to test the things that are not about disk
 *   space.
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
	 */
	protected fun readText(file: File): String {
		if (!file.exists()) throw Refused("no such file: ${workspace.relativize(file)}")
		if (file.isDirectory) throw Refused("${workspace.relativize(file)} is a directory, not a file")
		val limit = workspace.limits.maxReadBytes
		if (file.length() > limit) {
			throw Refused(
				"${workspace.relativize(file)} is ${file.length()} bytes, over the " +
					"${limit}-byte read limit. Read part of it, or use a shell.",
			)
		}
		return file.readText()
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

	/** Total size and file-count ceilings for the whole workspace. */
	private fun checkQuota(delta: Long) {
		val limits = workspace.limits
		val used = workspace.usedBytes()
		if (used + delta > limits.maxTotalBytes) {
			throw Refused(
				"workspace is full: ${used + delta} bytes would exceed the " +
					"${limits.maxTotalBytes}-byte limit. Delete something first.",
			)
		}
		val count = workspace.fileCount()
		if (count + 1 > limits.maxFileCount) {
			throw Refused(
				"workspace has $count files, at the ${limits.maxFileCount}-file limit. " +
					"Delete something first.",
			)
		}
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
