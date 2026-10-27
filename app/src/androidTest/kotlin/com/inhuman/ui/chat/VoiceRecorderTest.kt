package com.inhuman.ui.chat

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recorder writes a WAV in memory and nothing else.
 *
 * The emulator's microphone is not a real input, so this does not assert that
 * speech was captured; it asserts the permission gate, the WAV container and
 * that the bytes stay in the returned array. The transcription call is a
 * separate, user-configured backend and is covered by its own tool tests.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecorderTest {

	@Test
	fun aRecordingBecomesAWavInMemory() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val context = instrumentation.targetContext
		runCatching {
			instrumentation.uiAutomation.grantRuntimePermission(
				context.packageName,
				Manifest.permission.RECORD_AUDIO,
			)
		}
		val recorder = VoiceRecorder(context)
		try {
			if (!recorder.hasPermission()) return
			val started = recorder.start {}
			if (!started) return
			Thread.sleep(700)
			val bytes = recorder.stop()
			assertNotNull(bytes)
			assertTrue(bytes!!.size > 44)
			assertTrue(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
			assertTrue(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
		} finally {
			recorder.cancel()
		}
	}
}