package com.inhuman.core.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Moving files between the workspace and the outside world.
 *
 * ## Why copying is the only operation offered
 *
 * Both directions copy rather than reference. A `content://` URI handed to this
 * app by another one is not ours: the grant can be revoked, the source can be
 * deleted, and a file that a tool needs to read has to be a real path anyway.
 * Keeping the URI and reading through it later works until it does not, and the
 * failure is a broken conversation rather than a clear error.
 *
 * ## Why this is not part of the tools
 *
 * The tools face the model; this faces the user. They share [SessionWorkspace]
 * so both are confined identically, but a user importing a file is not a tool
 * call and should not need approval -- they are the one being protected.
 */
/**
 * @param freeSpaceBytes space available on the volume holding the workspace, or
 *   a provider that answers null to skip the check.
 *
 *   Injected because the free-space query is a platform/filesystem detail:
 *   production reads `File.usableSpace`, and a unit test passes a fixed number
 *   so the test does not depend on the machine's disk.
 */
class WorkspaceTransfer(
	private val workspace: SessionWorkspace,
	private val freeSpaceBytes: () -> Long? = {
		runCatching { workspace.rootPath().usableSpace }.getOrNull()
	},
) {

	sealed interface Outcome {
		data class Ok(val relativePath: String, val bytes: Long) : Outcome
		data class Failed(val reason: String) : Outcome
	}

	/**
	 * Copy a stream into the workspace.
	 *
	 * @param destinationPath where to put it, relative to the workspace root.
	 *   Resolved through the sandbox like any other path, so an import cannot
	 *   land outside the conversation.
	 * @param maxBytes per-file ceiling, or null for "only the workspace quota
	 *   applies". Tool writes and attachments use the default `maxWriteBytes`;
	 *   a user-initiated download passes the space left in the quota so it is
	 *   not artificially limited to 4 MiB, while still not being able to walk
	 *   past the workspace's own ceiling.
	 */
	fun import(
		destinationPath: String,
		maxBytes: Long? = workspace.limits.maxWriteBytes,
		open: () -> InputStream,
	): Outcome {
		val target = when (val r = workspace.resolve(destinationPath)) {
			is SessionWorkspace.Resolution.Ok -> r.file
			is SessionWorkspace.Resolution.Rejected -> return Outcome.Failed(r.reason)
		}
		if (target.isDirectory) {
			return Outcome.Failed("$destinationPath is a directory")
		}

		// ## Why this writes to a temporary file and then moves it
		//
		// It used to stream straight into `target` and call `target.delete()`
		// on every failure path. Two things were wrong with that, and the second
		// is the one that loses data:
		//
		// 1. A failed import of an *existing* file deleted the original. The
		//    user picks a file to import, the read fails halfway, and the file
		//    that was already there is gone.
		// 2. `resolve` happily returns an existing empty directory, whose
		//    `outputStream()` throws -- and the catch then deleted that
		//    directory. Importing into a freshly made folder removed the folder.
		//
		// A temp file in the same directory makes the move atomic on one volume,
		// so the target is only ever replaced by a complete import, and the
		// cleanup can never reach anything the user already had.
		val staging = File(target.parentFile, ".${target.name}.importing")
		staging.parentFile?.mkdirs()

		return try {
			var total = 0L
			open().use { input ->
				staging.outputStream().use { output ->
					val buffer = ByteArray(64 * 1024)
					while (true) {
						val read = input.read(buffer)
						if (read <= 0) break
						total += read
						// Checked as it goes rather than after: a stream has no
						// length up front, and discovering the limit was exceeded
						// only once the whole thing is on disk means the disk
						// already paid for it.
						if (maxBytes != null && total > maxBytes) {
							return Outcome.Failed(
								"file is larger than the $maxBytes-byte limit",
							)
						}
						output.write(buffer, 0, read)
					}
					output.flush()
				}
			}

			// Every quota, not just the per-file one. This is the side door the
			// tools' own checks could not close: importing twenty files one at a
			// time respected `maxWriteBytes` and nothing else, so the total and
			// the count could be walked straight past.
			//
			// The staging file is inside the workspace, so `usage()` is already
			// counting it, and an existing target is counted too even though it
			// is about to go. Both are declared here; see the note on
			// `quotaRefusal` for why the first version of this got it wrong.
			val replacing = target.isFile
			val existing = if (replacing) target.length() else 0L
			workspace.quotaRefusal(
				stagingBytes = total,
				stagingFiles = 1,
				replacedBytes = existing,
				addsFiles = if (replacing) 0 else 1,
				newBytes = total,
			)?.let { return Outcome.Failed(it) }

			val free = freeSpaceBytes()
			if (free != null && free - total < workspace.limits.reservedFreeBytes) {
				return Outcome.Failed(
					"not enough free space: $free bytes available, and " +
						"${workspace.limits.reservedFreeBytes} is reserved for the app itself.",
				)
			}

			if (target.exists() && !target.delete()) {
				return Outcome.Failed("could not replace $destinationPath")
			}
			if (!staging.renameTo(target)) {
				return Outcome.Failed("could not finish importing $destinationPath")
			}
			Outcome.Ok(workspace.relativize(target), total)
		} catch (e: Exception) {
			// Only ever the staging file. Whatever was at the destination is
			// still there, which is the property the old version lacked.
			staging.delete()
			Outcome.Failed(e.message ?: "import failed")
		} finally {
			// Every exit path lands here -- including the refusals above, which
			// `return` from inside the `try`. A `return` still runs `finally`,
			// which is what makes "the staging file never survives" true rather
			// than merely likely. It is removed on success too, where the rename
			// has already moved it and the delete is a no-op.
			staging.delete()
		}
	}

	/**
	 * Copy a workspace file out, through a stream the caller supplies.
	 *
	 * The caller opens the destination -- a `content://` URI from
	 * `ACTION_CREATE_DOCUMENT`, a share pipe, wherever -- so this class never
	 * has to know about Android's storage APIs, and the same code serves every
	 * export route.
	 */
	fun export(
		relativePath: String,
		open: () -> OutputStream,
	): Outcome {
		val source = when (val r = workspace.resolve(relativePath)) {
			is SessionWorkspace.Resolution.Ok -> r.file
			is SessionWorkspace.Resolution.Rejected -> return Outcome.Failed(r.reason)
		}
		if (!source.exists()) return Outcome.Failed("no such file: $relativePath")
		if (source.isDirectory) return Outcome.Failed("$relativePath is a directory")

		return try {
			var total = 0L
			source.inputStream().use { input ->
				open().use { output ->
					val buffer = ByteArray(64 * 1024)
					while (true) {
						val read = input.read(buffer)
						if (read <= 0) break
						total += read
						output.write(buffer, 0, read)
					}
					output.flush()
				}
			}
			Outcome.Ok(relativePath, total)
		} catch (e: Exception) {
			Outcome.Failed(e.message ?: "export failed")
		}
	}

	/** Suggested filename for an export, with the path flattened. */
	fun suggestedName(relativePath: String): String =
		relativePath.substringAfterLast('/').ifBlank { "file" }

	/**
	 * A default destination for an import, avoiding collisions.
	 *
	 * Appending " (2)" rather than overwriting: a user importing a file twice
	 * usually means two files, and silently replacing the first is how work
	 * disappears.
	 */
	fun uniqueName(desiredName: String): String {
		val safe = desiredName.replace('/', '_').replace('\\', '_').ifBlank { "imported" }
		var candidate = safe
		var n = 2
		while (workspace.resolve(candidate).let { it is SessionWorkspace.Resolution.Ok && it.file.exists() }) {
			val dot = safe.lastIndexOf('.')
			candidate = if (dot > 0) {
				"${safe.substring(0, dot)} ($n)${safe.substring(dot)}"
			} else {
				"$safe ($n)"
			}
			n++
			if (n > 200) return "$n-$safe"
		}
		return candidate
	}
}
