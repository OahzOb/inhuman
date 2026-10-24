package com.inhuman.desktop.backup

import com.inhuman.desktop.DesktopStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopBackupTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `export is readable only with the passphrase`() = runBlocking {
		val dataDir = tmp.newFolder("source")
		val store = DesktopStore.open(dataDir)
		try {
			store.create("Backup me")
			val backup = File(tmp.root, "test.inhuman")
			val exported = DesktopBackup.export(store, dataDir, backup, "correct horse".toCharArray())
			assertNull(exported.error)
			assertTrue(backup.length() > 0)

			val inspected = DesktopBackup.inspect(backup, "correct horse".toCharArray())
			assertNull(inspected.error)
			assertNotNull(inspected.info)

			val wrong = DesktopBackup.inspect(backup, "wrong".toCharArray())
			assertNotNull("a wrong passphrase must be reported", wrong.error)
		} finally {
			store.close()
		}
	}

	@Test
	fun `a staged restore replaces a fresh data directory`() = runBlocking {
		val source = tmp.newFolder("restore-source")
		var store = DesktopStore.open(source)
		val backup = File(tmp.root, "restore.inhuman")
		try {
			store.create("Restored conversation")
			store.appendUser(
				store.list().first().id,
				"hello from the backup",
				null,
			)
			val exported = DesktopBackup.export(store, source, backup, "passphrase".toCharArray())
			assertNull(exported.error)
		} finally {
			store.close()
		}

		val target = tmp.newFolder("restore-target")
		val staged = DesktopBackup.stageRestore(backup, "passphrase".toCharArray(), target)
		assertNull(staged.error)
		assertTrue("the marker is written", File(target, "restore.pending").isFile)
		assertTrue(DesktopBackup.applyPendingRestore(target))

		store = DesktopStore.open(target)
		try {
			assertEquals("Restored conversation", store.list().first().title)
			assertTrue(File(target, "restore.pending").let { !it.exists() })
		} finally {
			store.close()
		}
	}
}
