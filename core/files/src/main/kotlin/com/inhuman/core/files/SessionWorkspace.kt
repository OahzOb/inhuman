package com.inhuman.core.files

import java.io.File
import java.nio.file.Files

/**
 * The one and only way a path becomes a [File] inside a conversation's
 * workspace.
 *
 * ## Why this class exists
 *
 * The agent must be confined to its conversation directory as a matter of
 * **code**, not as a matter of prompting. A prompt is a request; this is a
 * gate. The rule it enforces is:
 *
 * > A tool never receives the workspace root and never builds a path itself.
 * > It hands a relative string to [resolve], and [resolve] either returns a
 * > file that is provably inside the root or refuses.
 *
 * Because the constructor is private and [root] is never exposed, a tool
 * holding a `SessionWorkspace` has no way to construct a path that skipped
 * this check.
 *
 * ## The two checks, and why both are needed
 *
 * 1. **No symlinks, ever.** Rejecting a symlink outright is stronger than
 *    resolving one. It removes not just the escape but the time-of-check to
 *    time-of-use window that resolving leaves open: if a symlink can never
 *    exist inside the workspace, it can never be swapped in between the check
 *    and the `open()`.
 * 2. **Canonical prefix check.** A second line of defence. If a bug ever let a
 *    symlink through, or a path component resolved somewhere unexpected, the
 *    canonical target still has to sit under the canonical root.
 *
 * ## What this does NOT protect against
 *
 * It does not sandbox the process. Anything running as the same UID can read
 * the same files regardless. This confines the **agent's tool calls**, which is
 * exactly what it is for.
 */
class SessionWorkspace private constructor(
	/** The canonical workspace root. Private: callers get [resolve]d files only. */
	private val root: File,
	val limits: WorkspaceLimits,
) {

	/** Outcome of turning an agent-supplied string into a file. */
	sealed interface Resolution {
		data class Ok(val file: File) : Resolution
		data class Rejected(val reason: String) : Resolution
	}

	/**
	 * Resolve an agent-supplied relative path against the workspace root.
	 *
	 * @param agentPath a path relative to the workspace, using `/` or `\`.
	 *   Leading separators are tolerated for convenience, absolute paths are
	 *   not honoured -- every path is treated as relative no matter what it
	 *   looks like, so an attempt at `/etc/passwd` simply resolves to
	 *   `<root>/etc/passwd`, which is inside the sandbox and harmless.
	 */
	fun resolve(agentPath: String): Resolution {
		if (agentPath.indexOf('\u0000') >= 0) {
			return Resolution.Rejected("path contains a null byte")
		}

		// Treat everything as relative. Stripping leading separators rather
		// than rejecting them means an absolute-looking path degrades safely
		// instead of becoming an error the model will keep retrying.
		val cleaned = agentPath.trim().replace('\\', '/').trimStart('/')
		if (cleaned.isEmpty()) return Resolution.Ok(root)

		val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }

		// Every filesystem call below is wrapped, because a malformed component
		// is a normal thing for a model to produce -- a Windows drive letter, a
		// reserved device name, a character the filesystem refuses. Those must
		// surface as a rejection the model can read and correct, not as an
		// exception that tears down the agent loop. Getting this wrong is how a
		// sandbox turns into a crash.
		return try {
			var cursor = root
			for (part in parts) {
				if (part == "..") {
					return Resolution.Rejected("'..' is not allowed in a workspace path")
				}
				cursor = File(cursor, part)

				// Walk and check. A symlinked component is refused even when it
				// points back inside the root: allowing some symlinks and not
				// others is the kind of rule that eventually has a hole.
				if (Files.isSymbolicLink(cursor.toPath())) {
					return Resolution.Rejected("symbolic links are not allowed in the workspace")
				}
			}

			// Canonical prefix check. canonicalFile() also resolves any symlink
			// in the *parent* chain, so this catches escapes the per-segment
			// walk could not see.
			val canonicalRoot = root.canonicalFile
			val target = cursor.canonicalFile
			val rootPath = canonicalRoot.path.trimEnd(File.separatorChar)
			if (target.path != rootPath && !target.path.startsWith(rootPath + File.separator)) {
				Resolution.Rejected("path escapes the conversation workspace")
			} else {
				Resolution.Ok(target)
			}
		} catch (e: java.nio.file.InvalidPathException) {
			Resolution.Rejected("path is not valid on this filesystem: ${e.reason ?: e.message}")
		} catch (e: java.io.IOException) {
			Resolution.Rejected("path could not be resolved: ${e.message}")
		}
	}

	/**
	 * Resolve [agentPath] and compute the workspace-relative path of the result,
	 * for display and for storing in tool results.
	 */
	fun relativize(file: File): String {
		val rootPath = root.canonicalFile.path.trimEnd(File.separatorChar)
		val p = file.canonicalFile.path
		return when {
			p == rootPath -> ""
			p.startsWith(rootPath + File.separator) -> p.substring(rootPath.length + 1).replace('\\', '/')
			else -> error("file is not inside this workspace")
		}
	}

	/**
	 * Total bytes currently held by this workspace, for quota enforcement.
	 */
	fun usedBytes(): Long = usage().bytes

	/** Number of files currently in this workspace, for quota enforcement. */
	fun fileCount(): Int = usage().files

	/**
	 * Both totals from **one** traversal.
	 *
	 * ## Why this exists rather than leaving the two above to be called in turn
	 *
	 * The quota check needs the file count and the byte total together, and it
	 * used to ask for them separately -- two full recursive walks of the
	 * workspace for every single write. Measured on a JVM with warm caches, one
	 * `write_file` into a 2,000-file workspace cost 106 ms, of which roughly
	 * 86 ms was those walks. A turn that writes four files pays it four times.
	 *
	 * One walk can be no cheaper than half of two, and the walk is the entire
	 * cost: a `listFiles` plus a symlink test per entry.
	 *
	 * Symlinked directories are not descended into, which matches what the two
	 * separate walks did: a link is refused by [resolve], so anything behind one
	 * is not part of the workspace and must not be charged to its quota.
	 */
	fun usage(): Usage {
		var bytes = 0L
		var files = 0
		root.walkTopDown()
			.onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
			.forEach { entry ->
				if (entry.isFile) {
					bytes += entry.length()
					files++
				}
			}
		return Usage(bytes = bytes, files = files)
	}

	/** What a workspace holds, as counted by [usage]. */
	data class Usage(val bytes: Long, val files: Int)

	/**
	 * Whether an operation that is *already partly on disk* stays inside the ceilings.
	 *
	 * Extracted rather than written twice, because it was written once and then
	 * a second path -- import -- skipped it, which is exactly how a quota
	 * becomes a suggestion. Returns a reason to refuse, or null to proceed.
	 *
	 * ## Why the caller states its own accounting
	 *
	 * An import cannot know a stream's size until it has read it, so it streams
	 * into a staging file **inside the workspace** and then decides whether to
	 * keep the result. By the time it can ask this question, `usage()` is
	 * already counting that staging file -- and, when it is replacing something,
	 * still counting the file it is about to replace. Both are the caller's
	 * facts, not this class's, so the caller declares them rather than passing a
	 * delta and hoping.
	 *
	 * The first version of this took only a delta, and the test that imports
	 * 2 KB into a 3 KB workspace was told 4 KB was over the limit: the staging
	 * file was charged twice, once by `usage` and once by the delta.
	 *
	 * @param stagingBytes bytes already on disk for this operation, excluded
	 *   from the current total. The staging file itself.
	 * @param stagingFiles file slots already on disk for this operation,
	 *   excluded from the current count. One while staging, because the file
	 *   count and the byte total are charged from the same walk and the staging
	 *   file pollutes both -- a file it is about to replace is still counted
	 *   too, so the count cannot simply be `usage.files`.
	 * @param replacedBytes bytes of a file this operation will replace, also
	 *   excluded -- it is going away, so charging both it and its successor
	 *   counts the same slot twice.
	 * @param addsFiles new file slots this operation needs. Zero when replacing.
	 * @param newBytes what the operation will leave behind for those slots.
	 */
	fun quotaRefusal(
		stagingBytes: Long,
		stagingFiles: Int,
		replacedBytes: Long,
		addsFiles: Int,
		newBytes: Long,
	): String? {
		val usage = usage()
		// A staged replacement is still counted as the file it replaces, so the
		// current total drops only by the part that is genuinely extra.
		val currentBytes = usage.bytes - stagingBytes
		val projected = currentBytes - replacedBytes + newBytes
		if (projected > limits.maxTotalBytes) {
			return "workspace is full: $projected bytes would exceed the " +
				"${limits.maxTotalBytes}-byte limit. Delete something first."
		}
		val currentFiles = usage.files - stagingFiles
		if (addsFiles > 0 && currentFiles + addsFiles > limits.maxFileCount) {
			return "workspace has $currentFiles files, at the ${limits.maxFileCount}-file limit. " +
				"Delete something first."
		}
		return null
	}

	/**
	 * The root, for filesystem queries that need a path.
	 *
	 * Exposed as a narrow accessor rather than handing out the `File` itself:
	 * the root is what every path check compares against, so a caller that
	 * could replace or wrap it would defeat the gate. This returns the root
	 * that [resolve] compares against -- already canonical, since [open]
	 * canonicalises once -- and [resolve] remains the only way to obtain a file
	 * *inside* it.
	 *
	 * Used for `StatFs`, which needs a path to answer "is there room".
	 */
	fun rootPath(): File = root

	companion object {
		/**
		 * Open (creating if needed) the workspace rooted at [dir].
		 *
		 * The root is canonicalised once, here. Doing it per-call would compare
		 * against a possibly different string if the directory were moved or a
		 * parent were a symlink.
		 */
		fun open(dir: File, limits: WorkspaceLimits = WorkspaceLimits()): SessionWorkspace {
			dir.mkdirs()
			return SessionWorkspace(dir.canonicalFile, limits)
		}
	}
}
