package com.inhuman

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the process alive while a reply is streaming.
 *
 * A turn can spend minutes in a provider call or a tool round. Without a
 * foreground service, Android is free to kill the process when the screen turns
 * off, losing the reply after the user was billed for it. The notification is
 * deliberately quiet: it reports that the assistant is working, not a progress
 * bar the app cannot measure.
 */
class TurnForegroundService : Service() {

	override fun onCreate() {
		super.onCreate()
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(
				CHANNEL_ID,
				"Assistant replies",
				NotificationManager.IMPORTANCE_LOW,
			).apply {
				description = "Shown while a reply is being generated."
				setShowBadge(false)
			},
		)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val open = PendingIntent.getActivity(
			this,
			0,
			Intent(this, MainActivity::class.java),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_stat_turn)
			.setContentTitle("inhuman")
			.setContentText("The assistant is still working on this reply.")
			.setContentIntent(open)
			.setOngoing(true)
			.setSilent(true)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.build()
		ServiceCompat.startForeground(
			this,
			NOTIFICATION_ID,
			notification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
		return START_NOT_STICKY
	}

	override fun onBind(intent: Intent?): IBinder? = null

	companion object {
		const val CHANNEL_ID = "turn_progress"
		const val NOTIFICATION_ID = 41
	}
}
