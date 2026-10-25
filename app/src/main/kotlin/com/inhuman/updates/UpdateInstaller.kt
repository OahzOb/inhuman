package com.inhuman.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

/**
 * Checks and installs a downloaded APK.
 *
 * Two checks are load-bearing:
 *
 * 1. **The package name must match.** A manifest that points at another app's
 *    APK must not make this app open the installer for it.
 * 2. **The signing certificate set must match the installed app.** Android
 *    would refuse a different key anyway, but refusing here gives a specific
 *    message instead of a generic install failure, and it means a compromised
 *    update host cannot even get as far as the system prompt.
 *
 * The APK's version code is compared with the manifest too, so a manifest
 * cannot advertise version 9 and hand over an older signed build.
 */
object UpdateInstaller {

	fun canInstall(context: Context): Boolean =
		context.packageManager.canRequestPackageInstalls()

	/** Open the per-app "install unknown apps" screen. */
	fun openInstallPermission(context: Context) {
		val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
			.setData(Uri.parse("package:${context.packageName}"))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		runCatching { context.startActivity(intent) }
	}

	/** Null when [apk] is safe to hand to the installer; otherwise a reason. */
	fun verify(context: Context, apk: File, expectedVersionCode: Long): String? {
		val pm = context.packageManager
		val archive = pm.getPackageArchiveInfo(
			apk.absolutePath,
			PackageManager.GET_SIGNING_CERTIFICATES,
		) ?: return "The downloaded APK cannot be read."
		if (archive.packageName != context.packageName) {
			return "The downloaded APK belongs to a different app."
		}
		if (archive.longVersionCode != expectedVersionCode) {
			return "The downloaded APK does not match the manifest version."
		}
		val archiveSigners = signerHashes(archive)
		val installed = runCatching {
			pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
		}.getOrNull() ?: return "The installed package cannot be read."
		val installedSigners = signerHashes(installed)
		if (archiveSigners.isEmpty() || installedSigners.isEmpty()) {
			return "The APK has no signing certificate."
		}
		if (archiveSigners != installedSigners) {
			return "The downloaded APK is signed with a different key."
		}
		return null
	}

	/**
	 * Hand the APK to the system package installer.
	 *
	 * A [PackageInstaller] session is used instead of an `ACTION_VIEW` on a
	 * FileProvider URI: the latter reached the installer on Android 15 but the
	 * installer reported "There was a problem parsing the package" for a
	 * correctly signed APK. The session API hands the bytes to the same
	 * component the Play Store uses and still shows the system confirmation.
	 */
	fun install(context: Context, apk: File): Boolean {
		return runCatching {
			val installer = context.packageManager.packageInstaller
			val params = PackageInstaller.SessionParams(
				PackageInstaller.SessionParams.MODE_FULL_INSTALL,
			).apply {
				setAppPackageName(context.packageName)
			}
			val sessionId = installer.createSession(params)
			installer.openSession(sessionId).use { session ->
				session.openWrite("inhuman-update.apk", 0L, apk.length()).use { output ->
					apk.inputStream().use { input -> input.copyTo(output) }
					session.fsync(output)
				}
				val status = Intent(context, UpdateInstallReceiver::class.java)
					.setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
				val pending = PendingIntent.getBroadcast(
					context,
					sessionId,
					status,
					PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
				)
				session.commit(pending.intentSender)
			}
			true
		}.getOrDefault(false)
	}

	private fun signerHashes(info: PackageInfo): Set<String> {
		val signing = info.signingInfo ?: return emptySet()
		return signing.apkContentsSigners
			.map { sha256(it.toByteArray()) }
			.toSet()
	}

	fun sha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val read = input.read(buffer)
				if (read <= 0) break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
	}

	private fun sha256(bytes: ByteArray): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
		return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
	}
}
