package com.inhuman.desktop.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the Room JVM/KSP/bundled-SQLite path, not the Android driver. */
class RoomJvmTest {

	@Test
	fun `room opens and round-trips on the JVM`() = runBlocking {
		val db = Room.inMemoryDatabaseBuilder<SpikeDb>()
			.setDriver(BundledSQLiteDriver())
			.build()
		try {
			db.rows().insert(SpikeRow("one", "desktop"))
			assertEquals("desktop", db.rows().byId("one")?.text)
		} finally {
			db.close()
		}
	}
}
