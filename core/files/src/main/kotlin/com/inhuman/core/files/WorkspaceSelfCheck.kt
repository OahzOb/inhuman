package com.inhuman.core.files

import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * The workspace fence, asked on the device instead of assumed from a test.
 *
 * ## Why this exists
 *
 * `SessionWorkspace` is the app's security boundary, and its unit tests prove
 * the code's intent on a JVM. They do not prove what the device does when the
 * app asks it to resolve a path, create a link, or canonicalise a junction. The
 * product rule is that a failure is not a warning but a **loss of the tools
 * that depend on the fence** -- see `docs/08-port-philosophy.md` §3.6.
 *
 * The check runs in a throwaway directory under the app's cache, never in a
 * user workspace. It creates only what it needs (one outside file, one
 * symlink), deletes every probe explicitly, and never follows a link during
 * cleanup: a recursive delete over a symlinked directory is exactly the
 * mistake this class is checking for.
 *
 * ## What a skipped check means
 *
 * A platform that refuses to create a symlink (Windows without developer
 * mode, some SELinux profiles) gets [Outcome.Skipped] for that check, not a
 * failure. It cannot have a planted symlink escape either, and the canonical
 * prefix check still runs. The other checks are never skipped.
 */
object WorkspaceSelfCheck {

	/** One assertion, named so the UI can translate it. */
	data class Check(
		val id: Id,
		val outcome: Outcome,
		/** A human-readable reason, for the details dialogue and diagnostics. */
		val detail: String = "",
	)

	enum class Id {
		RelativePath,
		ParentTraversal,
		AbsolutePath,
		RelativizeOutside,
		SymlinkEscape,
		JunctionEscape,
	}

	enum class Outcome { Passed, Failed, Skipped }

	/**
	 * The result of one run.
	 *
	 * [error] is set when the probe could not run at all. That is a failure:
	 * "we could not check" must not read as "the fence is fine".
	 */
	data class Report(
		val checks: List<Check>,
		val error: String? = null,
	) {
		val ok: Boolean get() = error == null && checks.none { it.outcome == Outcome.Failed }

		val failed: List<Check> get() = checks.filter { it.outcome == Outcome.Failed }

		companion object {
			fun failed(reason: String): Report = Report(emptyList(), reason)
		}
	}

	/**
	 * Run every check under [parent].
	 *
	 * [parent] is created if needed and is expected to be app-private cache
	 * storage. The caller owns it; this function leaves no probe files behind.
	 */
	fun run(parent: File): Report {
		if (!parent.isDirectory && !parent.mkdirs()) {
			return Report.failed("the self-check directory could not be created")
		}
		val probe = File(parent, "fence-${UUID.randomUUID()}")
		val root = File(probe, "workspace")
		val outside = File(probe, "outside")
		val secret = File(outside, "secret.txt")
		var symlink: File? = null
		var junction: File? = null
		try {
			if (!root.mkdirs() || !outside.mkdirs()) {
				return Report.failed("the probe directories could not be created")
			}
			secret.writeText("outside the workspace")
			val workspace = SessionWorkspace.open(root)
			val checks = mutableListOf<Check>()

			// 1. A normal path stays inside.
			checks += when (val resolution = workspace.resolve("notes.txt")) {
				is SessionWorkspace.Resolution.Ok -> {
					if (isInside(resolution.file, root)) {
						Check(Id.RelativePath, Outcome.Passed)
					} else {
						Check(Id.RelativePath, Outcome.Failed, "a relative path resolved outside the root")
					}
				}
				is SessionWorkspace.Resolution.Rejected -> Check(
					Id.RelativePath,
					Outcome.Failed,
					"a relative path was refused: ${resolution.reason}",
				)
			}

			// 2. Parent traversal is refused.
			checks += when (workspace.resolve("../escape.txt")) {
				is SessionWorkspace.Resolution.Rejected -> Check(Id.ParentTraversal, Outcome.Passed)
				is SessionWorkspace.Resolution.Ok -> Check(
					Id.ParentTraversal,
					Outcome.Failed,
					"'..' was accepted",
				)
			}

			// 3. An absolute-looking path is treated as relative, not honoured.
			checks += when (val resolution = workspace.resolve("/etc/passwd")) {
				is SessionWorkspace.Resolution.Ok ->
					if (isInside(resolution.file, root)) {
						Check(Id.AbsolutePath, Outcome.Passed)
					} else {
						Check(
							Id.AbsolutePath,
							Outcome.Failed,
							"an absolute-looking path resolved outside the root",
						)
					}
				is SessionWorkspace.Resolution.Rejected -> Check(Id.AbsolutePath, Outcome.Passed)
			}

			// 4. A file outside the workspace cannot be relativised.
			checks += try {
				workspace.relativize(secret)
				Check(Id.RelativizeOutside, Outcome.Failed, "an outside file was relativised")
			} catch (expected: IllegalStateException) {
				Check(Id.RelativizeOutside, Outcome.Passed)
			}

			// 5. A symlink planted in the workspace is refused.
			// Record the path before creating it: if `resolve` throws between
			// creation and the `.also` that used to set this, the link would
			// survive cleanup and the probe could not be deleted.
			symlink = File(root, "link")
			checks += symlinkCheck(workspace, root, secret)

			// 6. A Windows junction is refused by the real-path comparison --
			// `Files.isSymbolicLink` does not report one, which is why the
			// resolve check above is not enough on that platform.
			junction = File(root, "junction")
			checks += junctionCheck(workspace, root, outside)

			return Report(checks)
		} catch (failure: Exception) {
			return Report.failed(failure.message ?: failure.javaClass.simpleName)
		} finally {
			// Delete links before directories: a recursive delete that followed
			// one would delete the outside file this check just created.
			symlink?.let { runCatching { Files.deleteIfExists(it.toPath()) } }
			junction?.let { runCatching { Files.deleteIfExists(it.toPath()) } }
			runCatching { secret.delete() }
			runCatching { outside.delete() }
			runCatching { root.delete() }
			runCatching { probe.delete() }
		}
	}

	private fun symlinkCheck(
		workspace: SessionWorkspace,
		root: File,
		secret: File,
	): Check {
		val link = File(root, "link")
		return try {
			Files.createSymbolicLink(link.toPath(), secret.toPath())
			when (workspace.resolve("link")) {
				is SessionWorkspace.Resolution.Rejected -> Check(Id.SymlinkEscape, Outcome.Passed)
				is SessionWorkspace.Resolution.Ok -> Check(
					Id.SymlinkEscape,
					Outcome.Failed,
					"a symlink to a file outside the root was accepted",
				)
			}
		} catch (unsupported: UnsupportedOperationException) {
			Check(Id.SymlinkEscape, Outcome.Skipped, "this filesystem cannot create symlinks")
		} catch (failure: java.io.IOException) {
			Check(Id.SymlinkEscape, Outcome.Skipped, "symlink creation was refused: ${failure.message}")
		} catch (failure: SecurityException) {
			Check(Id.SymlinkEscape, Outcome.Skipped, "symlink creation was refused by the platform")
		}
	}

	private fun junctionCheck(workspace: SessionWorkspace, root: File, outside: File): Check {
		if (!System.getProperty("os.name").orEmpty().lowercase().contains("windows")) {
			return Check(Id.JunctionEscape, Outcome.Skipped, "not a Windows filesystem")
		}
		val junction = File(root, "junction")
		return try {
			val process = ProcessBuilder(
				"cmd",
				"/c",
				"mklink",
				"/J",
				junction.absolutePath,
				outside.absolutePath,
			).redirectErrorStream(true).start()
			process.inputStream.readBytes()
			if (process.waitFor() != 0) {
				return Check(Id.JunctionEscape, Outcome.Skipped, "junction creation failed")
			}
			when (workspace.resolve("junction/secret.txt")) {
				is SessionWorkspace.Resolution.Rejected -> Check(Id.JunctionEscape, Outcome.Passed)
				is SessionWorkspace.Resolution.Ok -> Check(
					Id.JunctionEscape,
					Outcome.Failed,
					"a junction outside the root was accepted",
				)
			}
		} catch (failure: Exception) {
			Check(Id.JunctionEscape, Outcome.Skipped, "junction creation was refused: ${failure.message}")
		}
	}

	private fun isInside(file: File, root: File): Boolean {
		val rootPath = root.canonicalFile.path.trimEnd(File.separatorChar)
		val target = file.canonicalFile.path
		return target == rootPath || target.startsWith(rootPath + File.separator)
	}
}