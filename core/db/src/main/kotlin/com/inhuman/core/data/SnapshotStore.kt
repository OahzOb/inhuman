package com.inhuman.core.data

import com.inhuman.core.common.diag.Diag
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

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

/** One retained turn, as the chat menu lists it. */
data class SnapshotTurn(
	/** The user message id the turn is anchored to. */
	val token: String,
	val createdAt: Long,
	val checkpoints: Int,
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
 * Symlinks are refused rather than followed. Restore builds the checkpoint
 * beside the workspace and swaps it in, so a missing blob or a copy that fails
 * halfway leaves the workspace alone rather than half-wiped.
 */
object SnapshotStore {

	const val START = "start"

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	@Serializable
	private data class Entry(
		val path: String,
		val hash: String,
		/**
		 * Size and modification time are a hash-cache key, not part of a file's
		 * identity. They are optional so manifests written before the cache
		 * existed still decode, and a missing value simply means "hash it".
		 */
		val size: Long = 0,
		val modified: Long = 0,
	)

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
			// The previous capture in this turn is the hash cache: a file whose
			// size and full-precision mtime still match it has not changed, and
			// its blob is already stored. Capturing a group used to re-read and
			// SHA-256 every file in the workspace each time.
			val previous = previousEntries(destination)
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
					val path = relative(source, file)
					val reused = reuseHash(file, previous[path])
					val hash = if (reused != null && File(blobs, reused).isFile) {
						reused
					} else {
						hash(file)
					}
					val blob = File(blobs, hash)
					if (!blob.isFile) copyBlob(file, blob)
					files += Entry(path, hash, file.length(), modifiedNanos(file))
				}

			val manifest = Manifest(
				dirs = dirs.sorted(),
				files = files.sortedBy { it.path },
			)
			val manifestJson = json.encodeToString(manifest)
			writeAtomically(checkpointFile(destination, checkpointId), manifestJson)
			// A copy of the newest manifest under a non-`.json` name, so the
			// next capture can tell which file versions are already stored.
			// Best effort: losing it costs a rehash, never a checkpoint.
			runCatching { writeAtomically(lastManifestFile(destination), manifestJson) }
			true
		}.getOrElse { failure ->
			Diag.w("rollback", "checkpoint capture failed", failure)
			false
		}
	}

	/**
	 * Put the workspace back to [checkpointId]; no rows should be deleted first.
	 *
	 * The restored tree is **built beside the workspace and swapped in**, not
	 * copied into a workspace that has already been emptied.
	 *
	 * It used to do the latter. Every blob was checked first, but a copy that
	 * failed halfway -- a disk that filled up, a blob removed between the check
	 * and the read -- left the workspace holding the first half of the
	 * checkpoint and none of the old files, while the call returned `false` as
	 * if nothing had been touched. Rollback is what a user reaches for *after*
	 * something has already gone wrong, so it is the last operation that may be
	 * able to make it worse.
	 *
	 * A failed swap puts the old workspace back before returning.
	 */
	fun restore(workspaceRoot: File, turnDir: File, checkpointId: String): Boolean {
		val destination = workspaceRoot.canonicalFile
		val manifest = readManifest(turnDir, checkpointId) ?: return false
		val blobs = File(turnDir, "blobs")
		val parent = destination.parentFile ?: return false
		// Beside the workspace, on the same file system, so the swap is a
		// rename rather than a copy of the whole workspace twice.
		val staging = File(parent, ".restore-${UUID.randomUUID()}")
		val restored = runCatching {
			// Validate every path and every blob before writing anything.
			val entries = manifest.files.map { entry ->
				safeChild(destination, entry.path) ?: error("unsafe path in checkpoint")
				val blob = File(blobs, entry.hash)
				if (!blob.isFile) error("missing blob ${entry.hash}")
				entry.path to blob
			}
			val dirs = manifest.dirs.map { rel ->
				safeChild(destination, rel) ?: error("unsafe directory in checkpoint")
				rel
			}
			staging.deleteRecursively()
			if (!staging.mkdirs()) error("could not create the restore staging directory")
			dirs.sortedBy { it.length }.forEach { File(staging, it).mkdirs() }
			entries.forEach { (path, blob) ->
				val target = File(staging, path)
				target.parentFile?.mkdirs()
				blob.copyTo(target, overwrite = true)
			}
			swapIntoPlace(destination, staging)
			true
		}.getOrElse { failure ->
			Diag.w("rollback", "checkpoint restore failed", failure)
			false
		}
		// A successful swap renamed the staging directory away; this only
		// removes what a failed build left behind.
		runCatching { staging.deleteRecursively() }
		return restored
	}

	/**
	 * Replace [destination] with [staging], putting the old tree back if the
	 * second rename fails.
	 *
	 * Two renames under the same parent, so the window in which the workspace
	 * does not exist is as small as the platform allows -- and if the second
	 * one fails, the workspace the user already had comes back rather than
	 * disappearing because a restore was attempted.
	 */
	private fun swapIntoPlace(destination: File, staging: File) {
		if (!destination.exists()) {
			if (!staging.renameTo(destination)) {
				error("could not put the restored workspace in place")
			}
			return
		}
		val parent = destination.parentFile ?: error("workspace has no parent directory")
		val aside = File(parent, ".restore-old-${UUID.randomUUID()}")
		if (!destination.renameTo(aside)) error("could not move the workspace aside")
		if (!staging.renameTo(destination)) {
			aside.renameTo(destination)
			error("could not put the restored workspace in place")
		}
		runCatching { aside.deleteRecursively() }
	}

	fun diff(workspaceRoot: File, turnDir: File, checkpointId: String): List<WorkspaceChange> {
		val before = readManifest(turnDir, checkpointId) ?: return emptyList()
		if (!workspaceRoot.isDirectory) return emptyList()
		val old = before.files.associateBy { it.path }
		// The same size+mtime cache as `capture`: a file that still matches the
		// start manifest has the same content, so the "changed files" summary no
		// longer re-reads the whole workspace at the end of every turn.
		val now = workspaceRoot.walkTopDown()
			.onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
			.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
			.associate { file ->
				val path = relative(workspaceRoot, file)
				path to (reuseHash(file, old[path]) ?: hash(file))
			}
		val changes = mutableListOf<WorkspaceChange>()
		for (path in (old.keys + now.keys).sorted()) {
			val was = old[path]?.hash
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
		val turns = turns(conversationDir)
		return SnapshotStats(
			turns = turns.size,
			bytes = turns.sumOf { it.bytes },
		)
	}

	/**
	 * The retained turns, newest first.
	 *
	 * The chat menu shows these rather than only a count: "3 turns" does not
	 * tell a user which turns can still be rewound, and the newest turn is the
	 * one a rollback would touch. Only directories with a `manifests/` folder
	 * count -- a half-written turn is not restorable.
	 */
	fun turns(conversationDir: File): List<SnapshotTurn> =
		conversationDir.listFiles()
			.orEmpty()
			.filter { it.isDirectory && File(it, "manifests").isDirectory }
			.map { dir ->
				SnapshotTurn(
					token = dir.name,
					createdAt = metaOf(dir)?.createdAt ?: dir.lastModified(),
					checkpoints = File(dir, "manifests").listFiles()
						.orEmpty()
						.count { it.isFile && it.name.endsWith(".json") },
					bytes = sizeOf(dir),
				)
			}
			.sortedWith(compareByDescending<SnapshotTurn> { it.createdAt }.thenByDescending { it.token })

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
			if (manifest.name == lastManifestFile(turnDir).name) continue
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
		hashedFiles++
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

	/**
	 * Cached byte totals for turn directories.
	 *
	 * `turns()` runs after every turn to fill the rewind menu, and its byte
	 * total used to walk the whole turn directory each time -- a turn holds one
	 * blob per changed file, so that is the largest part of the cache. A blob is
	 * content-addressed and never rewritten, so a total can be reused until the
	 * turn directory's contents change; the timestamps and entry counts of
	 * `blobs/` and `manifests/` are that signal. A stale number here is a wrong
	 * menu label, never a wrong restore.
	 */
	private data class DirStamp(
		val blobsModified: Long,
		val blobsCount: Int,
		val manifestsModified: Long,
		val manifestsCount: Int,
		val bytes: Long = 0,
	)

	private val sizeCache = java.util.concurrent.ConcurrentHashMap<String, DirStamp>()

	/**
	 * How many files were actually hashed.
	 *
	 * An observation point for the tests: the cache's failure mode is invisible
	 * in a manifest when content is unchanged, so the evidence that an
	 * unchanged capture does no reading is the absence of hash calls.
	 */
	internal var hashedFiles: Int = 0
		private set

	internal fun resetHashedFilesForTest() {
		hashedFiles = 0
	}

	/**
	 * The manifest the previous capture wrote, or an empty map when there is
	 * none.
	 *
	 * Kept as its own file rather than "the newest `.json`" because two captures
	 * can share a millisecond, and picking the older one would reuse a hash for
	 * a file that changed between them. A missing or corrupt cache is not an
	 * error: the capture hashes everything, which is what it used to do always.
	 */
	private fun previousEntries(turnDir: File): Map<String, Entry> {
		val manifest = runCatching {
			json.decodeFromString<Manifest>(lastManifestFile(turnDir).readText())
		}.getOrNull() ?: return emptyMap()
		return manifest.files.associateBy { it.path }
	}

	private fun lastManifestFile(turnDir: File): File = File(File(turnDir, "manifests"), ".last")

	/**
	 * The cached hash for [file], or null when it cannot be trusted.
	 *
	 * The key is size plus full-precision modification time. A zero mtime (a
	 * filesystem that cannot report one) disables the cache rather than
	 * matching every unknown file; manifests written before this cache existed
	 * carry zeros and therefore hash normally. A same-size rewrite within the
	 * filesystem's timestamp resolution is the one case this cannot see, which
	 * is why the timestamp is taken at nanosecond precision rather than from
	 * `File.lastModified()`'s milliseconds.
	 */
	private fun reuseHash(file: File, cached: Entry?): String? {
		if (cached == null) return null
		val modified = modifiedNanos(file)
		if (modified == 0L) return null
		if (cached.size != file.length() || cached.modified != modified) return null
		return cached.hash
	}

	private fun modifiedNanos(file: File): Long =
		runCatching { Files.getLastModifiedTime(file.toPath()).to(TimeUnit.NANOSECONDS) }
			.getOrDefault(0L)

	private fun sizeOf(dir: File): Long {
		val blobs = File(dir, "blobs")
		val manifests = File(dir, "manifests")
		if (!blobs.isDirectory && !manifests.isDirectory) {
			// Not a checkpoint directory: nothing to key a cache entry on.
			return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
		}
		val blobsModified = blobs.lastModified()
		val blobsCount = blobs.list()?.size ?: 0
		val manifestsModified = manifests.lastModified()
		val manifestsCount = manifests.list()?.size ?: 0
		sizeCache[dir.path]?.let { cached ->
			if (
				cached.blobsModified == blobsModified &&
				cached.blobsCount == blobsCount &&
				cached.manifestsModified == manifestsModified &&
				cached.manifestsCount == manifestsCount
			) {
				return cached.bytes
			}
		}
		val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
		sizeCache[dir.path] = DirStamp(
			blobsModified = blobsModified,
			blobsCount = blobsCount,
			manifestsModified = manifestsModified,
			manifestsCount = manifestsCount,
			bytes = bytes,
		)
		return bytes
	}
}
