package com.inhuman.ui.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BackupFolderTest {

	private val shanghai = ZoneId.of("Asia/Shanghai")

	@Test
	fun `archive filename formats timestamp in the given zone`() {
		val epochMs = LocalDateTime.of(2026, 11, 26, 13, 45, 9)
			.atZone(shanghai)
			.toInstant()
			.toEpochMilli()
		assertEquals(
			"inhuman-backup-20261126-134509.ibak",
			backupArchiveFileName(epochMs, shanghai),
		)
	}

	@Test
	fun `candidate filename appends collision counter before extension`() {
		val base = "inhuman-backup-20261126-134509.ibak"
		assertEquals(base, backupCandidateFileName(base, 0))
		assertEquals(
			"inhuman-backup-20261126-134509 (1).ibak",
			backupCandidateFileName(base, 1),
		)
		assertEquals(
			"inhuman-backup-20261126-134509 (12).ibak",
			backupCandidateFileName(base, 12),
		)
		assertEquals(
			"inhuman-backup (2)",
			backupCandidateFileName("inhuman-backup", 2),
		)
	}

	@Test
	fun `folder display name extracts readable path from SAF tree ids and URIs`() {
		assertNull(backupFolderDisplayName(null))
		assertNull(backupFolderDisplayName("   "))
		assertEquals(
			"Sync/inhuman",
			backupFolderDisplayName(
				uriString = "content://com.android.externalstorage.documents/tree/primary%3ASync%2Finhuman",
				treeDocumentId = "primary:Sync/inhuman",
			),
		)
		assertEquals(
			"Sync/inhuman",
			backupFolderDisplayName(
				uriString = "content://com.android.externalstorage.documents/tree/primary%3ASync%2Finhuman",
			),
		)
		assertEquals(
			"Backups/inhuman",
			backupFolderDisplayName(
				uriString = "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FBackups%2Finhuman",
				treeDocumentId = "raw:/storage/emulated/0/Backups/inhuman",
			),
		)
		assertEquals(
			"primary",
			backupFolderDisplayName(
				uriString = "content://com.android.externalstorage.documents/tree/primary%3A",
				treeDocumentId = "primary:",
			),
		)
	}
}
