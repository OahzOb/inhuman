package com.inhuman.download

import com.inhuman.core.ai.tools.DownloadSaveResult
import com.inhuman.core.ai.tools.DownloadSink
import com.inhuman.core.ai.tools.DownloadUrl
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.core.files.WorkspaceTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Where a `download_url` call's bytes land: `downloads/<name>` in the
 * conversation workspace, through the ordinary import path.
 *
 * ## Why not the file tools' write path
 *
 * `WorkspaceTransfer.import` is the one path that enforces the per-file ceiling,
 * the total quota, the file-count ceiling and the free-space reserve while the
 * bytes are streaming. A download that used a plain `writeText` would be able to
 * walk past the ceilings the workspace shows the user.
 *
 * ## Why a fixed directory and a unique name
 *
 * `downloads/` keeps the origin of the file obvious, and " (2)" rather than
 * overwrite matches the user's own download/import rule: fetching a file twice
 * usually means two files, and silently replacing the first is how work
 * disappears. The model can move or rename afterwards with the ordinary file
 * tools.
 */
class WorkspaceDownloadSink(
	private val workspace: SessionWorkspace,
) : DownloadSink {

	override suspend fun save(
		suggestedName: String,
		maxBytes: Long,
		body: InputStream,
	): DownloadSaveResult = withContext(Dispatchers.IO) {
		val safe = DownloadUrl.sanitizeName(suggestedName)
		val path = uniquePath(safe)
		when (val outcome = WorkspaceTransfer(workspace).import(path, maxBytes) { body }) {
			is WorkspaceTransfer.Outcome.Ok ->
				DownloadSaveResult.Saved(outcome.relativePath, outcome.bytes)
			is WorkspaceTransfer.Outcome.Failed ->
				DownloadSaveResult.Failed(outcome.reason)
		}
	}

	/**
	 * `downloads/name.ext`, then `downloads/name (2).ext`.
	 *
	 * Bounded like [WorkspaceTransfer.uniqueName]; a directory that happens to
	 * exist at the candidate path is skipped too, because the import would
	 * otherwise fail with "is a directory" on the second attempt.
	 */
	private fun uniquePath(name: String): String {
		val dot = name.lastIndexOf('.')
		var candidate = "downloads/$name"
		var n = 2
		while (exists(candidate)) {
			val next = if (dot > 0) {
				"${name.substring(0, dot)} ($n)${name.substring(dot)}"
			} else {
				"$name ($n)"
			}
			candidate = "downloads/$next"
			n++
			if (n > 200) return "downloads/$n-$name"
		}
		return candidate
	}

	private fun exists(relativePath: String): Boolean =
		when (val resolved = workspace.resolve(relativePath)) {
			is SessionWorkspace.Resolution.Ok -> resolved.file.exists()
			is SessionWorkspace.Resolution.Rejected -> false
		}
}
