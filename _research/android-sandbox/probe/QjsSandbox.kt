package com.inhuman.probe

/**
 * The bundled JavaScript engine, reachable from Java.
 *
 * QuickJS rather than Lua or Python, for the reasons the size measurement
 * settled: this compiles to about 1.1 MB, which is nothing beside the rest of
 * the APK, while a Python runtime would add fifteen to forty and bring a
 * standard library that has to be delivered through a filesystem the sandbox
 * deliberately cannot see.
 *
 * The engine is compiled into `libqjssandbox.so` along with this JNI layer, so
 * one library is the whole runtime. There is no separate standard-library tree
 * to mount, which is the problem that makes Python awkward here rather than any
 * deficiency of the language.
 *
 * ## What is deliberately absent
 *
 * No file access, no network, no timers. Those arrive as host-mediated
 * operations, and the point of this class is to prove the interpreter runs at
 * all inside an isolated process before any of that is designed.
 */
object QjsSandbox {

	/**
	 * Loaded lazily and once.
	 *
	 * The load must go through `System.loadLibrary` rather than
	 * `System.load` with a path: AGP stores native libraries uncompressed
	 * inside the APK and does not extract them, so the app's
	 * `nativeLibraryDir` is an empty directory and an absolute path into it
	 * fails. The class loader knows how to reach them where they actually are.
	 */
	private val loaded: Result<Unit> by lazy {
		runCatching { System.loadLibrary("qjssandbox") }
	}

	/** Human-readable engine version, or the reason it could not load. */
	fun version(): String = loaded.fold(
		{ nativeVersion() },
		{ "LOAD FAILED: ${it.javaClass.simpleName}: ${it.message}" },
	)

	/**
	 * Run a script and return a small JSON envelope.
	 *
	 * `{"ok":true,"value":...}` or `{"ok":false,"error":...,"timeout":...}`.
	 * Enveloped rather than returning the value directly because the caller has
	 * to tell "the script produced this" from "the script failed", and a bare
	 * string cannot carry both.
	 */
	fun eval(source: String, timeoutMs: Long = 5000): String {
		val failure = loaded.exceptionOrNull()
		if (failure != null) {
			return """{"ok":false,"error":"engine unavailable: ${escape(failure.message ?: "load failed")}"}"""
		}

		val handle = nativeCreate()
		if (handle == 0L) return """{"ok":false,"error":"could not create engine"}"""
		return try {
			nativeEval(handle, source, timeoutMs)
		} finally {
			// Always freed, including on the timeout path: a leaked runtime
			// would hold its memory limit against the process for good.
			nativeDestroy(handle)
		}
	}

	private fun escape(s: String): String =
		s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").take(200)

	private external fun nativeVersion(): String
	private external fun nativeCreate(): Long
	private external fun nativeDestroy(handle: Long)
	private external fun nativeEval(handle: Long, source: String, timeoutMs: Long): String
}
