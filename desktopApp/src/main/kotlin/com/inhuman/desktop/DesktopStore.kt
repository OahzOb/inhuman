package com.inhuman.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.DataPaths
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import java.util.UUID

/** One row the window can draw. */
data class StoredLine(
	val id: String,
	val fromUser: Boolean,
	val text: String,
	val reasoning: String,
)

/**
 * The desktop host's data layer.
 *
 * The window used to keep its transcript in a `mutableStateListOf`: closing it
 * lost the conversation, and the repository code could not be called from a
 * plain JVM anyway. This opens the same `InhumanDatabase` the Android app uses,
 * with the bundled SQLite driver, and stores messages through the same
 * `ConversationRepository`.
 *
 * The layout under the data directory is deliberately the Android one --
 * `inhuman.db`, `files/conversations/<workspaceId>` -- so the two hosts open
 * the same shape of store rather than growing parallel conventions.
 */
class DesktopStore private constructor(
	private val db: InhumanDatabase,
	private val conversations: ConversationRepository,
	private val conversationId: String,
) {

	data class Loaded(val messages: List<StoredLine>)

	suspend fun load(): Loaded {
		val messages = conversations.conversation(conversationId)?.messages.orEmpty()
			.filter { it.role == "user" || it.role == "assistant" }
			.map { message ->
				StoredLine(
					id = message.id,
					fromUser = message.role == "user",
					text = message.content,
					reasoning = message.reasoning.orEmpty(),
				)
			}
		return Loaded(messages)
	}

	suspend fun appendUser(text: String, parentId: String?): StoredLine {
		val now = System.currentTimeMillis()
		val message = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parentId,
			role = "user",
			content = text,
			createdAt = now,
		)
		conversations.appendMessage(message, parentId)
		return StoredLine(message.id, fromUser = true, text = text, reasoning = "")
	}

	suspend fun appendAssistant(
		text: String,
		reasoning: String,
		parentId: String?,
	): StoredLine {
		val message = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parentId,
			role = "assistant",
			content = text,
			reasoning = reasoning.takeIf { it.isNotBlank() },
			createdAt = System.currentTimeMillis(),
		)
		conversations.appendMessage(message, parentId)
		return StoredLine(message.id, fromUser = false, text = text, reasoning = reasoning)
	}

	fun close() {
		db.close()
	}

	companion object {
		/** `%LOCALAPPDATA%\inhuman` on Windows, the XDG data directory elsewhere. */
		fun defaultDataDir(): File {
			val os = System.getProperty("os.name").lowercase(Locale.ROOT)
			val base = if (os.contains("windows")) {
				System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
			} else {
				System.getenv("XDG_DATA_HOME")
					?: File(System.getProperty("user.home"), ".local/share").absolutePath
			}
			return File(base, "inhuman").apply { mkdirs() }
		}

		fun open(dataDir: File = defaultDataDir()): DesktopStore {
			dataDir.mkdirs()
			val db = Room.databaseBuilder<InhumanDatabase>(
				File(dataDir, InhumanDatabase.NAME).absolutePath,
			)
				.setDriver(BundledSQLiteDriver())
				// The same migrations and seed callback as the Android host, from
				// `:core:db`. A desktop copy of either would be a second answer
				// to "what does this database look like on first launch".
				.addMigrations(*InhumanDatabase.MIGRATIONS)
				.addCallback(InhumanDatabase.callback())
				.build()
			val conversations = ConversationRepository(db, DataPaths(File(dataDir, "files")))
			val conversation = runBlocking {
				conversations.all().firstOrNull() ?: conversations.create(title = "Desktop")
			}
			return DesktopStore(db, conversations, conversation.id)
		}
	}
}
