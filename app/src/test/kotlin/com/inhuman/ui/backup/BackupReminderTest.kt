package com.inhuman.ui.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reminder's arithmetic: one period, measured from the last export or from
 * first use, suppressed by "Later" and never fired for an empty install.
 */
class BackupReminderTest {

	private val now = 200L * BACKUP_DAY_MS

	private fun config(
		lastExportAt: Long = 0L,
		trackingSince: Long = now - 10L * BACKUP_DAY_MS,
		periodDays: Int = 30,
		snoozedUntil: Long = 0L,
	) = BackupReminderConfig(lastExportAt, trackingSince, periodDays, snoozedUntil)

	@Test
	fun `an install with no data is never reminded`() {
		assertNull(BackupReminderPolicy.evaluate(now, config(periodDays = 7), hasData = false))
	}

	@Test
	fun `off is off`() {
		assertNull(
			BackupReminderPolicy.evaluate(
				now,
				config(lastExportAt = now - 100L * BACKUP_DAY_MS, periodDays = 0),
				hasData = true,
			),
		)
	}

	@Test
	fun `a never-exported install is reminded after the period since first use`() {
		assertNull(
			BackupReminderPolicy.evaluate(
				now,
				config(trackingSince = now - 29L * BACKUP_DAY_MS, periodDays = 30),
				hasData = true,
			),
		)
		val due = BackupReminderPolicy.evaluate(
			now,
			config(trackingSince = now - 31L * BACKUP_DAY_MS, periodDays = 30),
			hasData = true,
		)
		assertEquals(BackupReminderUi(daysSince = 31, neverBackedUp = true), due)
	}

	@Test
	fun `a previous export is the baseline`() {
		val due = BackupReminderPolicy.evaluate(
			now,
			config(lastExportAt = now - 8L * BACKUP_DAY_MS, periodDays = 7),
			hasData = true,
		)
		assertEquals(BackupReminderUi(daysSince = 8, neverBackedUp = false), due)
		assertNull(
			BackupReminderPolicy.evaluate(
				now,
				config(lastExportAt = now - 6L * BACKUP_DAY_MS, periodDays = 7),
				hasData = true,
			),
		)
	}

	@Test
	fun `later suppresses the reminder until its time passes`() {
		assertNull(
			BackupReminderPolicy.evaluate(
				now,
				config(
					lastExportAt = now - 40L * BACKUP_DAY_MS,
					snoozedUntil = now + BACKUP_DAY_MS,
				),
				hasData = true,
			),
		)
		assertEquals(
			40L,
			BackupReminderPolicy.evaluate(
				now,
				config(
					lastExportAt = now - 40L * BACKUP_DAY_MS,
					snoozedUntil = now - 1L,
				),
				hasData = true,
			)?.daysSince,
		)
	}

	@Test
	fun `a clock that goes backwards does not produce a reminder`() {
		assertNull(
			BackupReminderPolicy.evaluate(
				now,
				config(lastExportAt = now + BACKUP_DAY_MS),
				hasData = true,
			),
		)
	}

	@Test
	fun `only the offered periods are stored`() {
		assertEquals(30, BackupReminderPolicy.normalizePeriod(null))
		assertEquals(0, BackupReminderPolicy.normalizePeriod(0))
		assertEquals(7, BackupReminderPolicy.normalizePeriod(7))
		assertEquals(90, BackupReminderPolicy.normalizePeriod(90))
		assertEquals(30, BackupReminderPolicy.normalizePeriod(12))
		assertEquals(30, BackupReminderPolicy.normalizePeriod(-1))
	}
}