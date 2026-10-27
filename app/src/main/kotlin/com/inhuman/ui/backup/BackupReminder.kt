package com.inhuman.ui.backup

import com.inhuman.core.data.secret.SecureStore

/** One day in milliseconds; the reminder is a calendar-scale question. */
internal const val BACKUP_DAY_MS = 24L * 60 * 60 * 1000

internal const val BACKUP_DEFAULT_REMINDER_DAYS = 30

/** How long "Later" buys. Long enough not to nag, short enough to return. */
internal const val BACKUP_SNOOZE_DAYS = 3L

/** The periods the settings chips offer; 0 is "off". */
internal val BACKUP_REMINDER_PERIODS = listOf(7, 30, 90, 0)

/**
 * The in-app backup prompt.
 *
 * @property daysSince days since the last export, or since tracking began when
 *   there has never been one.
 * @property neverBackedUp true when [daysSince] is measured from first use.
 */
data class BackupReminderUi(
	val daysSince: Long,
	val neverBackedUp: Boolean,
)

/** The stored inputs to [BackupReminderPolicy.evaluate], kept explicit for tests. */
data class BackupReminderConfig(
	val lastExportAt: Long,
	val trackingSince: Long,
	val periodDays: Int,
	val snoozedUntil: Long,
)

/**
 * Whether to show the reminder.
 *
 * ## Why this is only a reminder
 *
 * A backup has to be encrypted with a passphrase the user chooses, and the app
 * intentionally keeps neither the passphrase nor a copy of the archive. A
 * background job cannot therefore write a useful backup without inventing
 * storage for a credential and a retention policy; both are unresolved
 * product questions. What remains is honest and cheap: notice that the export
 * is overdue and offer a button. The export itself is still user-initiated.
 *
 * ## Why a baseline of first use
 *
 * `lastExportAt == 0` is the common case for a new install. Measuring from
 * [trackingSince] (written on the first launch) means someone who never exports
 * gets the first reminder after one period instead of never, without nagging on
 * day one. A clock that moves backwards produces no reminder rather than a
 * negative age.
 */
object BackupReminderPolicy {

	/** A period the UI does not offer is not a value to act on. */
	fun normalizePeriod(days: Int?): Int =
		if (days != null && days in BACKUP_REMINDER_PERIODS) days else BACKUP_DEFAULT_REMINDER_DAYS

	fun evaluate(
		now: Long,
		config: BackupReminderConfig,
		hasData: Boolean,
	): BackupReminderUi? {
		if (!hasData) return null
		if (config.periodDays <= 0) return null
		if (config.snoozedUntil > now) return null
		val baseline = if (config.lastExportAt > 0) config.lastExportAt else config.trackingSince
		if (baseline <= 0L || baseline > now) return null
		val elapsed = now - baseline
		val periodMs = config.periodDays.toLong() * BACKUP_DAY_MS
		if (elapsed < periodMs) return null
		return BackupReminderUi(
			daysSince = elapsed / BACKUP_DAY_MS,
			neverBackedUp = config.lastExportAt <= 0L,
		)
	}
}

/**
 * Reads and writes the reminder inputs in the app's key-value store.
 *
 * `SecureStore` is the app's only small preference store; these values are not
 * secrets, and none of them is the backup passphrase.
 */
class BackupReminderStore(private val secure: SecureStore) {

	/** Start the clock on first launch; returns the value it will use. */
	fun ensureTracking(now: Long): Long {
		val stored = secure.get(SecureStore.KEY_BACKUP_TRACKING_SINCE)?.toLongOrNull()
		if (stored != null && stored > 0L) return stored
		secure.put(SecureStore.KEY_BACKUP_TRACKING_SINCE, now.toString())
		return now
	}

	fun lastExportAt(): Long =
		secure.get(SecureStore.KEY_BACKUP_LAST_EXPORT_AT)?.toLongOrNull() ?: 0L

	fun periodDays(): Int = BackupReminderPolicy.normalizePeriod(
		secure.get(SecureStore.KEY_BACKUP_REMINDER_DAYS)?.toIntOrNull(),
	)

	fun setPeriodDays(days: Int) {
		secure.put(SecureStore.KEY_BACKUP_REMINDER_DAYS, BackupReminderPolicy.normalizePeriod(days).toString())
	}

	fun evaluate(now: Long, hasData: Boolean): BackupReminderUi? = BackupReminderPolicy.evaluate(
		now = now,
		config = BackupReminderConfig(
			lastExportAt = lastExportAt(),
			trackingSince = ensureTracking(now),
			periodDays = periodDays(),
			snoozedUntil = secure.get(SecureStore.KEY_BACKUP_SNOOZED_UNTIL)?.toLongOrNull() ?: 0L,
		),
		hasData = hasData,
	)

	/** A successful export resets the clock and clears a pending snooze. */
	fun recordExport(now: Long) {
		secure.put(SecureStore.KEY_BACKUP_LAST_EXPORT_AT, now.toString())
		secure.put(SecureStore.KEY_BACKUP_SNOOZED_UNTIL, "0")
	}

	fun snooze(now: Long) {
		secure.put(
			SecureStore.KEY_BACKUP_SNOOZED_UNTIL,
			(now + BACKUP_SNOOZE_DAYS * BACKUP_DAY_MS).toString(),
		)
	}
}