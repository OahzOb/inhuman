package com.inhuman

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the process alive and renders one notification per running turn.
 *
 * With one turn its own notification is the foreground notification. With
 * several turns a summary notification is foreground and each turn gets a child
 * notification with its own Open/Stop actions. Turn state lives in
 * [TurnNotifications]; this service only renders it.
 */
class TurnForegroundService : Service() {

	private val posted = mutableSetOf<Int>()

	override fun onCreate() {
		super.onCreate()
		TurnNotifications.ensureChannels(this)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			TurnNotifications.ACTION_STOP -> {
				intent.getStringExtra(TurnNotifications.EXTRA_CONVERSATION_ID)?.let {
					TurnNotifications.stopTurn(it)
				}
			}

			TurnNotifications.ACTION_STOP_ALL -> TurnNotifications.stopAll()
		}
		refresh()
		return START_NOT_STICKY
	}

	private fun refresh() {
		val turns = TurnNotifications.snapshot()
		val manager = getSystemService(NotificationManager::class.java)
		if (turns.isEmpty()) {
			ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
			posted.forEach { manager.cancel(it) }
			posted.clear()
			stopSelf()
			return
		}

		val previous = posted.toSet()
		posted.clear()
		if (turns.size == 1) {
			val turn = turns.single()
			val id = TurnNotifications.childId(turn.conversationId)
			ServiceCompat.startForeground(
				this,
				id,
				childNotification(turn, grouped = false),
				ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
			)
			posted += id
		} else {
			ServiceCompat.startForeground(
				this,
				TurnNotifications.SUMMARY_ID,
				summaryNotification(turns),
				ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
			)
			posted += TurnNotifications.SUMMARY_ID
			turns.forEach { turn ->
				val id = TurnNotifications.childId(turn.conversationId)
				manager.notify(id, childNotification(turn, grouped = true))
				posted += id
			}
		}
		(previous - posted).forEach { manager.cancel(it) }
	}

	private fun childNotification(status: TurnStatus, grouped: Boolean): Notification {
		val public = NotificationCompat.Builder(this, TurnNotifications.CHANNEL_PROGRESS)
			.setSmallIcon(R.drawable.ic_stat_turn)
			.setContentTitle("inhuman")
			.setContentText(getString(R.string.notif_reply_in_progress))
			.build()
		return NotificationCompat.Builder(this, TurnNotifications.CHANNEL_PROGRESS)
			.setSmallIcon(R.drawable.ic_stat_turn)
			.setContentTitle(status.title.ifBlank { getString(R.string.notif_conversation) })
			.setContentText(status.phase)
			.setSubText("inhuman")
			.setWhen(status.startedAt)
			.setUsesChronometer(true)
			.setShowWhen(true)
			.setContentIntent(TurnNotifications.openIntent(this, status.conversationId))
			.addAction(0, getString(R.string.notif_stop), TurnNotifications.stopIntent(this, status.conversationId))
			.apply {
				if (status.awaitingApproval) {
					addAction(
						0,
						getString(R.string.notif_review),
						TurnNotifications.openIntent(this@TurnForegroundService, status.conversationId),
					)
				}
			}
			.setOngoing(true)
			.setSilent(true)
			.setOnlyAlertOnce(true)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
			.setPublicVersion(public)
			.apply { if (grouped) setGroup(GROUP) }
			.build()
	}

	private fun summaryNotification(turns: List<TurnStatus>): Notification {
		val latest = turns.last()
		val public = NotificationCompat.Builder(this, TurnNotifications.CHANNEL_PROGRESS)
			.setSmallIcon(R.drawable.ic_stat_turn)
			.setContentTitle("inhuman")
			.setContentText(getString(R.string.notif_replies_in_progress, turns.size))
			.build()
		val inbox = NotificationCompat.InboxStyle()
			.setBigContentTitle(getString(R.string.notif_replies_running, turns.size))
		turns.take(5).forEach { turn ->
			inbox.addLine(
				"${turn.title.ifBlank { getString(R.string.notif_conversation) }} — ${turn.phase}",
			)
		}
		return NotificationCompat.Builder(this, TurnNotifications.CHANNEL_PROGRESS)
			.setSmallIcon(R.drawable.ic_stat_turn)
			.setContentTitle(getString(R.string.notif_replies_running, turns.size))
			.setContentText(
				"${latest.title.ifBlank { getString(R.string.notif_conversation) }} · ${latest.phase}",
			)
			.setStyle(inbox)
			.setSubText("inhuman")
			.setWhen(turns.minOf { it.startedAt })
			.setUsesChronometer(true)
			.setShowWhen(true)
			.setContentIntent(TurnNotifications.openIntent(this, latest.conversationId))
			.addAction(0, getString(R.string.notif_open), TurnNotifications.openIntent(this, latest.conversationId))
			.addAction(0, getString(R.string.notif_stop_all), TurnNotifications.stopAllIntent(this))
			.apply {
				val review = turns.filter { it.awaitingApproval }.maxByOrNull { it.startedAt }
				if (review != null) {
					addAction(
						0,
						getString(R.string.notif_review),
						TurnNotifications.openIntent(this@TurnForegroundService, review.conversationId),
					)
				}
			}
			.setOngoing(true)
			.setSilent(true)
			.setOnlyAlertOnce(true)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.setGroup(GROUP)
			.setGroupSummary(true)
			.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
			.setPublicVersion(public)
			.build()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	companion object {
		private const val GROUP = "inhuman.turns"
	}
}
