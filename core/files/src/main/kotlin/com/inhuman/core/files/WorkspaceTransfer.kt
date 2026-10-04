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
class WorkspaceTransfer(private val workspace: SessionWorkspace) {

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
	 */
	fun import(
		destinationPath: String,
		open: () -> InputStream,
	): Outcome {
		val target = when (val r = workspace.resolve(destinationPath)) {
			is SessionWorkspace.Resolution.Ok -> r.file
			is SessionWorkspace.Resolution.Rejected -> return Outcome.Failed(r.reason)
		}

		return try {
			target.parentFile?.mkdirs()
			var total = 0L
			open().use { input ->
				target.outputStream().use { output ->
					val buffer = ByteArray(64 * 1024)
					while (true) {
						val read = input.read(buffer)
						if (read <= 0) break
						total += read
						// Checked as it goes rather than after: a stream has no
						// length up front, and discovering the limit was exceeded
						// only once the whole thing is on disk means the disk
						// already paid for it.
						if (total > workspace.limits.maxWriteBytes) {
							output.close()
							target.delete()
							return Outcome.Failed(
								"file is larger than the ${workspace.limits.maxWriteBytes}-byte limit",
							)
						}
						output.write(buffer, 0, read)
					}
					output.flush()
				}
			}
			Outcome.Ok(workspace.relativize(target), total)
		} catch (e: Exception) {
			// A partial file is worse than none: it looks like a successful
			// import of a truncated document.
			target.delete()
			Outcome.Failed(e.message ?: "import failed")
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
