package com.inhuman

import android.app.Application
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
		AppDiagnostics.install(this)
	}
}
