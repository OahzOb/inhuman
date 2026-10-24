package com.inhuman.desktop

import com.inhuman.core.common.diag.Diag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopDiagnosticsTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `installs a file sink and keeps secrets out`() {
		val dir = tmp.newFolder("logs")
		try {
			val file = DesktopDiagnostics.install(dir)
			Diag.i("test", "started with key sk-abcdefghijklmnop")
			Diag.w("test", "boom", IllegalStateException("bad"))

			val text = file.readText()
			assertTrue(text.contains("I/test"))
			assertTrue(text.contains("boom"))
			assertFalse("api keys are redacted by Diag", text.contains("sk-abcdefghijklmnop"))
		} finally {
			Diag.install(null)
		}
	}
}
