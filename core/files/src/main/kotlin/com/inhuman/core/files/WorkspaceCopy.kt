package com.inhuman.core.files

/**
 * Copy one file from one workspace into another.
 *
 * This is the user's route between workspaces, not a tool: the source rules do
 * not apply to the owner, and the copy is a **snapshot** -- later changes to the
 * source do not follow. It deliberately cannot copy a directory or a reference:
 * a directory copy needs its own quota walk and failure semantics, and a
 * reference would mean two workspaces share one file, so one conversation's
 * rollback could write into another's materials.
 *
 * The destination name goes through [WorkspaceTransfer.uniqueName], so a second
 * copy is `name (2)` rather than an overwrite, and the bytes go through
 * [WorkspaceTransfer.import], so the per-file, total and free-space checks are
 * the same ones a system import gets.
 */
object WorkspaceCopy {

	/**
	 * @param sourceRelativePath a path from the source browser; re-resolved here
	 *   through [SessionWorkspace.resolve] rather than trusted.
	 * @param targetDirectory workspace-relative directory in the target, or
	 *   empty for the root.
	 */
	fun file(
		source: SessionWorkspace,
		sourceRelativePath: String,
		target: SessionWorkspace,
		targetDirectory: String,
	): WorkspaceTransfer.Outcome {
		val file = when (val resolution = source.resolve(sourceRelativePath)) {
			is SessionWorkspace.Resolution.Ok -> resolution.file
			is SessionWorkspace.Resolution.Rejected -> return WorkspaceTransfer.Outcome.Failed(
				resolution.reason,
			)
		}
		if (!file.isFile) {
			return WorkspaceTransfer.Outcome.Failed("not a file: $sourceRelativePath")
		}

		val transfer = WorkspaceTransfer(target)
		val name = transfer.uniqueName(file.name)
		val destination = if (targetDirectory.isBlank()) {
			name
		} else {
			"${targetDirectory.trimEnd('/')}/$name"
		}
		return transfer.import(destination) { file.inputStream() }
	}
}