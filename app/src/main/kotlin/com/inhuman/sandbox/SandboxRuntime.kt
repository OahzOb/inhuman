package com.inhuman.sandbox

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.inhuman.AppCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The platform V8 sandbox for model-authored JavaScript.
 *
 * The capability sentence is the point: code runs, but it cannot fetch, open a
 * socket, require a module or see a filesystem. The engine starts in an isolated
 * process with a 16 MiB heap; the host only passes a string in and a string out.
 * That is why this is a code calculator, not a second terminal.
 *
 * ## Fail closed
 *
 * Availability is probed on the device, once per process, before the tool is
 * offered. If the probe cannot create an isolate, cannot set the heap ceiling,
 * or finds `fetch`/`XMLHttpRequest`/`require`/`process`/`WebSocket`, the tool is
 * absent and the reason is exposed to Diagnostics. A warning with the tool still
 * present would be the wrong promise.
 */
object SandboxRuntime {

	private val stateLock = Mutex()
	private val runLock = Mutex()
	private var checked = false
	private var available = false
	private var failure: String? = null
	private var sandbox: JavaScriptSandbox? = null

	val failureReason: String?
		get() = failure

	suspend fun available(context: Context): Boolean = stateLock.withLock {
		if (checked) return available
		checked = true
		available = runCatching { probe(context.applicationContext) }
			.getOrElse { error ->
				failure = error.message ?: error.javaClass.simpleName
				false
			}
		AppCapabilities.recordSandbox(available, failure)
		available
	}

	/**
	 * Run [script] and return its stringified result.
	 *
	 * The script runs inside a function, so it must `return` what it wants; a
	 * bare trailing expression is deliberately not the contract. A timeout or a
	 * script error is a tool error, never an empty success.
	 */
	suspend fun run(
		context: Context,
		script: String,
		timeoutSeconds: Long = 10,
	): Result<String> = runLock.withLock {
		if (!available(context)) {
			return Result.failure(IllegalStateException(failure ?: "The JavaScript sandbox is unavailable."))
		}
		if (script.isBlank()) return Result.failure(IllegalArgumentException("The script is empty."))
		if (script.length > MAX_SCRIPT_CHARS) {
			return Result.failure(
				IllegalArgumentException("The script is longer than $MAX_SCRIPT_CHARS characters."),
			)
		}
		val isolate = try {
			newIsolate(context)
		} catch (e: Exception) {
			return Result.failure(IllegalStateException(e.message ?: "Could not start the sandbox."))
		}
		try {
			val wrapped = """
				(function () {
					"use strict";
					const __result = (function () {
				$script
					})();
					if (typeof __result === "string") return __result;
					try { return JSON.stringify(__result); } catch (e) { return String(__result); }
				})()
			""".trimIndent()
			val text = withContext(Dispatchers.IO) {
				isolate.evaluateJavaScriptAsync(wrapped).get(timeoutSeconds, TimeUnit.SECONDS)
			}
			Result.success(if (text.length > MAX_OUTPUT_CHARS) {
				text.take(MAX_OUTPUT_CHARS) + "\n… output truncated"
			} else {
				text
			})
		} catch (e: TimeoutException) {
			Result.failure(IllegalStateException("The script exceeded ${timeoutSeconds}s and was stopped."))
		} catch (e: ExecutionException) {
			val cause = e.cause ?: e
			Result.failure(IllegalStateException(cause.message ?: cause.javaClass.simpleName))
		} catch (e: Exception) {
			Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName))
		} finally {
			// Closing the isolate is what actually stops a runaway script; a
			// cancelled Future alone leaves the engine working.
			runCatching { isolate.close() }
		}
	}

	private fun probe(context: Context): Boolean {
		if (!JavaScriptSandbox.isSupported()) {
			failure = "The platform JavaScript sandbox is not supported on this device."
			return false
		}
		val box = newSandbox(context)
		val isolate = newIsolate(context)
		try {
			val globals = isolate.evaluateJavaScriptAsync(
				"""
				JSON.stringify({
					fetch: typeof fetch,
					xhr: typeof XMLHttpRequest,
					websocket: typeof WebSocket,
					require: typeof require,
					process: typeof process,
				})
				""".trimIndent(),
			).get(20, TimeUnit.SECONDS)
			val leaked = listOf("fetch", "xhr", "websocket", "require", "process")
				.any { globals.contains("\"$it\":\"") && !globals.contains("\"$it\":\"undefined\"") }
			if (leaked) {
				failure = "The JavaScript sandbox exposes a network or module API: $globals"
				return false
			}
			val arithmetic = isolate.evaluateJavaScriptAsync("String(1 + 1)").get(10, TimeUnit.SECONDS)
			if (arithmetic != "2") {
				failure = "The JavaScript sandbox returned an unexpected probe result."
				return false
			}
			return true
		} catch (e: Exception) {
			failure = "The JavaScript sandbox probe failed: ${e.message ?: e.javaClass.simpleName}"
			return false
		} finally {
			runCatching { isolate.close() }
			runCatching { box.close() }
			sandbox = null
		}
	}

	private fun newSandbox(context: Context): JavaScriptSandbox {
		sandbox?.let { return it }
		val created = try {
			JavaScriptSandbox.createConnectedInstanceAsync(context).get(20, TimeUnit.SECONDS)
		} catch (e: Exception) {
			failure = "Could not connect to the JavaScript sandbox: ${e.message ?: e.javaClass.simpleName}"
			throw IllegalStateException(failure, e)
		}
		sandbox = created
		return created
	}

	private fun newIsolate(context: Context): JavaScriptIsolate {
		val box = newSandbox(context)
		val parameters = IsolateStartupParameters()
		parameters.maxHeapSizeBytes = MAX_HEAP_BYTES
		return box.createIsolate(parameters)
	}

	private const val MAX_SCRIPT_CHARS = 20_000
	private const val MAX_OUTPUT_CHARS = 100_000
	private const val MAX_HEAP_BYTES = 16L * 1024 * 1024
}