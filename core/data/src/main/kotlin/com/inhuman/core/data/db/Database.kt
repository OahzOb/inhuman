package com.inhuman.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.inhuman.core.common.diag.Diag
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
			Room.databaseBuilder(
				context.applicationContext,
				InhumanDatabase::class.java,
				InhumanDatabase.NAME,
			)
				.addMigrations(
					InhumanDatabase.MIGRATION_1_2,
					InhumanDatabase.MIGRATION_2_3,
					InhumanDatabase.MIGRATION_3_4,
					InhumanDatabase.MIGRATION_4_5,
					InhumanDatabase.MIGRATION_5_6,
					InhumanDatabase.MIGRATION_6_7,
					InhumanDatabase.MIGRATION_7_8,
					InhumanDatabase.MIGRATION_8_9,
					InhumanDatabase.MIGRATION_9_10,
					InhumanDatabase.MIGRATION_10_11,
					InhumanDatabase.MIGRATION_11_12,
					InhumanDatabase.MIGRATION_12_13,
				)
				// Deliberately no fallbackToDestructiveMigration. It would make
				// a missing migration look like a successful upgrade while
				// deleting every conversation, and the whole point of writing
				// the migration is that the data is the user's.
				.addCallback(object : RoomDatabase.Callback() {
					override fun onCreate(connection: SQLiteConnection) {
						super.onCreate(connection)
						// Seeded in onCreate rather than on first read, so a
						// conversation is never created without an assistant to
						// attach to. A lazily-created default would mean the very
						// first conversation has none, which is exactly the case
						// the default exists for.
						InhumanDatabase.seedDefaultProvider(connection)
						InhumanDatabase.seedDefaultAgent(connection)
						Diag.i("db", "created ${InhumanDatabase.NAME}")
					}

					override fun onOpen(connection: SQLiteConnection) {
						super.onOpen(connection)
						Diag.i("db", "opened ${InhumanDatabase.NAME}")
					}
				})
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

	/** Directory holding one workspace per conversation. */
	fun workspacesRoot(context: Context): File =
		File(context.filesDir, "conversations").apply { mkdirs() }

	/** The workspace directory for one conversation. */
	fun workspaceDir(context: Context, workspaceId: String): File =
		File(workspacesRoot(context), workspaceId)

	/**
	 * Rollback snapshots live outside `conversations/`: they are not workspace
	 * content, must not appear in Files, and restoring one must not be able to
	 * recurse into the snapshot itself.
	 */
	fun rollbackRoot(context: Context): File =
		File(context.filesDir, "rollback").apply { mkdirs() }

	fun rollbackDir(context: Context, conversationId: String): File =
		File(rollbackRoot(context), conversationId)
}
