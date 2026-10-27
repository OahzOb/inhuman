package com.inhuman

import com.inhuman.core.files.WorkspaceSelfCheck

/**
 * What the device proved about its own fences at start-up.
 *
 * Not a setting: the user cannot turn a failed fence check back on, and a
 * successful one is not a permission -- it is the result of asking the device
 * the same question the unit tests ask on a JVM. The value is fixed for the
 * process; a fresh start asks again.
 *
 * The default is **false**, so a screen that composes before the check has run
 * offers no workspace tools rather than assuming the best. `InhumanApp.onCreate`
 * records the answer before the first activity is created.
 */
object AppCapabilities {

	@Volatile
	private var workspaceReport: WorkspaceSelfCheck.Report? = null

	/** Null until the check has run. */
	val workspaceCheck: WorkspaceSelfCheck.Report? get() = workspaceReport

	val workspaceOk: Boolean get() = workspaceReport?.ok == true

	@Volatile
	var sandboxOk: Boolean? = null
		private set

	@Volatile
	var sandboxError: String? = null
		private set

	fun record(report: WorkspaceSelfCheck.Report) {
		workspaceReport = report
	}

	/** Result of the platform JavaScript sandbox probe. */
	fun recordSandbox(ok: Boolean, error: String?) {
		sandboxOk = ok
		sandboxError = if (ok) null else error
	}
}