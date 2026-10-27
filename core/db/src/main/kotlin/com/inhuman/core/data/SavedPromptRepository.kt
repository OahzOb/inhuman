package com.inhuman.core.data

import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.SavedPromptEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The user's saved system prompts.
 *
 * ## Saving by title, not by id
 *
 * The assistant editor is the only writer, and it has a text field and no
 * concept of "edit which row". Saving with a title that already exists updates
 * that row's text and keeps its `createdAt`; a new title creates a row. That is
 * how a user iterates on a prompt without growing a list of near-duplicates,
 * and it is why the table's title index is unique.
 *
 * ## What this repository must never expose
 *
 * There is no tool-facing API here, and none should be added. The model may see
 * a prompt only after the user puts it in an assistant's system prompt; the
 * library itself is not context, and no tool may read, write or select it.
 */
class SavedPromptRepository(private val db: InhumanDatabase) {

	fun observeAll(): Flow<List<SavedPromptEntity>> = db.savedPrompts().observeAll()

	suspend fun all(): List<SavedPromptEntity> = db.savedPrompts().all()

	/**
	 * Create or update an entry.
	 *
	 * @return the row id, or null when the title or text is blank. Blank input is
	 *   not an error worth surfacing: the Save button is disabled for it.
	 */
	suspend fun save(title: String, text: String): String? {
		val cleanTitle = title.trim()
		if (cleanTitle.isEmpty() || text.isBlank()) return null
		val now = System.currentTimeMillis()
		val existing = db.savedPrompts().byTitle(cleanTitle)
		val id = existing?.id ?: UUID.randomUUID().toString()
		db.savedPrompts().upsert(
			SavedPromptEntity(
				id = id,
				title = cleanTitle,
				text = text,
				createdAt = existing?.createdAt ?: now,
				updatedAt = now,
			),
		)
		return id
	}

	suspend fun delete(id: String) {
		db.savedPrompts().delete(id)
	}
}