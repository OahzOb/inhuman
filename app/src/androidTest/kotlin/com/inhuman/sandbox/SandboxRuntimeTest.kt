package com.inhuman.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tool's two promises on the device: a result comes back, and the script
 * cannot see the network/module APIs. The platform sandbox is conditional on
 * WebView, so an unsupported device skips rather than failing; the tool is
 * absent there, which is the product rule.
 */
@RunWith(AndroidJUnit4::class)
class SandboxRuntimeTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun arithmeticComesBackAsAString() = runBlocking {
		assumeTrue("platform sandbox unavailable", SandboxRuntime.available(context))
		val result = SandboxRuntime.run(context, "return 1 + 1")
		assertTrue(result.toString(), result.isSuccess)
		assertEquals("2", result.getOrThrow())
	}

	@Test
	fun networkAndModuleApisAreAbsent() = runBlocking {
		assumeTrue("platform sandbox unavailable", SandboxRuntime.available(context))
		val result = SandboxRuntime.run(
			context,
			"return [typeof fetch, typeof require, typeof process].join(',')",
		)
		assertTrue(result.toString(), result.isSuccess)
		assertEquals("undefined,undefined,undefined", result.getOrThrow())
	}

	@Test
	fun aRunawayScriptIsStopped() = runBlocking {
		assumeTrue("platform sandbox unavailable", SandboxRuntime.available(context))
		val result = SandboxRuntime.run(context, "while (true) {}", timeoutSeconds = 1)
		assertFalse(result.toString(), result.isSuccess)
	}
}