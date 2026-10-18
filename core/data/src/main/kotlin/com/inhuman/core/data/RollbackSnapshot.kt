package com.inhuman.core.data

import com.inhuman.core.common.diag.Diag
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** One path that differs between the workspace and the pre-turn snapshot. */
data class WorkspaceChange(
	val path: String,
	val kind: Kind,
) {
	enum class Kind { Added, Modified, Deleted }
}

/** How many paths a stored summary carries; a workspace can hold thousands. */
private const val MAX_RECORDED_CHANGES = 100

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

	/**
	 * The turn's file changes, by content rather than timestamps.
	 *
	 * The snapshot copy does not preserve modification times, so hashing is the
	 * only comparison that survives the copy. It runs once at the end of a
	 * mutating turn, not per render. Directories are represented by their files;
	 * an empty directory change alone is not listed.
	 */
	fun diff(workspaceRoot: File, snapshot: File): List<WorkspaceChange> {
		if (!snapshot.isDirectory || !workspaceRoot.isDirectory) return emptyList()
		val before = filesByPath(snapshot)
		val after = filesByPath(workspaceRoot)
		val changes = mutableListOf<WorkspaceChange>()
		for (path in (before.keys + after.keys).sorted()) {
			val old = before[path]
			val new = after[path]
			when {
				old == null && new != null ->
					changes += WorkspaceChange(path, WorkspaceChange.Kind.Added)
				old != null && new == null ->
					changes += WorkspaceChange(path, WorkspaceChange.Kind.Deleted)
				old != null && new != null && hash(old) != hash(new) ->
					changes += WorkspaceChange(path, WorkspaceChange.Kind.Modified)
			}
		}
		return changes.take(MAX_RECORDED_CHANGES)
	}

	private fun filesByPath(root: File): Map<String, File> = root.walkTopDown()
		.onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
		.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
		.associateBy { it.relativeTo(root).invariantSeparatorsPath }

	private fun hash(file: File): String = runCatching {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(16 * 1024)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				digest.update(buffer, 0, read)
			}
		}
		digest.digest().joinToString("") { "%02x".format(it) }
	}.getOrElse { "unreadable:${file.length()}" }


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
