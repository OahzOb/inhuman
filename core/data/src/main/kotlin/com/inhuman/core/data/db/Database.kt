package com.inhuman.core.data.db

import android.content.Context
import androidx.room.Room
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.DataPaths
import java.io.File

/**
 * Opens the database.
 *
 * A single instance for the process: Room's own concurrency is per-instance, so
 * two instances against the same file would serialise badly and could hit
 * SQLite locking that one instance would have avoided.
 */
object Database {

	@Volatile
	private var instance: InhumanDatabase? = null

	fun open(context: Context): InhumanDatabase =
		instance ?: synchronized(this) {
			instance ?: build(context).also { instance = it }
		}

	private fun build(context: Context): InhumanDatabase {
		Diag.i("db", "opening ${InhumanDatabase.NAME}")
		return try {
			// The list and the seeding callback live in `:core:db`, because the
			// desktop host builds from a file path and must install the same
			// set. A host-local copy of either is how an upgrade path goes
			// missing.
			//
			// Deliberately no fallbackToDestructiveMigration. It would make a
			// missing migration look like a successful upgrade while deleting
			// every conversation, and the whole point of writing the migration
			// is that the data is the user's.
			Room.databaseBuilder(
				context.applicationContext,
				InhumanDatabase::class.java,
				InhumanDatabase.NAME,
			)
				.addMigrations(*InhumanDatabase.MIGRATIONS)
				.addCallback(InhumanDatabase.callback())
				.build()
		} catch (e: Exception) {
			// A schema that will not open takes the app with it. The crash
			// handler would record the stack, but not that it was the database
			// path and which name -- this line supplies both, before the
			// rethrow.
			Diag.e("db", "open failed ${InhumanDatabase.NAME}", e)
			throw e
		}
	}

	/**
	 * The assistant every install starts with.
	 *
	 * Its instructions are written out rather than left blank, because an empty
	 * system prompt gives a model nothing to work with and makes the first
	 * conversation a poor introduction. They say what the app is for -- the
	 * workspace -- which is the one thing a user would otherwise have to
	 * discover by accident.
	 */
	/** Stable id so a reinstall cannot produce two defaults. */
	const val DEFAULT_AGENT_ID = "default-agent"

	val DEFAULT_SYSTEM_PROMPT = """
		You are a helpful assistant.

		You have a workspace: a folder belonging to this conversation that you can
		read and write with the tools provided. It is the only place you can
		touch — every path you pass is relative to it, and you cannot reach
		anything outside it. Use it when a task involves files, and prefer
		writing results there over describing them, so the user can open them.

		Be direct. Answer the question that was asked, in as many words as it
		needs and no more.
	""".trimIndent()

	/**
	 * The Android view of the directories `DataPaths` owns.
	 *
	 * Delegated rather than re-derived: the backup code and the instrumentation
	 * tests reach for these, while the repositories take `DataPaths` directly.
	 * Two copies of `files/conversations/<id>` is how a test ends up asserting a
	 * layout the app no longer uses.
	 */
	fun workspacesRoot(context: Context): File =
		DataPaths(context.filesDir).workspacesRoot

	/** The workspace directory for one conversation. */
	fun workspaceDir(context: Context, workspaceId: String): File =
		DataPaths(context.filesDir).workspaceDir(workspaceId)

	/**
	 * Rollback snapshots live outside `conversations/`: they are not workspace
	 * content, must not appear in Files, and restoring one must not be able to
	 * recurse into the snapshot itself.
	 */
	fun rollbackRoot(context: Context): File =
		DataPaths(context.filesDir).rollbackRoot

	fun rollbackDir(context: Context, conversationId: String): File =
		DataPaths(context.filesDir).rollbackDir(conversationId)
}
