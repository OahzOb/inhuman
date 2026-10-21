package com.inhuman

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** What one chat turn should show in the notification area. */
data class TurnStatus(
	val conversationId: String,
	val title: String,
	val phase: String,
	val startedAt: Long,
	val streaming: Boolean,
	val preview: String? = null,
	val failed: Boolean = false,
	/** A tool call or SSH consent is waiting; the notification offers Review. */
	val awaitingApproval: Boolean = false,
)

/**
 * Process-wide state for the turn notifications.
 *
 * The view models do not know about Android notifications; they report a
 * [TurnStatus] and MainActivity hands it here. The service reads [snapshot] and
 * renders one notification per active turn, with a summary when several turns
 * run at once.
 */
object TurnNotifications {
	const val CHANNEL_PROGRESS = "turn_progress"
	const val CHANNEL_COMPLETE = "turn_complete"
	const val ACTION_REFRESH = "com.inhuman.action.REFRESH_TURNS"
	const val ACTION_STOP = "com.inhuman.action.STOP_TURN"
	const val ACTION_STOP_ALL = "com.inhuman.action.STOP_ALL_TURNS"
	const val EXTRA_CONVERSATION_ID = "com.inhuman.extra.CONVERSATION_ID"
	const val EXTRA_TITLE = "com.inhuman.extra.TITLE"
	const val SUMMARY_ID = 41

	private val active = LinkedHashMap<String, TurnStatus>()
	private val stops = mutableMapOf<String, () -> Unit>()

	/** Called by the chat view model while a turn is running. */
	fun registerStop(conversationId: String, stop: () -> Unit) {
		synchronized(stops) { stops[conversationId] = stop }
	}

	fun clearStop(conversationId: String) {
		synchronized(stops) { stops.remove(conversationId) }
	}

	/** Called by the service's Stop action. */
	fun stopTurn(conversationId: String) {
		val action = synchronized(stops) { stops[conversationId] }
		action?.invoke()
	}

	fun stopAll() {
		val actions = synchronized(stops) { stops.values.toList() }
		actions.forEach { it() }
	}

	fun snapshot(): List<TurnStatus> = synchronized(active) { active.values.toList() }

	/**
	 * Apply a view-model status and update the foreground service.
	 *
	 * `streaming=false` is only meaningful when a turn was active; the initial
	 * idle state from a newly-created view model must not emit a completion.
	 */
	fun update(context: Context, status: TurnStatus) {
		ensureChannels(context)
		if (status.streaming) {
			synchronized(active) { active[status.conversationId] = status }
			runCatching {
				ContextCompat.startForegroundService(
					context,
					Intent(context, TurnForegroundService::class.java).setAction(ACTION_REFRESH),
				)
			}
			return
		}

		val previous = synchronized(active) { active.remove(status.conversationId) }
		if (previous == null) return
		if (snapshot().isNotEmpty()) {
			runCatching {
				ContextCompat.startForegroundService(
					context,
					Intent(context, TurnForegroundService::class.java).setAction(ACTION_REFRESH),
				)
			}
		} else {
			context.stopService(Intent(context, TurnForegroundService::class.java))
		}
		if (!isAppForeground(context)) postCompletion(context, status)
	}

	private fun isAppForeground(context: Context): Boolean {
		val manager = context.getSystemService(ActivityManager::class.java) ?: return true
		val process = manager.runningAppProcesses
			?.firstOrNull { it.pid == Process.myPid() }
			?: return true
		return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
	}

	private fun postCompletion(context: Context, status: TurnStatus) {
		val manager = context.getSystemService(NotificationManager::class.java) ?: return
		val open = openIntent(context, status.conversationId)
		val text = status.preview?.let(::plainPreview)?.takeIf { it.isNotBlank() }
			?: if (status.failed) "The reply could not be completed." else "The reply is ready."
		val public = NotificationCompat.Builder(context, CHANNEL_COMPLETE)
			.setSmallIcon(R.drawable.ic_stat_turn)
			.setContentTitle("Reply ready")
			.setContentText("Open inhuman to read it.")
			.build()
		val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETE)
			.setSmallIcon(R.drawable.ic_stat_turn)
			.setContentTitle(if (status.failed) "Reply failed" else status.title)
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text))
			.setContentIntent(open)
			.setAutoCancel(true)
			.setOnlyAlertOnce(true)
			.setCategory(NotificationCompat.CATEGORY_MESSAGE)
			.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
			.setPublicVersion(public)
			.build()
		manager.notify(completionId(status.conversationId), notification)
	}

	/** Keep completion text human; Markdown punctuation is noise in a notification. */
	private fun plainPreview(text: String): String = text
		.replace(Regex("```[\\s\\S]*?```"), "[code]")
		.replace(Regex("`([^`]*)`"), "$1")
		.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
		.replace(Regex("(?m)^#+\\s+"), "")
		.replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
		.trim()

	fun ensureChannels(context: Context) {
		val manager = context.getSystemService(NotificationManager::class.java) ?: return
		manager.createNotificationChannel(
			NotificationChannel(
				CHANNEL_PROGRESS,
				"Assistant replies",
				NotificationManager.IMPORTANCE_LOW,
			).apply {
				description = "Shown while a reply is being generated."
				setShowBadge(false)
			},
		)
		manager.createNotificationChannel(
			NotificationChannel(
				CHANNEL_COMPLETE,
				"Finished replies",
				NotificationManager.IMPORTANCE_DEFAULT,
			).apply {
				description = "Shown when a reply finishes while the app is in the background."
				setShowBadge(true)
			},
		)
	}

	fun openIntent(context: Context, conversationId: String): PendingIntent {
		val intent = Intent(context, MainActivity::class.java)
			.putExtra(EXTRA_CONVERSATION_ID, conversationId)
		return PendingIntent.getActivity(
			context,
			conversationId.hashCode(),
			intent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
	}

	fun stopIntent(context: Context, conversationId: String): PendingIntent {
		val intent = Intent(context, TurnForegroundService::class.java)
			.setAction(ACTION_STOP)
			.putExtra(EXTRA_CONVERSATION_ID, conversationId)
		return PendingIntent.getService(
			context,
			conversationId.hashCode() xor 0x5f5f,
			intent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
	}

	fun stopAllIntent(context: Context): PendingIntent {
		val intent = Intent(context, TurnForegroundService::class.java)
			.setAction(ACTION_STOP_ALL)
		return PendingIntent.getService(
			context,
			0x5f5f,
			intent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
	}

	fun childId(conversationId: String): Int {
		val id = 1000 + (conversationId.hashCode() and 0x7fffffff) % 100_000
		return if (id == SUMMARY_ID) id + 1 else id
	}

	fun completionId(conversationId: String): Int =
		500_000 + (conversationId.hashCode() and 0x7fffffff) % 100_000
}
