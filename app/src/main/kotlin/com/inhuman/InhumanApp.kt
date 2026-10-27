package com.inhuman

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.BackupRepository
import com.inhuman.core.files.WorkspaceSelfCheck
import com.inhuman.diagnostics.AppDiagnostics
import com.inhuman.sandbox.SandboxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

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

	private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	override fun onCreate() {
		super.onCreate()
		ensureDefaultLanguage()
		AppDiagnostics.install(this)
		runWorkspaceSelfCheck()
		runSandboxSelfCheck()
		// Before any repository opens Room. A staged backup import replaces the
		// database file, which is only safe while no connection is open.
		BackupRepository.applyPendingRestore(this)
	}

	/**
	 * Ask the platform sandbox the capability question before a tool is offered.
	 *
	 * The probe starts a JavaScript isolate with the 16 MiB ceiling and checks
	 * that `fetch`, `XMLHttpRequest`, `WebSocket`, `require` and `process` are
	 * absent. A failure is recorded in [AppCapabilities] and the tool is absent;
	 * Settings -> Diagnostics shows the reason. Running it here means a turn
	 * never has to turn "we could not ask" into "the sandbox is fine".
	 */
	private fun runSandboxSelfCheck() {
		startupScope.launch {
			val ok = SandboxRuntime.available(this@InhumanApp)
			if (ok) {
				Diag.i("capability", "javascript sandbox probe passed")
			} else {
				Diag.e(
					"capability",
					"javascript sandbox unavailable: ${SandboxRuntime.failureReason ?: "-"}",
				)
			}
		}
	}

	/**
	 * Ask the device whether the workspace fence still holds.
	 *
	 * A failure is not a warning: [AppCapabilities.workspaceOk] becomes false,
	 * and every tool that needs the workspace is absent from the request and
	 * from the pickers, with a banner in the chat explaining why. A check that
	 * could not run at all is a failure too -- "we could not ask" must not read
	 * as "the fence is fine".
	 */
	private fun runWorkspaceSelfCheck() {
		val report = try {
			WorkspaceSelfCheck.run(File(cacheDir, "self-check"))
		} catch (failure: Exception) {
			WorkspaceSelfCheck.Report.failed(failure.message ?: failure.javaClass.simpleName)
		}
		AppCapabilities.record(report)
		if (report.ok) {
			Diag.i("capability", "workspace self-check passed checks=${report.checks.size}")
		} else {
			Diag.e(
				"capability",
				"workspace self-check failed error=${report.error ?: "-"} " +
					"failed=${report.failed.joinToString(",") { it.id.name }}",
			)
			report.failed.forEach { Diag.e("capability", "check=${it.id.name} detail=${it.detail}") }
		}
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
