package com.inhuman

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import com.inhuman.core.data.BackupRepository
import com.inhuman.diagnostics.AppDiagnostics

/**
 * Application entry point.
 *
 * There is still no DI container: the graph is five objects constructed in
 * `InhumanRoot`, and a container would be more configuration than the thing it
 * configures. Objects that must be singular for correctness rather than for
 * tidiness -- `SecureStore`, `ConversationRepository`, `AgentRepository` -- are
 * each built once per process and passed down.
 *
 * Diagnostics is the one thing that genuinely belongs here. It must be
 * recording before the first view model exists, because the failures worth
 * reading about are exactly the ones that happen early: a database that will
 * not open, a migration that throws, a process that dies before the first
 * frame. `Application.onCreate` is the earliest point the app controls.
 */
class InhumanApp : Application() {

	override fun onCreate() {
		super.onCreate()
		ensureDefaultLanguage()
		AppDiagnostics.install(this)
		// Before any repository opens Room. A staged backup import replaces the
		// database file, which is only safe while no connection is open.
		BackupRepository.applyPendingRestore(this)
	}

	/**
	 * Chinese is the product's default language.
	 *
	 * The system owns the per-app locale: an empty list means nobody has chosen
	 * one yet, so this is the first launch (or the user explicitly returned to
	 * "System default"). Set Chinese once here; a later English choice is stored
	 * by the system and is never overwritten, because the list is then non-empty.
	 *
	 * This runs before the first activity so the first frame is already Chinese;
	 * setting it after a screen has composed would show an English frame first.
	 */
	private fun ensureDefaultLanguage() {
		val locales = getSystemService(LocaleManager::class.java) ?: return
		if (locales.applicationLocales.isEmpty) {
			locales.applicationLocales = LocaleList.forLanguageTags("zh-CN")
		}
	}
}
