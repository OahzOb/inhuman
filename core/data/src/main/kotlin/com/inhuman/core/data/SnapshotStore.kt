package com.inhuman.core.data

import com.inhuman.core.common.diag.Diag
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** One path that differs between the workspace and a checkpoint. */
data class WorkspaceChange(
	val path: String,
	val kind: Kind,
) {
	enum class Kind { Added, Modified, Deleted }
}

/** What is stored for one conversation, for the snapshots setting. */
data class SnapshotStats(
	val turns: Int,
	val bytes: Long,
)

/** How many changed paths a stored summary carries; a workspace can hold thousands. */
private const val MAX_RECORDED_CHANGES = 100

/**
 * Per-turn, per-checkpoint workspace history.
 *
 * ## Why content-addressed rather than a full copy per checkpoint
 *
 * A turn can contain several tool calls, and the user can ask to regenerate
 * from after any completed group. A full workspace copy after each group would
 * multiply a 256 MiB workspace by the number of calls. Instead every distinct
 * file version is stored once under `blobs/<sha256>`, and a checkpoint is a
 * manifest mapping paths to hashes. Capturing ten checkpoints that each touch
 * one file costs ten manifests and the changed bytes, not ten workspaces.
 *
 * ## Layout
 *
 * ```
 * filesDir/rollback/<conversationId>/<turnToken>/
 *   meta.json
 *   blobs/<sha256>
 *   manifests/<checkpointId>.json
 * ```
 *
 * `checkpointId` is `start` for the state before the first mutating call, or a
 * tool-result message id for the state after a completed tool group. A turn is
 * identified by the user message id, which is also its directory name.
 *
 * Symlinks are refused rather than followed. Restore validates every blob
 * before deleting the current workspace, so a missing blob leaves the
 * workspace alone rather than half-wiped.
 */
object SnapshotStore {

	const val START = "start"

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	@Serializable
	private data class Entry(val path: String, val hash: String)

	@Serializable
	private data class Manifest(
		val version: Int = 1,
		val dirs: List<String> = emptyList(),
		val files: List<Entry> = emptyList(),
	)

	@Serializable
	private data class Meta(
		val version: Int = 1,
		val token: String,
		val createdAt: Long,
	)

	fun turnDir(conversationDir: File, token: String): File {
		require(token.isNotBlank() && '/' !in token && '\\' !in token) {
			"invalid turn token"
		}
		return File(conversationDir, token)
	}

	fun checkpointFile(turnDir: File, checkpointId: String): File {
		require(checkpointId.isNotBlank() && '/' !in checkpointId && '\\' !in checkpointId) {
			"invalid checkpoint id"
		}
		return File(File(turnDir, "manifests"), "$checkpointId.json")
	}

	fun hasCheckpoint(turnDir: File, checkpointId: String): Boolean =
		checkpointFile(turnDir, checkpointId).isFile

	/**
	 * Store the current workspace under [checkpointId].
	 *
	 * The manifest is written last and atomically; a failed copy leaves no
	 * checkpoint that claims to be complete.
	 */
	fun capture(workspaceRoot: File, turnDir: File, checkpointId: String): Boolean {
		val source = workspaceRoot.canonicalFile
		val destination = turnDir.canonicalFile
		if (!source.isDirectory) return false
		if (destination.path.startsWith(source.path + File.separator) ||
			source.path.startsWith(destination.path + File.separator)
		) {
			Diag.e("rollback", "checkpoint store overlaps workspace")
			return false
		}
		return runCatching {
			val blobs = File(destination, "blobs").apply { mkdirs() }
			val manifests = File(destination, "manifests").apply { mkdirs() }
			if (!blobs.isDirectory || !manifests.isDirectory) return false
			writeMetaIfMissing(destination)

			val dirs = mutableListOf<String>()
			val files = mutableListOf<Entry>()
			source.walkTopDown()
				.onEnter { dir ->
					if (Files.isSymbolicLink(dir.toPath())) {
						error("symlink in workspace")
					}
					if (dir != source) dirs += relative(source, dir)
					true
				}
				.filter { it.isFile }
				.forEach { file ->
					if (Files.isSymbolicLink(file.toPath())) error("symlink in workspace")
					val hash = hash(file)
					val blob = File(blobs, hash)
					if (!blob.isFile) copyBlob(file, blob)
					files += Entry(relative(source, file), hash)
				}

			val manifest = Manifest(
				dirs = dirs.sorted(),
				files = files.sortedBy { it.path },
			)
			writeAtomically(
				checkpointFile(destination, checkpointId),
				json.encodeToString(manifest),
			)
			true
		}.getOrElse { failure ->
			Diag.w("rollback", "checkpoint capture failed", failure)
			false
		}
	}

	/** Put the workspace back to [checkpointId]; no rows should be deleted first. */
	fun restore(workspaceRoot: File, turnDir: File, checkpointId: String): Boolean {
		val destination = workspaceRoot.canonicalFile
		val manifest = readManifest(turnDir, checkpointId) ?: return false
		val blobs = File(turnDir, "blobs")
		return runCatching {
			// Validate the complete set before touching the workspace.
			val entries = manifest.files.map { entry ->
				val target = safeChild(destination, entry.path)
					?: error("unsafe path in checkpoint")
				val blob = File(blobs, entry.hash)
				if (!blob.isFile) error("missing blob ${entry.hash}")
				target to blob
			}
			val dirs = manifest.dirs.map { rel ->
				safeChild(destination, rel) ?: error("unsafe directory in checkpoint")
			}
			destination.listFiles().orEmpty().forEach { it.deleteRecursively() }
			dirs.sortedBy { it.path.length }.forEach { it.mkdirs() }
			entries.forEach { (target, blob) ->
				target.parentFile?.mkdirs()
				blob.copyTo(target, overwrite = true)
			}
			true
		}.getOrElse { failure ->
			Diag.w("rollback", "checkpoint restore failed", failure)
			false
		}
	}

	fun diff(workspaceRoot: File, turnDir: File, checkpointId: String): List<WorkspaceChange> {
		val before = readManifest(turnDir, checkpointId) ?: return emptyList()
		if (!workspaceRoot.isDirectory) return emptyList()
		val old = before.files.associate { it.path to it.hash }
		val now = workspaceRoot.walkTopDown()
			.onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
			.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
			.associate { relative(workspaceRoot, it) to hash(it) }
		val changes = mutableListOf<WorkspaceChange>()
		for (path in (old.keys + now.keys).sorted()) {
			val was = old[path]
			val isNow = now[path]
			when {
				was == null && isNow != null ->
					changes += WorkspaceChange(path, WorkspaceChange.Kind.Added)
				was != null && isNow == null ->
					changes += WorkspaceChange(path, WorkspaceChange.Kind.Deleted)
				was != null && isNow != null && was != isNow ->
					changes += WorkspaceChange(path, WorkspaceChange.Kind.Modified)
			}
		}
		return changes.take(MAX_RECORDED_CHANGES)
	}

	fun stats(conversationDir: File): SnapshotStats {
		val dirs = conversationDir.listFiles()
			.orEmpty()
			.filter { it.isDirectory && File(it, "manifests").isDirectory }
		return SnapshotStats(
			turns = dirs.size,
			bytes = dirs.sumOf { sizeOf(it) },
		)
	}

	/**
	 * Keep only the newest [keepTurns] turn directories. Current turn is
	 * included; the caller never passes 0.
	 */
	fun prune(conversationDir: File, keepTurns: Int) {
		val keep = keepTurns.coerceAtLeast(1)
		// Directories written by the previous full-copy implementation have no
		// manifests and cannot be restored by this store. They are undo cache,
		// not user content; remove them instead of counting untouchable data.
		conversationDir.listFiles().orEmpty()
			.filter { it.isDirectory && !File(it, "manifests").isDirectory }
			.forEach { runCatching { it.deleteRecursively() } }
		val turns = conversationDir.listFiles()
			.orEmpty()
			.filter { it.isDirectory && File(it, "manifests").isDirectory }
			.sortedByDescending { metaOf(it)?.createdAt ?: it.lastModified() }
		for (old in turns.drop(keep)) {
			runCatching { old.deleteRecursively() }
		}
		turns.take(keep).forEach { compact(it) }
	}

	fun deleteTurn(conversationDir: File, token: String) {
		runCatching { turnDir(conversationDir, token).deleteRecursively() }
	}

	/**
	 * Drop checkpoints that no current message references. Called after a
	 * mid-turn regeneration so the rewritten turn replaces its old checkpoints
	 * rather than accumulating them.
	 */
	fun retainCheckpoints(turnDir: File, keep: Set<String>) {
		val manifests = File(turnDir, "manifests").listFiles().orEmpty()
		for (manifest in manifests) {
			val id = manifest.name.removeSuffix(".json")
			if (id !in keep) runCatching { manifest.delete() }
		}
		compact(turnDir)
	}

	fun clear(conversationDir: File) {
		runCatching { conversationDir.deleteRecursively() }
	}

	private fun writeMetaIfMissing(turnDir: File) {
		val file = File(turnDir, "meta.json")
		if (file.isFile) return
		val meta = Meta(token = turnDir.name, createdAt = System.currentTimeMillis())
		writeAtomically(file, json.encodeToString(meta))
	}

	private fun metaOf(turnDir: File): Meta? = runCatching {
		json.decodeFromString<Meta>(File(turnDir, "meta.json").readText())
	}.getOrNull()

	private fun readManifest(turnDir: File, checkpointId: String): Manifest? =
		runCatching {
			json.decodeFromString<Manifest>(
				checkpointFile(turnDir, checkpointId).readText(),
			)
		}.getOrNull()

	/** Remove blobs no surviving manifest refers to. */
	private fun compact(turnDir: File) {
		val referenced = File(turnDir, "manifests").listFiles().orEmpty()
			.mapNotNull { runCatching { json.decodeFromString<Manifest>(it.readText()) }.getOrNull() }
			.flatMap { manifest -> manifest.files.map { it.hash } }
			.toSet()
		File(turnDir, "blobs").listFiles().orEmpty().forEach { blob ->
			if (blob.isFile && blob.name !in referenced) {
				runCatching { blob.delete() }
			}
		}
	}

	private fun safeChild(root: File, relative: String): File? {
		if (relative.isBlank() || relative.startsWith('/') || relative.contains('\\')) return null
		if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
		val child = File(root, relative).canonicalFile
		return if (child.path.startsWith(root.path + File.separator)) child else null
	}

	private fun relative(root: File, file: File): String =
		file.relativeTo(root).invariantSeparatorsPath

	private fun copyBlob(source: File, target: File) {
		val temp = File(target.parentFile, ".tmp-${UUID.randomUUID()}")
		source.copyTo(temp, overwrite = true)
		if (!temp.renameTo(target)) {
			if (target.isFile) temp.delete() else error("could not store blob")
		}
	}

	private fun writeAtomically(target: File, text: String) {
		target.parentFile?.mkdirs()
		val temp = File(target.parentFile, ".tmp-${target.name}")
		temp.writeText(text)
		if (!temp.renameTo(target)) {
			temp.delete()
			error("could not write ${target.name}")
		}
	}

	private fun hash(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(16 * 1024)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
	}

	private fun sizeOf(file: File): Long =
		if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf { sizeOf(it) }
}
