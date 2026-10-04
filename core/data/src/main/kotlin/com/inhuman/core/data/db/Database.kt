package com.inhuman.core.data.db

import android.content.Context
import androidx.room.Room
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
			instance ?: Room.databaseBuilder(
				context.applicationContext,
				InhumanDatabase::class.java,
				InhumanDatabase.NAME,
			)
				.addMigrations(InhumanDatabase.MIGRATION_1_2)
				// Deliberately no fallbackToDestructiveMigration. It would make
				// a missing migration look like a successful upgrade while
				// deleting every conversation, and the whole point of writing
				// the migration is that the data is the user's.
				.build()
				.also { instance = it }
		}

	/** Directory holding one workspace per conversation. */
	fun workspacesRoot(context: Context): File =
		File(context.filesDir, "conversations").apply { mkdirs() }

	/** The workspace directory for one conversation. */
	fun workspaceDir(context: Context, workspaceId: String): File =
		File(workspacesRoot(context), workspaceId)
}
