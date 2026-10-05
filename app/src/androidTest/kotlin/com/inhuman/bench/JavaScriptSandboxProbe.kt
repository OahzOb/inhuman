package com.inhuman.bench

import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Is the platform's JavaScript sandbox actually available on this device?
 *
 * ## Why this is asked on a device rather than read from documentation
 *
 * `_research/android-sandbox/` concluded that running agent-authored code means
 * building it: bundle QuickJS, write the JNI, design a Binder protocol and a
 * virtual filesystem over it, and cap the heap inside the engine because
 * *"there is no public API to cap a child's CPU or memory"*.
 *
 * That last clause is what `androidx.javascriptengine` appears to retire --
 * `IsolateStartupParameters.setMaxHeapSizeBytes` is exactly the missing knob,
 * and `JavaScriptSandbox` is V8 in an isolated process. But "appears to" is not
 * evidence: `JavaScriptSandbox.isSupported()` depends on the WebView on the
 * device, WebView is upgradeable, and OEM builds differ. A probe that runs is
 * the only answer that means anything, so this is that probe.
 *
 * ## What it establishes, and what it does not
 *
 * It establishes whether the API is usable here at all, and whether a heap
 * limit can be set at *construction*. It does not measure performance against
 * the self-built QuickJS the earlier research measured, and it does not test
 * file access from inside the isolate -- both are follow-ups, not assumptions.
 */
@RunWith(AndroidJUnit4::class)
class JavaScriptSandboxProbe {

	@Test
	fun probe() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val report = StringBuilder("\n")
		report.appendLine("device           ${android.os.Build.MODEL}")
		report.appendLine("sdk              ${android.os.Build.VERSION.SDK_INT}")
		report.appendLine("abis             ${android.os.Build.SUPPORTED_ABIS.joinToString()}")

		// WebView is the thing being depended on, so its version is part of the
		// answer -- a "false" on one device and "true" on another is explained
		// here or nowhere.
		report.appendLine("webview          ${webViewVersion(context) ?: "(not reported)"}")
		report.appendLine("isSupported      ${JavaScriptSandbox.isSupported()}")

		if (!JavaScriptSandbox.isSupported()) {
			report.appendLine("VERDICT          unavailable on this device")
			println(report)
			return
		}

		val sandbox = try {
			JavaScriptSandbox.createConnectedInstanceAsync(context).get(30, TimeUnit.SECONDS)
		} catch (e: Exception) {
			report.appendLine("connect failed   ${e.javaClass.simpleName}: ${e.message}")
			println(report)
			return
		}

		sandbox.use { box ->
			// The two overloads are reported rather than assumed: `javap` shows
			// only `createIsolate()`, so whether startup parameters are wired up
			// yet is exactly the thing that a compile error would settle and a
			// device cannot. Try both, say which worked.
			report.appendLine("isolate(params)  ${tryIsolateWithHeapLimit(box, report)}")

			box.createIsolate().use { isolate ->
				// A number comes back empty on API 31+ because the platform stopped
				// letting bare numbers cross Binder; stringify so the probe reports
				// about the sandbox rather than about Parcel.
				report.appendLine("js 1+1           ${eval(isolate, "String(1 + 1)")}")
				report.appendLine("js Date          ${eval(isolate, "new Date(0).toISOString()")}")
				report.appendLine("js JSON          ${eval(isolate, "JSON.stringify({a:[1,2,3]})")}")
				// The sandbox is the point: prove it cannot reach the filesystem.
				report.appendLine(
					"js no-fs         ${
						eval(
							isolate,
							"typeof require === 'undefined' && typeof process === 'undefined' " +
								"? 'no require/process (expected)' : 'LEAKED require/process'",
						)
					}",
				)
				// What the sandbox does expose, since a runtime has to be written
				// against this surface rather than against a browser's.
				//
				// `globalThis`, not `this`: the sandbox evaluates in strict mode,
				// where a plain function's `this` is undefined -- which is how the
				// first version of this line failed, inside the probe rather than
				// inside the thing being probed.
				report.appendLine(
					"js globals       ${
						eval(
							isolate,
							"['fetch','atob','btoa','TextEncoder','setTimeout','console','WebAssembly']" +
								".map(function(k){ return k + ':' + (typeof globalThis[k]) }).join(' ')",
						)
					}",
				)
				report.appendLine("js wasm          ${probeWasm(isolate)}")
				report.appendLine("js runaway       ${probeRunaway(isolate)}")
			}
		}

		println(report)
	}

	/**
	 * Whether an isolate can be created with an explicit heap ceiling.
	 *
	 * Reported as a fact either way: if the call does not exist or does not take,
	 * the platform has no heap knob and the earlier research's conclusion stands
	 * for that part.
	 */
	private fun tryIsolateWithHeapLimit(box: JavaScriptSandbox, report: StringBuilder): String {
		val params = IsolateStartupParameters()
		return try {
			params.maxHeapSizeBytes = HEAP_LIMIT_BYTES
			report.appendLine("heap limit ask   $HEAP_LIMIT_BYTES bytes")
			val isolate = box.createIsolate(params)
			isolate.use { eval(it, "1") }
			"accepted"
		} catch (e: NoSuchMethodError) {
			"NO SUCH OVERLOAD (${e.message?.take(60)})"
		} catch (e: Throwable) {
			"${e.javaClass.simpleName}: ${e.message?.take(80)}"
		}
	}

	/**
	 * WebAssembly, since a WASM runtime would widen what the sandbox can run.
	 *
	 * The first attempt at this used `atob` to decode a base64 module and failed
	 * with `EvaluationFailedException` -- `atob` does not exist in the sandbox,
	 * which has a deliberately tiny global surface. That was a bug in the probe,
	 * not a missing feature. `provideNamedData` is the supported way in: the
	 * host hands over bytes and the script refers to them by name.
	 *
	 * The module is the four-byte magic, the four-byte version, and nothing
	 * else -- the smallest thing a real compiler accepts.
	 */
	private fun probeWasm(isolate: JavaScriptIsolate): String = try {
		val emptyModule = byteArrayOf(
			0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
		)
		isolate.provideNamedData("wasm-bytes", emptyModule)
		eval(
			isolate,
			"""
			(function () {
				if (typeof WebAssembly !== 'object') return 'no WebAssembly';
				var bytes = consumeNamedData('wasm-bytes');
				var mod = new WebAssembly.Module(bytes);
				var inst = new WebAssembly.Instance(mod, {});
				return 'compiled and instantiated, exports: ' +
					Object.keys(inst.exports).length;
			})()
			""".trimIndent(),
		)
	} catch (e: Exception) {
		val cause = e.cause ?: e
		"${cause.javaClass.simpleName}: ${cause.message?.take(90)}"
	}

	/**
	 * A runaway loop, to see whether the platform stops it without our help.
	 *
	 * The earlier research needed an interrupt handler inside the engine for
	 * this. If the sandbox terminates the isolate instead, that whole mechanism
	 * is also platform-provided.
	 */
	private fun probeRunaway(isolate: JavaScriptIsolate): String = try {
		eval(isolate, "while(true){}", timeoutSeconds = 5)
		"returned (did NOT hang the caller)"
	} catch (e: Exception) {
		"${e.javaClass.simpleName}: ${e.message?.take(60)}"
	}

	private fun eval(
		isolate: JavaScriptIsolate,
		script: String,
		timeoutSeconds: Long = 20,
	): String = isolate.evaluateJavaScriptAsync(script).get(timeoutSeconds, TimeUnit.SECONDS)

	private fun webViewVersion(context: android.content.Context): String? = runCatching {
		android.webkit.WebView.getCurrentWebViewPackage()?.versionName
	}.getOrNull()

	private companion object {
		/** 16 MiB: large enough to be useful, small enough to matter. */
		const val HEAP_LIMIT_BYTES = 16L * 1024 * 1024
	}
}
