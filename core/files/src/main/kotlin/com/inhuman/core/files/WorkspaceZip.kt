package com.inhuman.core.files

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
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