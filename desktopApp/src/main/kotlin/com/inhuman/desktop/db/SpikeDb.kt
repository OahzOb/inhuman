package com.inhuman.desktop.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * A deliberately tiny Room/JVM database.
 *
 * This is the spike's proof that the Room toolchain (KSP, the JVM runtime and a
 * bundled SQLite driver) works on the desktop. The real schema migration is a
 * separate step: it must move `InhumanDatabase` and the entities out of the
 * Android-only `:core:data` module, not duplicate them here.
 */
@Entity(tableName = "spike_rows")
data class SpikeRow(
	@PrimaryKey val id: String,
	val text: String,
)

@Dao
interface SpikeDao {
	@Insert
	suspend fun insert(row: SpikeRow)

	@Query("SELECT * FROM spike_rows WHERE id = :id")
	suspend fun byId(id: String): SpikeRow?
}

@Database(entities = [SpikeRow::class], version = 1, exportSchema = false)
abstract class SpikeDb : RoomDatabase() {
	abstract fun rows(): SpikeDao
}
