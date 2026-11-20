package com.inhuman.core.data

import java.io.File

/**
 * The directories the data layer owns, supplied by the host.
 *
 * These used to be derived from Android's `context.filesDir` inside the
 * repository, which is precisely why the repository could not run on a plain
 * JVM. The layout is unchanged -- `conversations/` for one workspace
 * per conversation and `rollback/` for the checkpoint store, both under the
 * app's private files directory -- so an existing Android install opens the
 * same files after the repository moved modules.
 *
 * A temporary workspace lives and dies with its conversation; a permanent one
 * survives it. The directory is named by the opaque workspace id, never by the
 * conversation title, so renaming a conversation never moves a file.
 */
class DataPaths(val filesDir: File) {

	/** One directory per workspace, named by workspace id. */
	val workspacesRoot: File
		get() = File(filesDir, "conversations").apply { mkdirs() }

	fun workspaceDir(workspaceId: String): File = File(workspacesRoot, workspaceId)

	/**
	 * Checkpoints live outside `conversations/`: they are not workspace content,
	 * must not appear in Files, and restoring one must not be able to recurse
	 * into the snapshot itself.
	 */
	val rollbackRoot: File
		get() = File(filesDir, "rollback").apply { mkdirs() }

	fun rollbackDir(conversationId: String): File = File(rollbackRoot, conversationId)
}
