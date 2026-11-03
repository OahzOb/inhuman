package com.inhuman.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.common.diag.RollingLog
import java.io.File
import java.util.Locale

/**
 * Wires [Diag] to a file, to logcat, and to the crash handler.
 *
 * ## Why the file is the primary record and logcat the mirror
 *
 * logcat is a shared ring buffer: it is not readable by the app, its size is
 * the vendor's choice, and the events that explain a bug are usually gone by
 * the time anyone looks. The file is owned by the app, survives the process,
 * and can be pulled with `run-as` or attached from Settings. The logcat mirror
 * exists because during development `adb logcat -s inhuman` is the immediate
 * view, and it costs one line to keep the two in step.
 *
 * ## Why this lives in `:app` rather than `:core:common`
 *
 * The facade and the file format are platform-free and unit-tested there; only
 * this half needs Android. Keeping `android.util.Log`, the Application and the
 * crash handler out of the shared module means `Diag` can be tested on a plain
 * JVM.
 */
object AppDiagnostics {

	/** logcat's tag; `adb logcat -s inhuman` shows the same lines as the file. */
	private const val LOGCAT_TAG = "inhuman"

	private const val MAX_BYTES = 1_000_000L

	/** Three archives plus the live file: at most 4 MB, ~4 months of ordinary use. */
	private const val ARCHIVES = 3

	/**
	 * A manual recording is a second, explicitly bounded copy of the same lines.
	 *
	 * The always-on recorder exists for the bug that has already happened; the
	 * recording exists for the bug that can be reproduced, so the upload can be
	 * one segment instead of months of history. It is still made of the same
	 * `Diag.format` output and the same redaction -- this adds a destination,
	 * not a second kind of record.
	 */
	private const val MAX_RECORDING_BYTES = 2_000_000L

	private const val RECORDING_NAME = "recording.log"

	private var rolling: RollingLog? = null

	/** Null until [install]; a recording is only meaningful once the file is set. */
	private var recordingDirectory: File? = null

	private val recordingLock = Any()

	@Volatile
	private var recordingFile: File? = null

	/** Bytes already written to the active recording, for the cap. */
	private var recordingWritten = 0L

	private var recordingTruncated = false

	@Volatile
	private var installed = false

	/**
	 * Install once, from `Application.onCreate`.
	 *
	 * Idempotent because tests and process recreation can call it more than
	 * once, and a second sink would double every line.
	 */
	fun install(context: Context) {
		if (installed) return
		installed = true

		val app = context.applicationContext as Application
		val log = RollingLog(
			directory = File(app.filesDir, "diagnostics"),
			maxBytes = MAX_BYTES,
			archives = ARCHIVES,
		)
		log.start()
		rolling = log
		recordingDirectory = File(app.filesDir, "diagnostics/recordings")

		Diag.install { level, tag, message, error ->
			val line = Diag.format(level, tag, message, error)
			// File first: if logcat throws (it should not), the durable copy is
			// already written. The order is the whole point of having both.
			runCatching { log.append(line) }
			appendToRecording(line)
			Log.println(priority(level), LOGCAT_TAG, line)
		}

		Diag.i(
			"app",
			"start ${version(app)} sdk=${Build.VERSION.SDK_INT} " +
				"device=${Build.MANUFACTURER}/${Build.MODEL} " +
				"abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} " +
				"locale=${Locale.getDefault().toLanguageTag()} pid=${Process.myPid()}",
		)
		installCrashHandler()
		installLifecycle(app)
	}

	/** The live file. Null before [install], which is a test or a preview. */
	fun currentFile(): File? = rolling?.current

	fun sizeBytes(): Long = rolling?.sizeBytes() ?: 0L

	/** Everything retained, oldest first. For sharing and for tests. */
	fun readText(): String = rolling?.readText().orEmpty()

	fun clear() {
		// Logged before deleting, so the line is deleted with the rest. Logging
		// after would leave a file that is never empty, and "Clear" would look
		// like it had failed.
		Diag.i("diag", "log cleared by user")
		rolling?.clear()
	}

	/**
	 * Start a manual recording, replacing the last one.
	 *
	 * The start and stop lines are ordinary `Diag` lines: they are written to
	 * the app log as well, and they are what delimits the uploaded segment.
	 * A recording that outlives the process is left as a stopped recording after
	 * restart (the file is there, [recordingActive] is false) so it can still be
	 * uploaded -- losing it to a crash would lose the evidence.
	 */
	fun startRecording() {
		val directory = recordingDirectory ?: return
		val file = File(directory, RECORDING_NAME)
		val started = synchronized(recordingLock) {
			runCatching {
				directory.mkdirs()
				file.writeText("")
				recordingFile = file
				recordingWritten = 0L
				recordingTruncated = false
			}.isSuccess
		}
		if (started) Diag.i("diag", "recording start file=$RECORDING_NAME")
	}

	/** Close the active recording. The file stays until deleted or replaced. */
	fun stopRecording() {
		if (recordingFile == null) return
		Diag.i("diag", "recording stop bytes=${recordingBytes()}")
		synchronized(recordingLock) { recordingFile = null }
	}

	fun recordingActive(): Boolean = recordingFile != null

	fun recordingBytes(): Long = displayRecordingFile()?.length() ?: 0L

	fun readRecordingText(): String =
		displayRecordingFile()?.let { file -> runCatching { file.readText() }.getOrNull() }.orEmpty()

	fun deleteRecording() {
		val file = displayRecordingFile()
		synchronized(recordingLock) { recordingFile = null }
		runCatching { file?.delete() }
	}

	/**
	 * Append the already-formatted line to the active recording.
	 *
	 * A cap, not rotation: a manual recording is a reproduction, not a second
	 * flight recorder. When it fills, one marker is written and the recording
	 * stops growing; the user still has the beginning of the reproduction.
	 */
	private fun appendToRecording(line: String) {
		synchronized(recordingLock) {
			val file = recordingFile ?: return
			if (recordingTruncated) return
			val next = recordingWritten + line.length + 1
			if (next > MAX_RECORDING_BYTES) {
				recordingTruncated = true
				runCatching {
					file.appendText("[recording truncated at $MAX_RECORDING_BYTES bytes]\n")
				}
				return
			}
			runCatching { file.appendText(line + "\n") }
			recordingWritten = next
		}
	}

	private fun displayRecordingFile(): File? =
		recordingDirectory?.let { File(it, RECORDING_NAME) }?.takeIf { it.isFile }

	private fun priority(level: Diag.Level): Int = when (level) {
		Diag.Level.Debug -> Log.DEBUG
		Diag.Level.Info -> Log.INFO
		Diag.Level.Warn -> Log.WARN
		Diag.Level.Error -> Log.ERROR
	}

	private fun version(context: Context): String = try {
		val info = context.packageManager.getPackageInfo(context.packageName, 0)
		"version=${info.versionName} (${info.longVersionCode})"
	} catch (_: Exception) {
		"version=?"
	}

	/**
	 * Write the stack trace before the process dies.
	 *
	 * The previous handler is called afterwards rather than replaced: on
	 * Android it is the one that actually terminates the process and reports
	 * the crash to the system. Swallowing that to "handle" the crash ourselves
	 * would leave a broken process running.
	 */
	private fun installCrashHandler() {
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			try {
				Diag.e("crash", "uncaught thread=${thread.name} pid=${Process.myPid()}", error)
			} catch (_: Throwable) {
				// If the logger itself is what failed, still let the process die.
			}
			if (previous != null) {
				previous.uncaughtException(thread, error)
			} else {
				Process.killProcess(Process.myPid())
			}
		}
	}

	/**
	 * Record activity transitions.
	 *
	 * One line per transition is enough to reconstruct "where was the user when
	 * this happened", which no other event answers -- a crash in a background
	 * view model looks identical to one on screen without it. `restored` marks
	 * a rotation or a return from death, the two cases where UI state is most
	 * likely to disagree with the view model.
	 */
	private fun installLifecycle(app: Application) {
		app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
				Diag.d(
					"life",
					"create ${activity.javaClass.simpleName} " +
						"state=${if (savedInstanceState == null) "fresh" else "restored"}",
				)
			}

			override fun onActivityStarted(activity: Activity) {
				Diag.d("life", "start ${activity.javaClass.simpleName}")
			}

			override fun onActivityResumed(activity: Activity) = Unit

			override fun onActivityPaused(activity: Activity) = Unit

			override fun onActivityStopped(activity: Activity) {
				Diag.d("life", "stop ${activity.javaClass.simpleName}")
			}

			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

			override fun onActivityDestroyed(activity: Activity) {
				Diag.d("life", "destroy ${activity.javaClass.simpleName}")
			}
		})
	}
}
