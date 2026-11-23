package com.inhuman.core.files

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Writes a workspace as a plain, unencrypted zip.
 *
 * ## Why this is not a backup
 *
 * The backup is an encrypted database plus workspaces, for moving to another
 * install. This is an export: no database, no key material, no manifest, just
 * the files the user can already see in Files. Calling it a backup would invite
 * someone to delete the only copy of their data and trust it.
 *
 * ## Why the walk is not `File.walkTopDown()`
 *
 * A zip must not contain bytes from outside the workspace. The workspace's
 * tools refuse symlinks, but a user or another app can create one, and a
 * Windows junction is neither a symlink to `Files.isSymbolicLink` nor resolved
 * by `File.canonicalFile` (see `07-gotchas.md` B29). Every entry is therefore
 * checked with `toRealPath()` against the real root before it is added; a link
 * that points outside is skipped rather than followed, and the result says how
 * many entries were skipped.
 *
 * ## What it does not do
 *
 * It does not preserve timestamps, permissions or empty directories' metadata;
 * zip is a transport for the bytes. It does not compress beyond the default
 * deflate level, and it does not impose a second quota -- the workspace already
 * has one, and an export that refuses halfway is worse than a large file.
 */
object WorkspaceZip {

	/**
	 * What was written.
	 *
	 * [skipped] counts entries that could not be read or whose real path left
	 * the workspace. A non-zero value is disclosed to the user; silently
	 * dropping a file is how an export looks complete when it is not.
	 */
	data class Result(val files: Int, val bytes: Long, val skipped: Int)

	fun write(workspace: SessionWorkspace, output: OutputStream): Result {
		val root = workspace.rootPath().toPath()
		val realRoot = root.toRealPath()
		val entries = ArrayList<Entry>()
		// A junction can point back at the root or at a directory already
		// visited; walking it again would either loop or write the same bytes
		// twice under two names. The real path is the identity, so it is what
		// decides whether a directory has been seen.
		val visitedDirectories = HashSet<Path>().apply { add(realRoot) }
		var skipped = 0

		Files.walkFileTree(
			root,
			object : SimpleFileVisitor<Path>() {
				override fun preVisitDirectory(
					dir: Path,
					attrs: BasicFileAttributes,
				): FileVisitResult {
					if (dir == root) return FileVisitResult.CONTINUE
					val real = realInside(dir, realRoot)
					if (Files.isSymbolicLink(dir) || real == null || !visitedDirectories.add(real)) {
						skipped++
						return FileVisitResult.SKIP_SUBTREE
					}
					entries += Entry(
						name = relativeName(root, dir),
						path = dir,
						directory = true,
					)
					return FileVisitResult.CONTINUE
				}

				override fun visitFile(
					file: Path,
					attrs: BasicFileAttributes,
				): FileVisitResult {
					if (!attrs.isRegularFile || Files.isSymbolicLink(file) ||
						realInside(file, realRoot) == null
					) {
						skipped++
						return FileVisitResult.CONTINUE
					}
					entries += Entry(
						name = relativeName(root, file),
						path = file,
						directory = false,
					)
					return FileVisitResult.CONTINUE
				}

				override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
					skipped++
					return FileVisitResult.CONTINUE
				}
			},
		)

		var files = 0
		var bytes = 0L
		// Closing the ZipOutputStream closes `output`; the caller opened it for
		// exactly this one write, and a half-closed stream is not useful to it.
		ZipOutputStream(BufferedOutputStream(output)).use { zip ->
			for (entry in entries.sortedBy { it.name }) {
				if (entry.directory) {
					zip.putNextEntry(ZipEntry(entry.name + "/"))
					zip.closeEntry()
					continue
				}
				// A file with an empty relative name cannot happen for a child
				// of the root, but writing an empty zip entry would be a
				// malformed archive rather than a harmless no-op.
				if (entry.name.isEmpty()) {
					skipped++
					continue
				}
				zip.putNextEntry(ZipEntry(entry.name))
				Files.newInputStream(entry.path).use { input ->
					bytes += input.copyTo(zip, 64 * 1024)
				}
				zip.closeEntry()
				files++
			}
		}
		return Result(files = files, bytes = bytes, skipped = skipped)
	}

	/**
	 * What an import from a zip did.
	 *
	 * [skipped] names every entry that was not written, with a short reason: a
	 * directory entry, an unsafe name, a duplicate, or a file over the
	 * per-file ceiling. [failed] means the import stopped early -- quota, free
	 * space, IO or a corrupt archive -- and whatever was written before that
	 * point is kept and counted. A partial import must not look complete.
	 */
	data class ImportResult(
		val files: Int,
		val bytes: Long,
		val skipped: List<String>,
		val failed: Boolean,
		val failure: String? = null,
	)

	/**
	 * Import the files of a zip into an existing workspace.
	 *
	 * ## What it reuses, and why
	 *
	 * Every entry is written through [WorkspaceTransfer.import], so the
	 * per-file ceiling, the workspace quota, the free-space reserve, the
	 * atomic temporary-file replacement and the "replace an existing target"
	 * rule are the ones the rest of the app uses. A second write path here
	 * would be a second set of those rules, and the day one changed the other
	 * would silently differ.
	 *
	 * ## What it refuses
	 *
	 * Entry names are normalised (`\` to `/`) and checked before anything is
	 * written: empty names, absolute paths, `.`/`..` segments, a Windows drive
	 * prefix and directory entries are skipped with a reason. A name that
	 * survives this check is still resolved through [SessionWorkspace.resolve]
	 * before it is written, so the sandbox is the backstop rather than this
	 * check alone.
	 *
	 * A name that appears twice is written once: the first occurrence claims
	 * it and later ones are reported as skipped rather than silently
	 * overwriting the earlier bytes.
	 *
	 * ## When it stops
	 *
	 * Quota, free-space, IO and corrupt-archive failures stop the import:
	 * continuing cannot succeed, and the result says so. A per-file ceiling
	 * refusal is entry-level -- the rest of the archive can still be imported,
	 * so that entry is skipped instead. At most [MAX_IMPORT_FILES] file
	 * entries are read; beyond that the import stops.
	 *
	 * The [InputStream] is consumed and closed; the caller hands over a stream
	 * it no longer needs (the same contract as [write] and its output).
	 */
	fun importInto(workspace: SessionWorkspace, input: InputStream): ImportResult {
		val transfer = WorkspaceTransfer(workspace)
		val skipped = ArrayList<String>()
		var files = 0
		var bytes = 0L
		var fileEntries = 0
		val seen = HashSet<String>()
		val zip = ZipInputStream(BufferedInputStream(input))
		try {
			while (true) {
				val entry = zip.nextEntry ?: break
				val raw = entry.name.orEmpty()
				// Zip marks directories with a trailing separator. Windows-built
				// archives can use '\' instead, and either way there is no file
				// to write -- parent directories are created by the import.
				if (entry.isDirectory || raw.endsWith('/') || raw.endsWith('\\')) {
					skipped += "${display(raw)}: directory entry"
					continue
				}
				fileEntries++
				if (fileEntries > MAX_IMPORT_FILES) {
					return ImportResult(
						files = files,
						bytes = bytes,
						skipped = skipped,
						failed = true,
						failure = "the zip contains more than $MAX_IMPORT_FILES file entries",
					)
				}

				val checked = checkImportName(raw)
				if (checked is NameCheck.Rejected) {
					skipped += "${display(raw)}: ${checked.reason}"
					continue
				}
				val name = (checked as NameCheck.Accepted).name
				if (!seen.add(name)) {
					skipped += "$raw: duplicate name"
					continue
				}
				when (val resolution = workspace.resolve(name)) {
					is SessionWorkspace.Resolution.Rejected -> {
						skipped += "$raw: ${resolution.reason}"
						continue
					}
					is SessionWorkspace.Resolution.Ok -> Unit
				}
				// -1 means the local header defers the size to a data
				// descriptor, which is the common case for a deflated entry;
				// the streaming check inside WorkspaceTransfer is what catches
				// those.
				if (entry.size > workspace.limits.maxWriteBytes) {
					skipped += "$raw: file is larger than the " +
						"${workspace.limits.maxWriteBytes}-byte limit"
					continue
				}
				val outcome = transfer.import(
					destinationPath = name,
					maxBytes = workspace.limits.maxWriteBytes,
				) { EntryStream(zip) }
				when (outcome) {
					is WorkspaceTransfer.Outcome.Ok -> {
						files++
						bytes += outcome.bytes
					}
					is WorkspaceTransfer.Outcome.Failed -> {
						val reason = outcome.reason
						when {
							isQuotaRefusal(reason) ->
								return ImportResult(files, bytes, skipped, true, reason)
							isPerFileLimit(reason) ->
								skipped += "$raw: $reason"
							else ->
								return ImportResult(files, bytes, skipped, true, reason)
						}
					}
				}
			}
		} catch (e: IOException) {
			// A corrupt or truncated archive is not a normal end of input: the
			// caller has to know the import stopped rather than finishing.
			return ImportResult(
				files = files,
				bytes = bytes,
				skipped = skipped,
				failed = true,
				failure = e.message ?: "the zip could not be read",
			)
		} finally {
			// Closing the ZipInputStream closes the caller's stream; that is
			// stated in the KDoc, and a close failure must not mask a result
			// that was already decided.
			runCatching { zip.close() }
		}
		return ImportResult(files = files, bytes = bytes, skipped = skipped, failed = false)
	}

	/** Most file entries one archive may carry; beyond this the import stops. */
	private const val MAX_IMPORT_FILES = 2_000

	/** What an entry name was normalised to, or why it was refused. */
	private sealed interface NameCheck {
		data class Accepted(val name: String) : NameCheck
		data class Rejected(val reason: String) : NameCheck
	}

	/**
	 * Normalise and validate an entry name before it reaches the workspace.
	 *
	 * This is the cheap check, not the security boundary: `resolve` still
	 * decides where a file lands. It exists so a zip full of bad names produces
	 * a list of skips instead of a run of resolved-path failures.
	 */
	private fun checkImportName(raw: String): NameCheck {
		if (raw.isEmpty()) return NameCheck.Rejected("empty name")
		val name = raw.replace('\\', '/')
		if (name.startsWith('/')) return NameCheck.Rejected("absolute path")
		if (name.indexOf('\u0000') >= 0) return NameCheck.Rejected("name contains a null byte")
		// A drive-qualified name (`C:...`) is not workspace-relative, and on
		// Windows it would resolve outside the root.
		if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) {
			return NameCheck.Rejected("absolute path")
		}
		val parts = name.split('/')
		if (parts.any { it == "." || it == ".." }) {
			return NameCheck.Rejected("'.' or '..' in the path")
		}
		val cleaned = parts.filter { it.isNotEmpty() }.joinToString("/")
		if (cleaned.isEmpty()) return NameCheck.Rejected("empty name")
		return NameCheck.Accepted(cleaned)
	}

	private fun display(name: String): String = if (name.isEmpty()) "(empty name)" else name

	/**
	 * The current entry's bytes, without letting the importer close the archive.
	 *
	 * `WorkspaceTransfer.import` closes the stream it is given (`use`), which is
	 * correct for a one-file import and wrong here: the same [ZipInputStream]
	 * carries every later entry.
	 */
	private class EntryStream(private val zip: ZipInputStream) : InputStream() {
		override fun read(): Int = zip.read()

		override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len)

		override fun close() = Unit
	}

	// The strings come from `WorkspaceTransfer` / `SessionWorkspace.quotaRefusal`
	// in this same module. If the wording ever changes, the fallback is "stop
	// and report", never "pretend it worked".
	private fun isQuotaRefusal(reason: String): Boolean =
		reason.startsWith("workspace is full:") ||
			reason.contains("-file limit") ||
			reason.startsWith("not enough free space:")

	private fun isPerFileLimit(reason: String): Boolean =
		reason.startsWith("file is larger than the")

	private fun relativeName(root: Path, path: Path): String =
		root.relativize(path).toString().replace('\\', '/')

	/**
	 * The real path if it is inside the workspace, or null.
	 *
	 * This is the one check that follows a Windows junction, which neither
	 * `Files.isSymbolicLink` nor `File.canonicalFile` does. Returning the real
	 * path as well lets the caller use it as the directory's identity.
	 */
	private fun realInside(path: Path, realRoot: Path): Path? = try {
		val real = path.toRealPath()
		if (real == realRoot || real.startsWith(realRoot)) real else null
	} catch (_: IOException) {
		null
	}

	private data class Entry(
		val name: String,
		val path: Path,
		val directory: Boolean,
	)
}