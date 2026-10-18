package com.inhuman.core.data

import com.inhuman.core.common.diag.Diag
import java.io.File
import java.nio.file.Files

/**
 * The workspace before a mutating turn, kept so a rollback can put the files
 * back rather than only deleting the conversation rows.
 *
 * Scope is deliberate: it copies the whole conversation workspace, not a
 * per-tool inverse. A tool can move a directory, append to a file or delete a
 * subtree; recording an inverse for each would be several implementations of
 * the same fact. The workspace is already capped at 256 MiB, and only the last
 * turn's snapshot is retained.
 *
 * Symlinks are refused rather than followed: the sandbox never creates one, and
 * a snapshot that followed a link could copy bytes from outside the workspace.
 */
internal object RollbackSnapshot {

	fun capture(workspaceRoot: File, target: File): Boolean {
		val source = workspaceRoot.canonicalFile
		val destination = target.canonicalFile
		if (destination.path.startsWith(source.path + File.separator) ||
			source.path.startsWith(destination.path + File.separator)
		) {
			Diag.e("rollback", "snapshot path overlaps workspace")
			return false
		}
		runCatching { destination.deleteRecursively() }
		if (!destination.mkdirs()) {
			Diag.w("rollback", "could not create snapshot dir")
			return false
		}
		return runCatching {
			source.listFiles().orEmpty().forEach { child ->
				copyTree(child, File(destination, child.name))
			}
			true
		}.getOrElse { failure ->
			Diag.w("rollback", "snapshot capture failed", failure)
			runCatching { destination.deleteRecursively() }
			false
		}
	}

	fun restore(workspaceRoot: File, snapshot: File): Boolean {
		val source = snapshot.canonicalFile
		val destination = workspaceRoot.canonicalFile
		if (!source.isDirectory) {
			Diag.w("rollback", "snapshot missing")
			return false
		}
		if (destination.path.startsWith(source.path + File.separator) ||
			source.path.startsWith(destination.path + File.separator)
		) {
			Diag.e("rollback", "snapshot path overlaps workspace")
			return false
		}
		return runCatching {
			destination.listFiles().orEmpty().forEach { child ->
				child.deleteRecursively()
			}
			source.listFiles().orEmpty().forEach { child ->
				copyTree(child, File(destination, child.name))
			}
			true
		}.getOrElse { failure ->
			Diag.w("rollback", "snapshot restore failed", failure)
			false
		}
	}

	private fun copyTree(source: File, target: File) {
		if (Files.isSymbolicLink(source.toPath())) {
			error("symlink in workspace snapshot")
		}
		if (source.isDirectory) {
			if (!target.mkdirs() && !target.isDirectory) error("could not create ${target.name}")
			source.listFiles().orEmpty().forEach { child ->
				copyTree(child, File(target, child.name))
			}
		} else {
			source.copyTo(target, overwrite = true)
		}
	}
}
