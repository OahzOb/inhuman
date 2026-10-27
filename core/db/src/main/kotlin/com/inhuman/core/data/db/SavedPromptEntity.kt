package com.inhuman.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A system prompt the user saved for reuse.
 *
 * ## Why this is not part of an assistant
 *
 * A prompt is text a person wrote **for** an assistant, not a property of one.
 * The same review instructions are useful to three assistants, and editing one
 * assistant's prompt should not rewrite the library. The assistant row still
 * stores its own copy; choosing from the library fills the editor's text and
 * nothing else.
 *
 * ## What it is not allowed to become
 *
 * This table is not a capability. No tool reads or writes it, it never enters a
 * request except as the system prompt the user chose, and the model cannot set
 * one entry as its own instructions. That is also why the title is unique: a
 * library entry is something a person names and recognises, and two rows with
 * the same name would make "which one is selected?" ambiguous.
 */
@Entity(
	tableName = "saved_prompts",
	indices = [Index(value = ["title"], unique = true)],
)
data class SavedPromptEntity(
	@PrimaryKey val id: String,
	/** What the picker shows. Trimmed by the repository; never sent to a model. */
	val title: String,
	/**
	 * The prompt text, stored verbatim.
	 *
	 * Not trimmed: whitespace can be deliberate in a prompt (indented examples,
	 * trailing newlines), and a library that edits the user's text is worse than
	 * one that stores it exactly.
	 */
	val text: String,
	val createdAt: Long,
	val updatedAt: Long,
)