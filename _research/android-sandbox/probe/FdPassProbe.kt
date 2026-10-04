package com.inhuman.probe

import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * The one question the sepolicy sources could not answer.
 *
 * Passing a `ParcelFileDescriptor` from an app to an `isolatedProcess` service is
 * the basis of the whole sandbox design: the host opens a workspace file with its
 * own authority and hands the descriptor across, so the sandbox needs no
 * filesystem access of its own.
 *
 * It is not obvious that this works. The file lives under `/data/data/<pkg>/`,
 * labelled `app_data_file`, and the receiver runs in `isolated_app`, which has no
 * `allow` rule for that label. The transfer is not a filesystem operation -- the
 * receiving process never opens the path -- but whether the kernel, SELinux or
 * Binder refuses it is a question for a device, not for a policy file.
 *
 * ## Why this uses raw `transact` rather than a typed interface
 *
 * Because the test has to run the *remote* path. Two processes are involved and
 * the receiver is in a different one, so an `as? LocalBinder` cast returns null
 * -- the object here is a `BinderProxy`, which is not the class it stands for.
 * A direct `transact` also removes the temptation of an in-process shortcut: if
 * the service were ever run without `isolatedProcess`, a local call would still
 * "succeed" and the probe would report a boundary that is not there.
 */
class FdPassProbeActivity : Activity() {

	private var bound: Boolean = false

	private val connection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName, binder: IBinder) {
			Log.i(TAG, "==================== RESULT ====================")
			try {
				val input = File(filesDir, "probe.txt").apply {
					writeText("line one\nline two\nline three\n")
				}
				Log.i(TAG, "host wrote ${input.absolutePath} (${input.length()} bytes)")

				// 1. What the isolated process can see of itself.
				Log.i(TAG, callString(binder, TX_IDENTITY))

				// 2. The question: a descriptor into a file it cannot open.
				ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
					Log.i(TAG, "FDPASS: " + callWithFd(binder, TX_READ_VIA_FD, pfd))
				}

				// 3. The control: the same file by name. Must fail.
				Log.i(TAG, "BYPATH: " + callStringArg(binder, TX_READ_BY_PATH, input.absolutePath))

				// 4. Can it open anything at all on its own?
				Log.i(TAG, "OWNOPEN: " + callStringArg(binder, TX_READ_BY_PATH, "/data/data/com.inhuman/files/probe.txt"))

				// 5. The interpreter, running in the throwaway UID.
				//
				// Four separate things, because they fail differently: does the
				// engine load, does arithmetic work, does a loop with a
				// library call work, and does the timeout actually stop a
				// script that never returns.
				Log.i(TAG, "JS-ARITH: " + callStringArg(binder, TX_EVAL_JS, "6 * 7"))
				Log.i(
					TAG,
					"JS-MATH: " + callStringArg(
						binder, TX_EVAL_JS,
						"(() => { let s = 0; for (let i = 1; i <= 100; i++) s += i; " +
							"return 'sum=' + s + ' sqrt=' + Math.sqrt(2).toFixed(6) + " +
							"' json=' + JSON.stringify({a:[1,2,3]}); })()",
					),
				)
				Log.i(TAG, "JS-DATE: " + callStringArg(binder, TX_EVAL_JS, "new Date(0).toISOString()"))
				Log.i(TAG, "JS-REGEX: " + callStringArg(binder, TX_EVAL_JS, "'a1b2c3'.replace(/[0-9]/g, '#')"))
				Log.i(TAG, "JS-HANG: " + callStringArg(binder, TX_EVAL_JS, "while(true){}"))
			} catch (e: Exception) {
				Log.e(TAG, "probe threw ${e.javaClass.simpleName}: ${e.message}")
			}
			Log.i(TAG, "================================================")
		}

		override fun onServiceDisconnected(name: ComponentName) {}
	}

	/** A call with no arguments, returning a string. */
	private fun callString(binder: IBinder, code: Int): String {
		val data = Parcel.obtain()
		val reply = Parcel.obtain()
		return try {
			data.writeInterfaceToken(DESCRIPTOR)
			binder.transact(code, data, reply, 0)
			reply.readException()
			reply.readString() ?: "(null)"
		} catch (e: Exception) {
			"TRANSACT FAILED: ${e.javaClass.simpleName}: ${e.message}"
		} finally {
			data.recycle(); reply.recycle()
		}
	}

	private fun callStringArg(binder: IBinder, code: Int, arg: String): String {
		val data = Parcel.obtain()
		val reply = Parcel.obtain()
		return try {
			data.writeInterfaceToken(DESCRIPTOR)
			data.writeString(arg)
			binder.transact(code, data, reply, 0)
			reply.readException()
			reply.readString() ?: "(null)"
		} catch (e: Exception) {
			"TRANSACT FAILED: ${e.javaClass.simpleName}: ${e.message}"
		} finally {
			data.recycle(); reply.recycle()
		}
	}

	/** The experiment. `writeFileDescriptor` is the whole point of it. */
	private fun callWithFd(binder: IBinder, code: Int, pfd: ParcelFileDescriptor): String {
		val data = Parcel.obtain()
		val reply = Parcel.obtain()
		return try {
			data.writeInterfaceToken(DESCRIPTOR)
			data.writeFileDescriptor(pfd.fileDescriptor)
			binder.transact(code, data, reply, 0)
			reply.readException()
			reply.readString() ?: "(null)"
		} catch (e: Exception) {
			"TRANSACT FAILED: ${e.javaClass.simpleName}: ${e.message}"
		} finally {
			data.recycle(); reply.recycle()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		bound = bindService(
			Intent(this, FdPassProbeService::class.java),
			connection,
			Context.BIND_AUTO_CREATE,
		)
		Log.i(TAG, "bindService returned $bound")
		if (!bound) Log.e(TAG, "could not bind")
	}

	override fun onDestroy() {
		super.onDestroy()
		if (bound) runCatching { unbindService(connection) }
	}

	companion object {
		const val TAG = "FD-PROBE"
		const val DESCRIPTOR = "com.inhuman.probe.FdPassProbe"
		const val TX_IDENTITY = 1
		const val TX_READ_VIA_FD = 2
		const val TX_READ_BY_PATH = 3
		const val TX_EVAL_JS = 4
	}
}

/**
 * Runs in a throwaway UID with no permissions, so it can open nothing itself.
 *
 * `onTransact` is overridden directly rather than generated from AIDL: the
 * question is whether a descriptor survives the Binder transaction, and an AIDL
 * interface would add a build step without changing the answer.
 */
class FdPassProbeService : Service() {

	override fun onBind(intent: Intent): IBinder = object : android.os.Binder() {
		// `reply` is nullable in the platform signature even though it is never
		// null in practice; matching the declared type is what makes this an
		// override at all.
		override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
			// `enforceInterface` returns Unit on this SDK rather than a Boolean,
			// so it is called for its side effect and the descriptor check is
			// applied to the reply instead.
			data.enforceInterface(FdPassProbeActivity.DESCRIPTOR)
			val result = when (code) {
				FdPassProbeActivity.TX_IDENTITY -> identity()
				FdPassProbeActivity.TX_READ_VIA_FD -> readVia(data.readFileDescriptor())
				FdPassProbeActivity.TX_READ_BY_PATH -> readByPath(data.readString().orEmpty())
				FdPassProbeActivity.TX_EVAL_JS -> evalJs(data.readString().orEmpty())
				else -> return super.onTransact(code, data, reply, flags)
			}
			reply?.writeNoException()
			reply?.writeString(result)
			return true
		}
	}

	private fun identity(): String = buildString {
		val uid = Process.myUid()
		append("pid=${Process.myPid()} uid=$uid ")
		append("isolated=${uid in 99_000..99_999 || uid in 90_000..98_999} ")
		append("groups=${statusField("Groups")} ")
		append("filesDir=${runCatching { filesDir?.absolutePath ?: "null" }.getOrElse { "DENIED" }} ")
		// Whether a socket can be *created* says little: the interesting
		// question is whether it can reach anything. Creating one and
		// connecting one fail in different places, and only the second matters
		// for whether the sandbox can exfiltrate.
		append("socketCreate=" + runCatching { java.net.Socket() }
			.fold({ it.close(); "OK" }, { "DENIED:${it.javaClass.simpleName}" }) + " ")
		append(
			"dns=" + runCatching {
				java.net.InetAddress.getByName("example.com").hostAddress
			}.fold({ "RESOLVED:$it" }, { "DENIED:${it.javaClass.simpleName}" }) + " ",
		)
		append(
			"tcp=" + runCatching {
				java.net.Socket().use { s ->
					s.connect(java.net.InetSocketAddress("1.1.1.1", 80), 3000)
					"CONNECTED"
				}
			}.fold({ it }, { "DENIED:${it.javaClass.simpleName}:${it.message?.take(40)}" }) + " ",
		)
		append(nativeProbe())
	}

	/**
	 * The load-bearing question of the entire design.
	 *
	 * If a bundled interpreter cannot be loaded here, there is no way to run one
	 * in this process and the sandbox has to be built some other way. Two routes
	 * are tried because they fail for different reasons:
	 *
	 *  - `System.loadLibrary` is what the runtime does for a JNI library, and it
	 *    resolves the path itself from the app's own metadata.
	 *  - `System.load` with an absolute path is what a hand-rolled interpreter
	 *    bootstrap would use, and it exercises the same `mmap(PROT_EXEC)` path
	 *    that the AOSP commit says was deliberately left working.
	 */
	private fun nativeProbe(): String = buildString {
		val libDir = runCatching { applicationInfo.nativeLibraryDir }.getOrNull()
		append("libDir=${libDir ?: "null"} ")

		// Reading the directory and loading from it are different permissions,
		// and the earlier run showed them disagreeing: `loadLibrary` worked while
		// the listing came back empty. Recorded separately so the two are not
		// confused again.
		val entries = runCatching { libDir?.let { java.io.File(it).list()?.toList() } }.getOrNull()
		append("list=${entries?.joinToString(",") ?: "null"} ")

		// The route a hand-rolled interpreter bootstrap would take: an absolute
		// path, by the file's real name rather than one guessed from a listing
		// that may be empty.
		val real = entries?.firstOrNull()
		append(
			"loadAbs=" + if (real != null) {
				runCatching { System.load("$libDir/$real"); "OK($real)" }
					.fold({ it }, { "FAILED:${it.javaClass.simpleName}:${it.message?.take(70)}" })
			} else {
				"SKIPPED(no listing)"
			} + " ",
		)

		append(
			"loadLibrary=" + runCatching { System.loadLibrary("datastore_shared_counter"); "OK" }
				.fold({ it }, { "FAILED:${it.javaClass.simpleName}:${it.message?.take(50)}" }),
		)
	}

	/** The experiment: read a file we could not have opened ourselves. */
	private fun readVia(pfd: ParcelFileDescriptor?): String {
		if (pfd == null) return "FAILED: no descriptor arrived"
		return runCatching {
			val text = FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
			pfd.close()
			"SUCCESS, ${text.length} chars: ${text.trim().replace("\n", " / ")}"
		}.getOrElse { "FAILED: ${it.javaClass.simpleName}: ${it.message}" }
	}

	/** The control: the same file, by name. Expected to fail. */
	private fun readByPath(path: String): String = runCatching {
		val text = File(path).readText()
		"UNEXPECTED SUCCESS, ${text.length} chars"
	}.getOrElse { "expected failure: ${it.javaClass.simpleName}: ${it.message}" }

	/**
	 * Run JavaScript here, in the throwaway UID.
	 *
	 * The engine's own version is reported alongside the result, because "the
	 * script failed" and "the library failed to load" are very different
	 * outcomes and a single error string would conflate them.
	 */
	private fun evalJs(source: String): String {
		val version = QjsSandbox.version()
		if (version.startsWith("LOAD FAILED")) return "engine=$version"
		val result = QjsSandbox.eval(source)
		return "engine=$version result=$result"
	}

	private fun readQuietly(path: String) =
		runCatching { File(path).readText().trim() }.getOrElse { "DENIED" }

	private fun statusField(key: String) = runCatching {
		File("/proc/self/status").readLines().first { it.startsWith("$key:") }.substringAfter(':').trim()
	}.getOrElse { "unreadable" }
}
