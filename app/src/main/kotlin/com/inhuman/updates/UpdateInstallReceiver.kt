package com.inhuman.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.inhuman.R

/**
 * Receives the system's answer to a [PackageInstaller] commit.
 *
 * `STATUS_PENDING_USER_ACTION` is the normal case: the system hands back the
 * confirmation Intent, which must be started for the install to proceed. The
 * success/failure branches only report; the app's own status line cannot
 * observe them because the process may be in the background while the system
 * installer is in front.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != ACTION_INSTALL_STATUS) return
		val status = intent.getIntExtra(
			PackageInstaller.EXTRA_STATUS,
			PackageInstaller.STATUS_FAILURE,
		)
		when (status) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				val confirmation = intent.getParcelableExtra(
					Intent.EXTRA_INTENT,
					Intent::class.java,
				)
				if (confirmation != null) {
					confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					runCatching { context.startActivity(confirmation) }
				}
			}
			PackageInstaller.STATUS_SUCCESS -> {
				Toast.makeText(
					context,
					context.getString(R.string.settings_update_installed),
					Toast.LENGTH_SHORT,
				).show()
			}
			else -> {
				val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
					?: "install failed (status $status)"
				Toast.makeText(
					context,
					context.getString(R.string.settings_update_failed, message),
					Toast.LENGTH_LONG,
				).show()
			}
		}
	}

	companion object {
		const val ACTION_INSTALL_STATUS = "com.inhuman.updates.INSTALL_STATUS"
	}
}
