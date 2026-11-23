package com.inhuman.ui.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.inhuman.MainActivity
import com.inhuman.R

/**
 * The system-facing side of reply read-aloud: a media session plus a media
 * notification, so pause/resume/stop are available on the lock screen and in
 * the notification shade while the app is in the background.
 *
 * ## Why it is scoped to the screen
 *
 * The player is owned by the chat composition, not by a service. The session is
 * created and released with the same composition: leaving the chat stops the
 * audio and removes the notification, instead of leaving an orphaned session
 * the user cannot reach. While the chat is merely backgrounded (home, lock
 * screen) the composition survives, which is the case this control exists for.
 *
 * The notification actions cannot call the composition directly, so they are
 * broadcasts to [SpeechMediaReceiver], which forwards to the one active session
 * through [SpeechPlaybackBridge]. The system's own lock-screen controls use the
 * [MediaSession] callbacks below and do not go through the receiver.
 */
class SpeechMediaSession(private val context: Context) {

	private val notifications = context.getSystemService(NotificationManager::class.java)
	private val audio = context.getSystemService(AudioManager::class.java)

	private var session: MediaSession? = null
	private var focus: AudioFocusRequest? = null
	private var playing = false
	private var voice: String = ""
	private var durationMs: Int = 0
	private var positionMs: Int = 0
	private var caption: String? = null

	/** Wired to the screen's player; all callbacks run on the caller's main thread. */
	var onPlay: (() -> Unit)? = null
	var onPause: (() -> Unit)? = null
	var onStop: (() -> Unit)? = null
	var onSeek: ((Long) -> Unit)? = null

	/** Start (or retarget) the session and post the playing notification. */
	fun start(
		voice: String,
		durationMs: Int = 0,
		positionMs: Int = 0,
		caption: String? = null,
	) {
		val mediaSession = ensureSession()
		this.voice = voice
		this.durationMs = durationMs.coerceAtLeast(0)
		this.positionMs = positionMs.coerceAtLeast(0)
		this.caption = caption
		SpeechPlaybackBridge.active = this
		mediaSession.isActive = true
		requestFocus()
		update(playing = true, positionMs = this.positionMs)
		post(playing = true)
	}

	/** Update the paused/playing state and its notification. */
	fun setPaused(paused: Boolean, positionMs: Int = this.positionMs, caption: String? = this.caption) {
		this.positionMs = positionMs.coerceAtLeast(0)
		this.caption = caption
		update(playing = !paused, positionMs = this.positionMs)
		post(playing = !paused)
	}

	/**
	 * Follow playback. Called about once a second while audio is running; the
	 * position feeds the lock-screen seek bar and the caption feeds the
	 * notification's real-time subtitle line.
	 */
	fun progress(positionMs: Int, durationMs: Int, caption: String? = this.caption) {
		this.positionMs = positionMs.coerceAtLeast(0)
		if (durationMs > 0) this.durationMs = durationMs
		this.caption = caption
		update(playing = playing, positionMs = this.positionMs)
		post(playing = playing)
	}

	fun stop() {
		session?.isActive = false
		notifications?.cancel(NOTIFICATION_ID)
		abandonFocus()
		SpeechPlaybackBridge.active = null
		playing = false
		caption = null
	}

	fun release() {
		stop()
		session?.release()
		session = null
	}

	/** Notification action: play (used when the notification is paused). */
	fun requestPlay() {
		onPlay?.invoke()
	}

	/** Notification action: pause. */
	fun requestPause() {
		onPause?.invoke()
	}

	/** Notification action: stop. */
	fun requestStop() {
		onStop?.invoke()
	}

	private fun ensureSession(): MediaSession {
		session?.let { return it }
		val created = MediaSession(context, TAG)
		created.setCallback(object : MediaSession.Callback() {
			override fun onPlay() {
				onPlay?.invoke()
			}

			override fun onPause() {
				onPause?.invoke()
			}

			override fun onStop() {
				onStop?.invoke()
			}

			override fun onSeekTo(pos: Long) {
				onSeek?.invoke(pos)
			}
		})
		created.setSessionActivity(contentIntent())
		session = created
		ensureChannel()
		return created
	}

	private fun update(playing: Boolean, positionMs: Int) {
		this.playing = playing
		this.positionMs = positionMs.coerceAtLeast(0)
		val mediaSession = session ?: return
		val metadata = MediaMetadata.Builder()
			.putString(
				MediaMetadata.METADATA_KEY_TITLE,
				caption?.takeIf { it.isNotBlank() }
					?: context.getString(R.string.chat_speech_channel),
			)
			.putString(MediaMetadata.METADATA_KEY_ARTIST, voice)
		// The lock screen needs the duration to draw a seek bar; without it the
		// position has no scale to move along.
		if (durationMs > 0) {
			metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.toLong())
		}
		mediaSession.setMetadata(metadata.build())
		mediaSession.setPlaybackState(
			PlaybackState.Builder()
				.setActions(
					PlaybackState.ACTION_PLAY or
						PlaybackState.ACTION_PAUSE or
						PlaybackState.ACTION_STOP or
						PlaybackState.ACTION_PLAY_PAUSE,
				)
				.setState(
					if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
					this.positionMs.toLong(),
					if (playing) 1f else 0f,
				)
				.build(),
		)
	}

	/** The notification's second line: the caption being read, or the fallback. */
	private fun notificationText(playing: Boolean): String {
		caption?.takeIf { it.isNotBlank() }?.let { return it }
		val state = context.getString(
			if (playing) R.string.chat_speaking else R.string.chat_speech_paused,
		)
		if (durationMs <= 0) return state
		return "$state · ${SpeechTime.format(positionMs)} / ${SpeechTime.format(durationMs)}"
	}

	/** Voice and position, kept out of the caption line. */
	private fun notificationSubText(): String {
		val parts = mutableListOf<String>()
		if (voice.isNotBlank()) parts += voice
		if (durationMs > 0) {
			parts += "${SpeechTime.format(positionMs)} / ${SpeechTime.format(durationMs)}"
		}
		return parts.joinToString(" · ")
	}

	private fun post(playing: Boolean) {
		val mediaSession = session ?: return
		val manager = notifications ?: return
		if (!manager.areNotificationsEnabled()) return

		val builder = Notification.Builder(context, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_stat_turn)
			.setContentTitle(
				context.getString(
					if (playing) R.string.chat_speaking else R.string.chat_speech_paused,
				),
			)
			.setContentText(notificationText(playing))
			.setSubText(notificationSubText().ifBlank { null })
			.setContentIntent(contentIntent())
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setVisibility(Notification.VISIBILITY_PUBLIC)
			.setStyle(
				Notification.MediaStyle()
					.setMediaSession(mediaSession.sessionToken)
					.setShowActionsInCompactView(0, 1),
			)
		if (durationMs > 0) {
			builder.setProgress(durationMs, positionMs, false)
		}
		builder.addAction(
			action(
				icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
				title = context.getString(if (playing) R.string.chat_pause else R.string.chat_resume),
				action = if (playing) ACTION_PAUSE else ACTION_PLAY,
				requestCode = 1,
			),
		)
		builder.addAction(
			action(
				icon = android.R.drawable.ic_menu_close_clear_cancel,
				title = context.getString(R.string.chat_stop),
				action = ACTION_STOP,
				requestCode = 2,
			),
		)
		runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
	}

	private fun action(icon: Int, title: String, action: String, requestCode: Int): Notification.Action =
		Notification.Action.Builder(
			Icon.createWithResource(context, icon),
			title,
			PendingIntent.getBroadcast(
				context,
				requestCode,
				Intent(context, SpeechMediaReceiver::class.java).setAction(action),
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
			),
		).build()

	private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
		context,
		0,
		Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
		PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
	)

	private fun ensureChannel() {
		val manager = notifications ?: return
		if (manager.getNotificationChannel(CHANNEL_ID) != null) return
		manager.createNotificationChannel(
			NotificationChannel(
				CHANNEL_ID,
				context.getString(R.string.chat_speech_channel),
				NotificationManager.IMPORTANCE_LOW,
			).apply {
				description = context.getString(R.string.chat_speech_channel_body)
				setShowBadge(false)
			},
		)
	}

	private fun requestFocus() {
		if (focus != null) return
		val attributes = AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_MEDIA)
			.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
			.build()
		val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
			.setAudioAttributes(attributes)
			.setOnAudioFocusChangeListener { change ->
				if (change == AudioManager.AUDIOFOCUS_LOSS ||
					change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
				) {
					onPause?.invoke()
				}
			}
			.build()
		runCatching { audio?.requestAudioFocus(request) }
		focus = request
	}

	private fun abandonFocus() {
		focus?.let { request -> runCatching { audio?.abandonAudioFocusRequest(request) } }
		focus = null
	}

	companion object {
		private const val TAG = "inhuman-speech"
		private const val CHANNEL_ID = "speech_playback"
		private const val NOTIFICATION_ID = 0x5EEC

		internal const val ACTION_PLAY = "com.inhuman.action.SPEECH_PLAY"
		internal const val ACTION_PAUSE = "com.inhuman.action.SPEECH_PAUSE"
		internal const val ACTION_STOP = "com.inhuman.action.SPEECH_STOP"
	}
}

/** The one session currently allowed to receive notification actions. */
internal object SpeechPlaybackBridge {
	@Volatile
	var active: SpeechMediaSession? = null
}

/**
 * Notification-action entry point.
 *
 * Declared in the manifest without an intent filter and exported=false: the
 * PendingIntents name this class explicitly, so no other app can reach it.
 */
class SpeechMediaReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val session = SpeechPlaybackBridge.active ?: return
		when (intent.action) {
			SpeechMediaSession.ACTION_PLAY -> session.requestPlay()
			SpeechMediaSession.ACTION_PAUSE -> session.requestPause()
			SpeechMediaSession.ACTION_STOP -> session.requestStop()
		}
	}
}
