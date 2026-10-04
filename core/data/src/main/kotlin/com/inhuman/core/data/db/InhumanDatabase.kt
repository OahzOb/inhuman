package com.inhuman.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

	@Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
	fun observeAll(): Flow<List<ConversationEntity>>

	@Query("SELECT * FROM conversations WHERE id = :id")
	suspend fun byId(id: String): ConversationEntity?

	@Query("SELECT * FROM conversations WHERE id = :id")
	fun observe(id: String): Flow<ConversationEntity?>

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(conversation: ConversationEntity)

	@Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
	suspend fun rename(id: String, title: String, now: Long)

	@Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
	suspend fun setPinned(id: String, pinned: Boolean)

	@Query("UPDATE conversations SET model = :model WHERE id = :id")
	suspend fun setModel(id: String, model: String?)

	@Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
	suspend fun touch(id: String, now: Long)

	@Query("DELETE FROM conversations WHERE id = :id")
	suspend fun delete(id: String)
}

@Dao
interface MessageDao {

	@Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
	fun observeAll(conversationId: String): Flow<List<MessageEntity>>

	@Query("SELECT * FROM messages WHERE conversationId = :conversationId")
	suspend fun all(conversationId: String): List<MessageEntity>

	@Query("SELECT * FROM messages WHERE id = :id")
	suspend fun byId(id: String): MessageEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(message: MessageEntity)

	@Update
	suspend fun update(message: MessageEntity)

	@Query("UPDATE messages SET selectedChildId = :childId WHERE id = :parentId")
	suspend fun setSelectedChild(parentId: String, childId: String?)

	@Query("DELETE FROM messages WHERE id = :id")
	suspend fun delete(id: String)

	@Query("DELETE FROM messages WHERE conversationId = :conversationId")
	suspend fun deleteAllIn(conversationId: String)
}

@Database(
	entities = [ConversationEntity::class, MessageEntity::class],
	version = 1,
	exportSchema = true,
)
abstract class InhumanDatabase : RoomDatabase() {
	abstract fun conversations(): ConversationDao
	abstract fun messages(): MessageDao

	companion object {
		const val NAME = "inhuman.db"
	}
}

/**
 * Reads and writes that must not interleave.
 *
 * Kept out of the DAOs because they span both tables: appending a message and
 * marking the conversation as updated are one logical operation, and a reader
 * that observed the first without the second would see a message that appears
 * to belong to no recent conversation.
 */
class ConversationStore(private val db: InhumanDatabase) {

	/**
	 * Walk the active branch of a conversation.
	 *
	 * The tree is stored as parent links plus a `selectedChildId` on each node,
	 * so the visible transcript is the chain obtained by starting at the root
	 * and repeatedly following the selected child. Returning the whole table and
	 * filtering in Kotlin rather than doing this in SQL is deliberate: the query
	 * would be a recursive CTE, and the number of messages in one conversation
	 * is small enough that clarity wins.
	 */
	fun activePath(messages: List<MessageEntity>): List<MessageEntity> {
		if (messages.isEmpty()) return emptyList()

		val byParent = messages.groupBy { it.parentId }
		val roots = messages.filter { it.parentId == null }.sortedBy { it.createdAt }
		if (roots.isEmpty()) return emptyList()

		// A conversation has one root in practice; if a branch ever produced
		// several, the earliest wins so the choice is at least deterministic.
		val path = mutableListOf<MessageEntity>()
		var current: MessageEntity? = roots.first()
		while (current != null) {
			path += current
			val children = byParent[current.id].orEmpty()
			val selected = current.selectedChildId?.let { id -> children.firstOrNull { it.id == id } }
			// Fall back to the newest child when nothing is marked selected:
			// that is what a freshly appended reply is, and it keeps a
			// transcript readable if a write was interrupted.
			current = selected ?: children.maxByOrNull { it.createdAt }
		}
		return path
	}

	@Transaction
	suspend fun append(message: MessageEntity, parentId: String?) {
		db.messages().upsert(message)
		if (parentId != null) {
			db.messages().setSelectedChild(parentId, message.id)
		}
		db.conversations().touch(message.conversationId, message.createdAt)
	}

	@Transaction
	suspend fun createConversation(conversation: ConversationEntity, firstMessage: MessageEntity?) {
		db.conversations().upsert(conversation)
		firstMessage?.let { db.messages().upsert(it) }
	}

	@Transaction
	suspend fun deleteConversation(id: String) {
		// The foreign key cascades, but deleting explicitly keeps the behaviour
		// obvious and survives anyone later switching the cascade off.
		db.messages().deleteAllIn(id)
		db.conversations().delete(id)
	}
}
